/*
 * Copyright (c) 2025, 2025, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.svm.core.graal.amd64;

import static jdk.graal.compiler.lir.LIRInstruction.OperandFlag.COMPOSITE;
import static jdk.graal.compiler.lir.LIRInstruction.OperandFlag.REG;
import static jdk.vm.ci.code.ValueUtil.asRegister;

import com.oracle.svm.core.ReservedRegisters;
import com.oracle.svm.core.gc.shenandoah.ShenandoahConstants;
import com.oracle.svm.core.graal.meta.SubstrateForeignCallLinkage;

import jdk.graal.compiler.asm.Label;
import jdk.graal.compiler.asm.amd64.AMD64Address;
import jdk.graal.compiler.asm.amd64.AMD64Assembler.ConditionFlag;
import jdk.graal.compiler.asm.amd64.AMD64BaseAssembler.OperandSize;
import jdk.graal.compiler.asm.amd64.AMD64MacroAssembler;
import jdk.graal.compiler.core.common.LIRKind;
import jdk.graal.compiler.core.common.spi.ForeignCallLinkage;
import jdk.graal.compiler.lir.LIRInstructionClass;
import jdk.graal.compiler.lir.Opcode;
import jdk.graal.compiler.lir.amd64.AMD64AddressValue;
import jdk.graal.compiler.lir.amd64.AMD64Call;
import jdk.graal.compiler.lir.amd64.AMD64LIRInstruction;
import jdk.graal.compiler.lir.asm.CompilationResultBuilder;

import jdk.vm.ci.amd64.AMD64;
import jdk.vm.ci.amd64.AMD64Kind;
import jdk.vm.ci.code.Register;
import jdk.vm.ci.meta.AllocatableValue;

/**
 * SubstrateVM AMD64 self-healing load-reference barrier applied to the <em>location</em> of an oop
 * field that is about to be atomically updated (compare-and-swap or getAndSet).
 *
 * Instead of strictly following the Hotspot backend's implementation in {@link
 * jdk.graal.compiler.hotspot.amd64.shenandoah.AMD64HotSpotShenandoahCompareAndSwapOp} this, class
 * implements the newer "fix up early" Shenandoah atomic barrier model (HotSpot JDK-8384080 /
 * JDK-8383810): rather than teaching the atomic itself to tolerate a from-space pointer (a multi-step
 * retry), we heal the field to its to-space value <em>before</em> a plain atomic. After healing, the
 * field holds the canonical (to-space) reference, so a plain CAS compares to-space against to-space
 * and cannot suffer a concurrent-evacuation false negative (which would otherwise leave a stale
 * from-space pointer in the field, later crashing concurrent marking).
 *
 * Fully inlined fast-path (mirrors HotSpot's inline LRB + cmpxchg_oop resolution):
 * <ol>
 * <li>skip if the thread register is not yet set up (very early isolate creation);</li>
 * <li>skip if the heap has no forwarded objects ({@code gc_state & HAS_FORWARDED == 0});</li>
 * <li>skip if the current field value is null;</li>
 * <li>skip if the referenced object is <em>not</em> in the collection set (biased fast-test map):
 * only from-space (in-cset) references need healing;</li>
 * <li>read the object's mark word; if it is forwarded ({@code mark & MARK_FORWARDED_MASK != 0} with a
 * non-null forwardee), extract the to-space forwardee ({@code mark & ~MARK_FORWARDED_MASK}),
 * re-encode it as a narrow reference and CAS-heal the field in place. No runtime call is needed - this
 * is the common case throughout the (long) concurrent update-refs phase.</li>
 * </ol>
 * Only when the object is in the collection set but <em>not yet evacuated</em> (or self-forwarded)
 * does it fall through to the runtime stub {@code svm_gc_load_reference_barrier_heal}, which evacuates
 * (if needed), resolves and CAS-heals the field.
 */
@Opcode("SHENANDOAH_CAS_HEAL")
public class AMD64SubstrateShenandoahCASHealOp extends AMD64LIRInstruction {
    public static final LIRInstructionClass<AMD64SubstrateShenandoahCASHealOp> TYPE = LIRInstructionClass.create(AMD64SubstrateShenandoahCASHealOp.class);

    /** The address of the reference field that is about to be atomically updated. */
    @Alive({COMPOSITE}) private AMD64AddressValue address;

    /** Scratch: gc_state byte / cset map base / mark word / forwardee / new narrow reference. */
    @Temp({REG}) private AllocatableValue tmp;

    /** Scratch: the loaded field value (old narrow reference, also the CAS-heal expected value). */
    @Temp({REG}) private AllocatableValue tmp2;

    /** Scratch: the decoded object address (to read its mark word and compute its region index). */
    @Temp({REG}) private AllocatableValue tmp3;

    /** Scratch: cset region index / slot address, and a save slot for rax across the CAS-heal. */
    @Temp({REG}) private AllocatableValue tmp4;

    /**
     * The calling-convention register that the slow-path stub call passes its argument (the field
     * address) in. Declared {@link Temp} so the register allocator will not keep any other live
     * operand in it across the call.
     */
    @Temp({REG}) private AllocatableValue callArg;

    /**
     * rax, reserved as a {@link Temp} because the inline CAS-heal uses {@code cmpxchg} (whose comparand
     * is hardwired to rax). Reserving it keeps the other scratch registers off rax and lets us clobber
     * rax freely; the caller preserves any value it needs across this op (the compare-and-swap expected
     * value - see emitCompareAndSwapOp).
     */
    @Temp({REG}) private AllocatableValue raxTemp;

    /** The runtime stub that evacuates (if needed), resolves and heals the field. */
    private final ForeignCallLinkage callTarget;

    /** Whether the reference field is a full machine word (8 bytes) vs. 32 bits. */
    private final boolean loadWordSized;

    /** Compression shift for the narrow reference (0 on Graal CE). */
    private final int narrowShift;

    /**
     * Whether the inline path is possible. It requires a heap-base-relative reference encoding (always
     * the case for SubstrateVM Shenandoah). If false, the barrier conservatively calls the stub
     * whenever the heap has forwarded objects.
     */
    private final boolean canInlineCsetCheck;

    public AMD64SubstrateShenandoahCASHealOp(AMD64AddressValue address, AllocatableValue tmp, AllocatableValue tmp2, AllocatableValue tmp3, AllocatableValue tmp4,
                    ForeignCallLinkage callTarget, boolean loadWordSized, int narrowShift, boolean canInlineCsetCheck) {
        super(TYPE);
        this.address = address;
        this.tmp = tmp;
        this.tmp2 = tmp2;
        this.tmp3 = tmp3;
        this.tmp4 = tmp4;
        this.callArg = (AllocatableValue) callTarget.getOutgoingCallingConvention().getArgument(0);
        this.raxTemp = AMD64.rax.asValue(LIRKind.value(AMD64Kind.QWORD));
        this.callTarget = callTarget;
        this.loadWordSized = loadWordSized;
        this.narrowShift = narrowShift;
        this.canInlineCsetCheck = canInlineCsetCheck;
    }

    @Override
    public void emitCode(CompilationResultBuilder crb, AMD64MacroAssembler masm) {
        Register thread = ReservedRegisters.singleton().getThreadRegister();
        Register heapBase = ReservedRegisters.singleton().getHeapBaseRegister();
        Register rtmp = asRegister(tmp);
        Register rold = asRegister(tmp2);
        Register robj = asRegister(tmp3);
        Register ridx = asRegister(tmp4);
        OperandSize opSize = loadWordSized ? OperandSize.QWORD : OperandSize.DWORD;
        AMD64Address fieldAddr = address.toAddress(masm);

        Label done = new Label();
        Label slowPath = new Label();

        // Skip the barrier if the thread register is not yet set up (very early isolate-creation code).
        masm.testq(thread, thread);
        masm.jcc(ConditionFlag.Zero, done);

        // Fast path: only heal when the heap has forwarded objects (evacuation / update-refs).
        masm.movb(rtmp, new AMD64Address(thread, ShenandoahConstants.gcStateOffset()));
        masm.testlAndJcc(rtmp, ShenandoahConstants.GC_STATE_HAS_FORWARDED, ConditionFlag.Zero, done, false);

        if (canInlineCsetCheck) {
            // Load the current field value (the old narrow reference). Null needs no healing.
            if (loadWordSized) {
                masm.movq(rold, fieldAddr);
            } else {
                masm.movl(rold, fieldAddr);
            }
            masm.testAndJcc(opSize, rold, rold, ConditionFlag.Zero, done, false);

            // Decode the object address: obj = heapBase + (value << shift).
            masm.movq(robj, rold);
            if (narrowShift != 0) {
                masm.shlq(robj, narrowShift);
            }
            masm.addq(robj, heapBase);

            // Collection-set fast test: index = obj >> region_size_shift; the biased map is indexed
            // directly by that value. A zero map byte means not in the collection set -> no healing.
            masm.movq(ridx, robj);
            masm.shrq(ridx, ShenandoahConstants.logOfHeapRegionGrainBytes());
            masm.movq(rtmp, new AMD64Address(thread, ShenandoahConstants.csetMapAddressOffset()));
            masm.addq(ridx, rtmp);
            masm.cmpb(new AMD64Address(ridx), 0);
            masm.jcc(ConditionFlag.Zero, done);

            // In the collection set. Read the object's mark word; if it is forwarded, the mark word
            // holds the (lock-bit-tagged) to-space forwardee. If not yet evacuated (or self-forwarded),
            // fall back to the runtime stub which evacuates/resolves.
            masm.movq(rtmp, new AMD64Address(robj, ShenandoahConstants.markOffset()));
            masm.testlAndJcc(rtmp, ShenandoahConstants.MARK_FORWARDED_MASK, ConditionFlag.Zero, slowPath, false);
            masm.andq(rtmp, ~ShenandoahConstants.MARK_FORWARDED_MASK);   // rtmp = forwardee (full pointer)
            masm.testqAndJcc(rtmp, rtmp, ConditionFlag.Zero, slowPath, false); // marked but null fwd -> stub

            // Re-encode the forwardee as a narrow reference: newNarrow = (forwardee - heapBase) >> shift.
            masm.subq(rtmp, heapBase);
            if (narrowShift != 0) {
                masm.shrq(rtmp, narrowShift);
            }

            // CAS-heal the field from the old (from-space) narrow value to the to-space narrow value.
            // cmpxchg uses rax as the comparand (rax is reserved via raxTemp); the caller preserves any
            // value it needs in rax across this op. Best-effort: a losing CAS just means another thread
            // already updated the field.
            masm.movq(AMD64.rax, rold);
            if (crb.target.isMP) {
                masm.lock();
            }
            if (loadWordSized) {
                masm.cmpxchgq(rtmp, fieldAddr);
            } else {
                masm.cmpxchgl(fieldAddr, rtmp);
            }
        } else {
            // No heap-base-relative encoding: cannot decode inline, so take the stub whenever the heap
            // has forwarded objects.
            masm.jmp(slowPath);
        }
        masm.bind(done);

        // Out-of-line slow path: the object is in the collection set but not yet evacuated (or
        // self-forwarded). Pass the field address to the runtime stub, which evacuates/resolves and
        // CAS-heals the field in place.
        crb.getLIR().addSlowPath(this, () -> {
            masm.bind(slowPath);
            masm.leaq(asRegister(callArg), fieldAddr);
            AMD64Call.directCall(crb, masm, ((SubstrateForeignCallLinkage) callTarget).getMethod(), null, false, null);
            masm.jmp(done);
        });
    }
}
