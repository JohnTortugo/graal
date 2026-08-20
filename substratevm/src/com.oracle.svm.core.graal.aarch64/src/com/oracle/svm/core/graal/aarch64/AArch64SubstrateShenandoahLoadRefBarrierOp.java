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

import static jdk.graal.compiler.lir.LIRInstruction.OperandFlag.COMPOSITE;
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
import jdk.graal.compiler.lir.aarch64.AArch64AddressValue;
import jdk.graal.compiler.lir.aarch64.AArch64Call;
import jdk.graal.compiler.lir.aarch64.AArch64LIRInstruction;
import jdk.graal.compiler.lir.asm.CompilationResultBuilder;
import jdk.graal.compiler.nodes.gc.shenandoah.ShenandoahLoadRefBarrierNode.ReferenceStrength;

import jdk.vm.ci.code.Register;
import jdk.vm.ci.meta.AllocatableValue;

/**
 * SubstrateVM AArch64 implementation of the Shenandoah load-reference barrier.
 *
 * The AArch64 counterpart of {@link com.oracle.svm.core.graal.amd64.AMD64SubstrateShenandoahLoadRefBarrierOp};
 * the SVM semantics are taken from there and the AArch64 assembly shape follows the Graal HotSpot
 * backend op {@link jdk.graal.compiler.hotspot.aarch64.shenandoah.AArch64HotSpotShenandoahLoadRefBarrierOp}:
 * <ol>
 * <li>Inlined fast path: if the loaded reference is null, or the per-thread {@code gc_state} shows
 * a stable heap (no forwarded objects; for non-strong references additionally no weak-roots
 * processing), the reference is returned unchanged.</li>
 * <li>Out-of-line mid-path (strong references only): the collection-set fast test using the biased
 * per-region byte map (thread-local {@code csetMapAddress}, indexed by
 * {@code address >> regionSizeShift}).</li>
 * <li>Out-of-line slow path: call the (uninterruptible, register-preserving) runtime stub with the
 * reference and the address it was loaded from. The stub returns the canonical (to-space)
 * reference and self-heals the load location.</li>
 * </ol>
 */
@Opcode("SHENANDOAH_LRB")
public class AArch64SubstrateShenandoahLoadRefBarrierOp extends AArch64LIRInstruction {
    public static final LIRInstructionClass<AArch64SubstrateShenandoahLoadRefBarrierOp> TYPE = LIRInstructionClass.create(AArch64SubstrateShenandoahLoadRefBarrierOp.class);

    /** The canonicalized reference (output). */
    @Def({REG}) private AllocatableValue result;

    /** The reference that was just loaded (input). */
    @Use({REG}) private AllocatableValue object;

    /** The address of the memory location the reference was loaded from. */
    @Alive({COMPOSITE}) private AArch64AddressValue loadAddress;

    /** The runtime stub implementing the slow-path load-reference barrier. */
    private final ForeignCallLinkage callTarget;

    /**
     * Calling-convention registers for the stub call. On SubstrateVM the foreign-call arguments and
     * return are passed in registers (not stack slots); they are declared {@link Temp} so the
     * register allocator keeps other live operands out of them.
     */
    @Temp({REG}) private AllocatableValue callArg0;
    @Temp({REG}) private AllocatableValue callArg1;
    @Temp({REG}) private AllocatableValue callRet;

    private final ReferenceStrength strength;

    private final boolean notNull;

    public AArch64SubstrateShenandoahLoadRefBarrierOp(AllocatableValue result, AllocatableValue object, AArch64AddressValue loadAddress,
                    ForeignCallLinkage callTarget, ReferenceStrength strength, boolean notNull) {
        super(TYPE);
        this.result = result;
        this.object = object;
        this.loadAddress = loadAddress;
        this.callTarget = callTarget;
        this.callArg0 = (AllocatableValue) callTarget.getOutgoingCallingConvention().getArgument(0);
        this.callArg1 = (AllocatableValue) callTarget.getOutgoingCallingConvention().getArgument(1);
        this.callRet = (AllocatableValue) callTarget.getOutgoingCallingConvention().getReturn();
        this.strength = strength;
        this.notNull = notNull;
    }

    @Override
    public void emitCode(CompilationResultBuilder crb, AArch64MacroAssembler masm) {
        Register thread = ReservedRegisters.singleton().getThreadRegister();
        Register objReg = asRegister(object);
        Register resReg = asRegister(result);
        AArch64Address loadAddr = loadAddress.toAddress();

        Label done = new Label();
        Label csetCheck = new Label();
        Label slowPath = new Label();

        // Fast path: assume the heap is stable, result == object.
        masm.mov(64, resReg, objReg);

        if (!notNull) {
            masm.cbz(64, resReg, done);
        }

        // Skip the barrier if the thread register is not yet set up (very early isolate-creation
        // code, before the current IsolateThread has been installed). No objects are forwarded then.
        masm.cbz(64, thread, done);

        // Check for heap stability (any forwarded objects?). Strong references continue to the
        // out-of-line collection-set fast test; non-strong references go straight to the runtime
        // stub, because during the weak-roots phase the stub must return null for unreachable
        // referents, even ones outside the collection set.
        try (ScratchRegister sc = masm.getScratchRegister()) {
            Register rtmp = sc.getRegister();
            masm.ldr(8, rtmp, masm.makeAddress(8, thread, ShenandoahConstants.gcStateOffset()));
            if (strength == ReferenceStrength.STRONG) {
                masm.tst(64, rtmp, ShenandoahConstants.GC_STATE_HAS_FORWARDED);
                masm.branchConditionally(ConditionFlag.NE, csetCheck);
            } else {
                // Two tests because HAS_FORWARDED | WEAK_ROOTS is not representable as a single
                // immediate.
                masm.tst(64, rtmp, ShenandoahConstants.GC_STATE_HAS_FORWARDED);
                masm.branchConditionally(ConditionFlag.NE, slowPath);
                masm.tst(64, rtmp, ShenandoahConstants.GC_STATE_WEAK_ROOTS);
                masm.branchConditionally(ConditionFlag.NE, slowPath);
            }
        }
        masm.bind(done);

        // Out-of-line mid path (strong only): the collection-set fast test. Only references into
        // the collection set can be stale; everything else returns unchanged without a runtime
        // call. The biased map is indexed directly by (address >> regionSizeShift).
        if (strength == ReferenceStrength.STRONG) {
            crb.getLIR().addSlowPath(this, () -> {
                try (ScratchRegister sc1 = masm.getScratchRegister(); ScratchRegister sc2 = masm.getScratchRegister()) {
                    Register rmap = sc1.getRegister();
                    Register ridx = sc2.getRegister();
                    masm.bind(csetCheck);
                    // ridx := object >> regionSizeShift
                    masm.lsr(64, ridx, objReg, ShenandoahConstants.logOfHeapRegionGrainBytes());
                    // rmap := *csetMapAddress; load the biased map byte at rmap[ridx]
                    masm.ldr(64, rmap, masm.makeAddress(64, thread, ShenandoahConstants.csetMapAddressOffset()));
                    masm.ldr(8, ridx, AArch64Address.createRegisterOffsetAddress(8, rmap, ridx, false));
                    masm.cbnz(32, ridx, slowPath);
                    masm.jmp(done);
                }
            });
        }

        // Out-of-line slow path: call the runtime load-reference barrier with the loaded reference
        // and the address it was loaded from, so the runtime can self-heal the location. On
        // SubstrateVM the arguments and return are passed in registers.
        crb.getLIR().addSlowPath(this, () -> {
            masm.bind(slowPath);
            Register arg0 = asRegister(callArg0);
            Register arg1 = asRegister(callArg1);

            // arg1 := the address the reference was loaded from
            if (loadAddr.isBaseRegisterOnly()) {
                masm.mov(64, arg1, loadAddr.getBase());
            } else {
                masm.loadAddress(arg1, loadAddr);
            }
            // arg0 := the loaded reference
            masm.mov(64, arg0, objReg);

            // All direct calls assume that they are within +-128 MB.
            AArch64Call.directCall(crb, masm, ((SubstrateForeignCallLinkage) callTarget).getMethod(), null, null);
            // Retrieve the canonical reference from the stub's return register.
            masm.mov(64, resReg, asRegister(callRet));
            masm.jmp(done);
        });
    }
}
