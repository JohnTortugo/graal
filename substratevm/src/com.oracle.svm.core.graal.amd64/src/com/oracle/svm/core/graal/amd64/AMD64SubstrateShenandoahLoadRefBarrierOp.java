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
import jdk.graal.compiler.core.common.spi.ForeignCallLinkage;
import jdk.graal.compiler.lir.LIRInstructionClass;
import jdk.graal.compiler.lir.Opcode;
import jdk.graal.compiler.lir.amd64.AMD64AddressValue;
import jdk.graal.compiler.lir.amd64.AMD64Call;
import jdk.graal.compiler.lir.amd64.AMD64LIRInstruction;
import jdk.graal.compiler.lir.asm.CompilationResultBuilder;
import jdk.graal.compiler.nodes.gc.shenandoah.ShenandoahLoadRefBarrierNode.ReferenceStrength;

import jdk.vm.ci.code.Register;
import jdk.vm.ci.meta.AllocatableValue;

/**
 * SubstrateVM AMD64 implementation of the Shenandoah load-reference barrier.
 *
 * Based on the HotSpot backend op
 * {@link jdk.graal.compiler.hotspot.amd64.shenandoah.AMD64HotSpotShenandoahLoadRefBarrierOp}:
 * <ol>
 * <li>Inlined fast path: if the loaded reference is null, or the per-thread {@code gc_state} shows
 * a stable heap (no forwarded objects; for non-strong references additionally no weak-roots
 * processing), the reference is returned unchanged.</li>
 * <li>Out-of-line mid-path (strong references only): the collection-set fast test. Only references
 * into the collection set can be stale; the biased per-region byte map (thread-local
 * {@code csetMapAddress}, indexed by {@code address >> regionSizeShift}) filters everything else
 * without a runtime call. During a long update-refs phase most loaded references are NOT in the
 * collection set, so this test removes the dominant share of slow-path calls. Non-strong references
 * skip it: during the weak-roots window the stub must run even for non-collection-set referents (to
 * return null for unreachable ones).</li>
 * <li>Out-of-line slow path: call the (uninterruptible, register-preserving) runtime stub with the
 * reference and the address it was loaded from. The stub returns the canonical (to-space)
 * reference and self-heals the load location (CAS from the stale to the canonical value), so
 * subsequent loads of the same slot take the fast path.</li>
 * </ol>
 */
@Opcode("SHENANDOAH_LRB")
public class AMD64SubstrateShenandoahLoadRefBarrierOp extends AMD64LIRInstruction {
    public static final LIRInstructionClass<AMD64SubstrateShenandoahLoadRefBarrierOp> TYPE = LIRInstructionClass.create(AMD64SubstrateShenandoahLoadRefBarrierOp.class);

    /** The canonicalized reference (output). */
    @Def({REG}) private AllocatableValue result;

    /** The reference that was just loaded (input). */
    @Alive({REG}) private AllocatableValue object;

    /** The address of the memory location the reference was loaded from. */
    @Alive({COMPOSITE}) private AMD64AddressValue loadAddress;

    /** Scratch register holding the gc_state byte / the cset map base. */
    @Temp({REG}) private AllocatableValue tmp;

    /** Scratch register holding the region index for the cset fast test. */
    @Temp({REG}) private AllocatableValue tmp2;

    @Temp({REG}) private AllocatableValue callArg0;
    @Temp({REG}) private AllocatableValue callArg1;
    @Temp({REG}) private AllocatableValue callRet;

    /** The runtime stub implementing the slow-path load-reference barrier. */
    private final ForeignCallLinkage callTarget;

    private final ReferenceStrength strength;

    private final boolean notNull;

    public AMD64SubstrateShenandoahLoadRefBarrierOp(AllocatableValue result, AllocatableValue object, AMD64AddressValue loadAddress, AllocatableValue tmp, AllocatableValue tmp2,
                    ForeignCallLinkage callTarget, ReferenceStrength strength, boolean notNull) {
        super(TYPE);
        this.result = result;
        this.object = object;
        this.loadAddress = loadAddress;
        this.tmp = tmp;
        this.tmp2 = tmp2;
        this.callArg0 = (AllocatableValue) callTarget.getOutgoingCallingConvention().getArgument(0);
        this.callArg1 = (AllocatableValue) callTarget.getOutgoingCallingConvention().getArgument(1);
        this.callRet = (AllocatableValue) callTarget.getOutgoingCallingConvention().getReturn();
        this.callTarget = callTarget;
        this.strength = strength;
        this.notNull = notNull;
    }

    @Override
    public void emitCode(CompilationResultBuilder crb, AMD64MacroAssembler masm) {
        Register thread = ReservedRegisters.singleton().getThreadRegister();
        Register rtmp = asRegister(tmp);
        Register objReg = asRegister(object);
        Register resReg = asRegister(result);

        Register rtmp2 = asRegister(tmp2);
        AMD64Address loadAddr = loadAddress.toAddress(masm);

        Label done = new Label();
        Label csetCheck = new Label();
        Label slowPath = new Label();

        // Fast path: assume the heap is stable, result == object.
        masm.movq(resReg, objReg);

        if (!notNull) {
            masm.testAndJcc(OperandSize.QWORD, resReg, resReg, ConditionFlag.Zero, done, false);
        }

        // Skip the barrier if the thread register is not yet set up (very early isolate-creation
        // code, before the current IsolateThread has been installed). No objects are forwarded then.
        masm.testq(thread, thread);
        masm.jcc(ConditionFlag.Zero, done);

        // Check for heap stability (any forwarded objects?). Strong references continue to the
        // out-of-line collection-set fast test; non-strong references go straight to the runtime
        // stub, because during the weak-roots phase (WEAK_ROOTS gc_state bit; also reachable via a
        // short-cut cycle without evacuation) the stub must return null for unreachable referents,
        // even ones outside the collection set.
        masm.movb(rtmp, new AMD64Address(thread, ShenandoahConstants.gcStateOffset()));
        if (strength == ReferenceStrength.STRONG) {
            masm.testlAndJcc(rtmp, ShenandoahConstants.GC_STATE_HAS_FORWARDED, ConditionFlag.NotZero, csetCheck, false);
        } else {
            int mask = ShenandoahConstants.GC_STATE_HAS_FORWARDED | ShenandoahConstants.GC_STATE_WEAK_ROOTS;
            masm.testlAndJcc(rtmp, mask, ConditionFlag.NotZero, slowPath, false);
        }
        masm.bind(done);

        // Out-of-line mid path (strong only): the collection-set fast test. Only references into
        // the collection set can be stale; everything else returns unchanged without a runtime
        // call. The biased map is indexed directly by (address >> regionSizeShift), exactly like in
        // AMD64SubstrateShenandoahCASHealOp.
        if (strength == ReferenceStrength.STRONG) {
            crb.getLIR().addSlowPath(this, () -> {
                masm.bind(csetCheck);
                masm.movq(rtmp2, objReg);
                masm.shrq(rtmp2, ShenandoahConstants.logOfHeapRegionGrainBytes());
                masm.movq(rtmp, new AMD64Address(thread, ShenandoahConstants.csetMapAddressOffset()));
                masm.addq(rtmp2, rtmp);
                masm.cmpb(new AMD64Address(rtmp2), 0);
                masm.jcc(ConditionFlag.NotZero, slowPath);
                masm.jmp(done);
            });
        }

        // Out-of-line slow path: call the runtime load-reference barrier with the loaded reference
        // and the address it was loaded from, so the runtime can self-heal the location.
        crb.getLIR().addSlowPath(this, () -> {
            masm.bind(slowPath);
            masm.leaq(asRegister(callArg1), loadAddr);
            masm.movq(asRegister(callArg0), objReg);
            AMD64Call.directCall(crb, masm, ((SubstrateForeignCallLinkage) callTarget).getMethod(), null, false, null);
            masm.movq(resReg, asRegister(callRet));
            masm.jmp(done);
        });
    }
}
