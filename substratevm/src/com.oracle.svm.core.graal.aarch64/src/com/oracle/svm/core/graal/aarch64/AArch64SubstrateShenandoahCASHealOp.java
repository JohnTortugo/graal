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
package com.oracle.svm.core.graal.aarch64;

import static jdk.graal.compiler.asm.aarch64.AArch64Address.AddressingMode.IMMEDIATE_SIGNED_UNSCALED;
import static jdk.graal.compiler.lir.LIRInstruction.OperandFlag.REG;
import static jdk.vm.ci.code.ValueUtil.asRegister;

import com.oracle.svm.core.ReservedRegisters;
import com.oracle.svm.core.gc.shenandoah.ShenandoahConstants;
import com.oracle.svm.core.graal.meta.SubstrateForeignCallLinkage;

import jdk.graal.compiler.asm.Label;
import jdk.graal.compiler.asm.aarch64.AArch64Address;
import jdk.graal.compiler.asm.aarch64.AArch64Assembler.ConditionFlag;
import jdk.graal.compiler.asm.aarch64.AArch64MacroAssembler;
import jdk.graal.compiler.asm.aarch64.AArch64MacroAssembler.ScratchRegister;
import jdk.graal.compiler.core.common.spi.ForeignCallLinkage;
import jdk.graal.compiler.lir.LIRInstructionClass;
import jdk.graal.compiler.lir.Opcode;
import jdk.graal.compiler.lir.aarch64.AArch64Call;
import jdk.graal.compiler.lir.aarch64.AArch64LIRInstruction;
import jdk.graal.compiler.lir.asm.CompilationResultBuilder;

import jdk.vm.ci.code.Register;
import jdk.vm.ci.meta.AllocatableValue;

/**
 * SubstrateVM AArch64 self-healing load-reference barrier applied to the <em>location</em> of an oop
 * field that is about to be atomically updated (compare-and-swap or getAndSet).
 *
 * The AArch64 counterpart of {@link com.oracle.svm.core.graal.amd64.AMD64SubstrateShenandoahCASHealOp}.
 * Like that op, it implements the "fix up early" Shenandoah atomic barrier model (HotSpot
 * JDK-8384080 / JDK-8383810): rather than teaching the atomic itself to tolerate a from-space
 * pointer (the multi-step retry used by the Graal HotSpot backend op
 * {@link jdk.graal.compiler.hotspot.aarch64.shenandoah.AArch64HotSpotShenandoahCompareAndSwapOp}),
 * we heal the field to its to-space value <em>before</em> a plain atomic. After healing, the field
 * holds the canonical (to-space) reference, so a plain CAS compares to-space against to-space and
 * cannot suffer a concurrent-evacuation false negative.
 *
 * Fully inlined fast-path:
 * <ol>
 * <li>skip if the thread register is not yet set up (very early isolate creation);</li>
 * <li>skip if the heap has no forwarded objects ({@code gc_state & HAS_FORWARDED == 0});</li>
 * <li>skip if the current field value is null;</li>
 * <li>skip if the referenced object is <em>not</em> in the collection set (biased fast-test map);</li>
 * <li>read the object's mark word; if it is forwarded, extract the to-space forwardee, re-encode it
 * as a narrow reference and CAS-heal the field in place.</li>
 * </ol>
 * Only when the object is in the collection set but not yet evacuated (or self-forwarded) does it
 * fall through to the runtime stub {@code svm_gc_load_reference_barrier_heal}.
 */
@Opcode("SHENANDOAH_CAS_HEAL")
public class AArch64SubstrateShenandoahCASHealOp extends AArch64LIRInstruction {
    public static final LIRInstructionClass<AArch64SubstrateShenandoahCASHealOp> TYPE = LIRInstructionClass.create(AArch64SubstrateShenandoahCASHealOp.class);

    /** The address of the reference field that is about to be atomically updated (in a register). */
    @Alive({REG}) private AllocatableValue address;

    /** Scratch: gc_state byte / cset map base / mark word / forwardee / new narrow reference. */
    @Temp({REG}) private AllocatableValue tmp;

    /** Scratch: the loaded field value (old narrow reference, also the CAS-heal expected value). */
    @Temp({REG}) private AllocatableValue tmp2;

    /** Scratch: the decoded object address (to read its mark word and compute its region index). */
    @Temp({REG}) private AllocatableValue tmp3;

    /**
     * The calling-convention register that the slow-path stub call passes its argument (the field
     * address) in. On SubstrateVM the foreign-call argument is passed in a register (not a stack
     * slot); declared {@link Temp} so the allocator keeps other live operands out of it.
     */
    @Temp({REG}) private AllocatableValue callArg;

    /** The runtime stub that evacuates (if needed), resolves and heals the field. */
    private final ForeignCallLinkage callTarget;

    /** Whether the reference field is a full machine word (8 bytes) vs. 32 bits. */
    private final boolean loadWordSized;

    /** Compression shift for the narrow reference (0 on Graal CE). */
    private final int narrowShift;

    /**
     * Whether the inline path is possible. It requires a heap-base-relative reference encoding
     * (always the case for SubstrateVM Shenandoah). If false, the barrier conservatively calls the
     * stub whenever the heap has forwarded objects.
     */
    private final boolean canInlineCsetCheck;

    public AArch64SubstrateShenandoahCASHealOp(AllocatableValue address, AllocatableValue tmp, AllocatableValue tmp2, AllocatableValue tmp3,
                    ForeignCallLinkage callTarget, boolean loadWordSized, int narrowShift, boolean canInlineCsetCheck) {
        super(TYPE);
        this.address = address;
        this.tmp = tmp;
        this.tmp2 = tmp2;
        this.tmp3 = tmp3;
        this.callArg = (AllocatableValue) callTarget.getOutgoingCallingConvention().getArgument(0);
        this.callTarget = callTarget;
        this.loadWordSized = loadWordSized;
        this.narrowShift = narrowShift;
        this.canInlineCsetCheck = canInlineCsetCheck;
    }

    @Override
    public void emitCode(CompilationResultBuilder crb, AArch64MacroAssembler masm) {
        Register thread = ReservedRegisters.singleton().getThreadRegister();
        Register heapBase = ReservedRegisters.singleton().getHeapBaseRegister();
        Register addrReg = asRegister(address);
        Register rtmp = asRegister(tmp);
        Register rold = asRegister(tmp2);
        Register robj = asRegister(tmp3);
        int size = loadWordSized ? 64 : 32;
        AArch64Address fieldAddr = AArch64Address.createImmediateAddress(size, IMMEDIATE_SIGNED_UNSCALED, addrReg, 0);

        Label done = new Label();
        Label slowPath = new Label();

        // Skip the barrier if the thread register is not yet set up (very early isolate-creation code).
        masm.cbz(64, thread, done);

        // Fast path: only heal when the heap has forwarded objects (evacuation / update-refs).
        masm.ldr(8, rtmp, masm.makeAddress(8, thread, ShenandoahConstants.gcStateOffset()));
        masm.tst(64, rtmp, ShenandoahConstants.GC_STATE_HAS_FORWARDED);
        masm.branchConditionally(ConditionFlag.EQ, done);

        if (canInlineCsetCheck) {
            // Load the current field value (the old narrow reference). Null needs no healing.
            masm.ldr(size, rold, fieldAddr);
            masm.cbz(size, rold, done);

            // Decode the object address: obj = heapBase + (value << shift).
            masm.mov(64, robj, rold);
            if (narrowShift != 0) {
                masm.lsl(64, robj, robj, narrowShift);
            }
            masm.add(64, robj, robj, heapBase);

            // Collection-set fast test: index = obj >> region_size_shift; the biased map is indexed
            // directly by that value. A zero map byte means not in the collection set -> no healing.
            try (ScratchRegister sc = masm.getScratchRegister()) {
                Register ridx = sc.getRegister();
                masm.lsr(64, ridx, robj, ShenandoahConstants.logOfHeapRegionGrainBytes());
                masm.ldr(64, rtmp, masm.makeAddress(64, thread, ShenandoahConstants.csetMapAddressOffset()));
                masm.ldr(8, rtmp, AArch64Address.createRegisterOffsetAddress(8, rtmp, ridx, false));
                masm.cbz(32, rtmp, done);
            }

            // In the collection set. Read the object's mark word; if it is forwarded, the mark word
            // holds the (lock-bit-tagged) to-space forwardee. If not yet evacuated (or self-forwarded),
            // fall back to the runtime stub which evacuates/resolves.
            masm.ldr(64, rtmp, masm.makeAddress(64, robj, ShenandoahConstants.markOffset()));
            masm.tst(64, rtmp, ShenandoahConstants.MARK_FORWARDED_MASK);
            masm.branchConditionally(ConditionFlag.EQ, slowPath);
            masm.bic(64, rtmp, rtmp, ShenandoahConstants.MARK_FORWARDED_MASK); // rtmp = forwardee (full pointer)
            masm.cbz(64, rtmp, slowPath); // marked but null fwd -> stub

            // Re-encode the forwardee as a narrow reference: newNarrow = (forwardee - heapBase) >> shift.
            masm.sub(64, rtmp, rtmp, heapBase);
            if (narrowShift != 0) {
                masm.lsr(64, rtmp, rtmp, narrowShift);
            }

            // CAS-heal the field from the old (from-space) narrow value to the to-space narrow value.
            // LSE cas: rold is the comparand and receives the loaded value; rtmp is the new value;
            // addrReg holds the address. Best-effort: a losing CAS just means another thread already
            // updated the field.
            masm.cas(size, rold, rtmp, addrReg, false, false);
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
            masm.mov(64, asRegister(callArg), addrReg);
            // All direct calls assume that they are within +-128 MB.
            AArch64Call.directCall(crb, masm, ((SubstrateForeignCallLinkage) callTarget).getMethod(), null, null);
            masm.jmp(done);
        });
    }
}
