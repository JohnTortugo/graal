/*
 * Copyright (c) 2024, Oracle and/or its affiliates. All rights reserved.
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
package jdk.graal.compiler.lir.aarch64.shenandoah;

import static jdk.graal.compiler.asm.Assembler.guaranteeDifferentRegisters;
import static jdk.graal.compiler.lir.LIRInstruction.OperandFlag.REG;
import static jdk.vm.ci.code.ValueUtil.asRegister;

import jdk.graal.compiler.asm.Label;
import jdk.graal.compiler.asm.aarch64.AArch64Address;
import jdk.graal.compiler.asm.aarch64.AArch64MacroAssembler;
import jdk.graal.compiler.core.common.spi.ForeignCallLinkage;
import jdk.graal.compiler.debug.GraalError;
import jdk.graal.compiler.lir.LIRInstructionClass;
import jdk.graal.compiler.lir.aarch64.AArch64Call;
import jdk.graal.compiler.lir.aarch64.AArch64LIRInstruction;
import jdk.graal.compiler.lir.asm.CompilationResultBuilder;
import jdk.vm.ci.code.CallingConvention;
import jdk.vm.ci.code.Register;
import jdk.vm.ci.meta.Value;

/**
 * AArch46 Shenandoah Load Reference Barrier code emission. Platform specific code generation is
 * performed by {@link AArch64ShenandoahBarrierSetLIRTool}.
 */
public class AArch64ShenandoahLoadReferenceBarrierOp extends AArch64LIRInstruction {
    public static final LIRInstructionClass<AArch64ShenandoahLoadReferenceBarrierOp> TYPE = LIRInstructionClass.create(AArch64ShenandoahLoadReferenceBarrierOp.class);

    @Alive(REG) private Value fieldAddress;
    @Alive(REG) private Value loadedObject;

    private final ForeignCallLinkage callTarget;
    private final AArch64ShenandoahBarrierSetLIRTool tool;
    private final boolean isStrong;

    enum GCStateBitPos {
        // Heap has forwarded objects: needs LRB barriers.
        HAS_FORWARDED_BITPOS(0),

        // Heap is under marking: needs SATB barriers.
        // For generational mode, it means either young or old marking, or both.
        MARKING_BITPOS(1),

        // Heap is under evacuation: needs LRB barriers. (Set together with HAS_FORWARDED)
        EVACUATION_BITPOS(2),

        // Heap is under updating: needs no additional barriers.
        UPDATE_REFS_BITPOS(3),

        // Heap is under weak-reference/roots processing: needs weak-LRB barriers.
        WEAK_ROOTS_BITPOS(4),

        // Young regions are under marking, need SATB barriers.
        YOUNG_MARKING_BITPOS(5),

        // Old regions are under marking, need SATB barriers.
        OLD_MARKING_BITPOS(6);

        private final int value;

        GCStateBitPos(int val) {
            this.value = val;
        }

        public int getValue() {
            return this.value;
        }
    }

    enum GCState {
        STABLE(0),
        HAS_FORWARDED(1 << GCStateBitPos.HAS_FORWARDED_BITPOS.getValue()),
        MARKING(1 << GCStateBitPos.MARKING_BITPOS.getValue()),
        EVACUATION(1 << GCStateBitPos.EVACUATION_BITPOS.getValue()),
        UPDATE_REFS(1 << GCStateBitPos.UPDATE_REFS_BITPOS.getValue()),
        WEAK_ROOTS(1 << GCStateBitPos.WEAK_ROOTS_BITPOS.getValue()),
        YOUNG_MARKING(1 << GCStateBitPos.YOUNG_MARKING_BITPOS.getValue()),
        OLD_MARKING(1 << GCStateBitPos.OLD_MARKING_BITPOS.getValue());

        private final int value;

        GCState(int val) {
            this.value = val;
        }

        public int getValue() {
            return this.value;
        }
    }

    public AArch64ShenandoahLoadReferenceBarrierOp(Value address, Value loadedObject, boolean isStrong, ForeignCallLinkage callTarget, AArch64ShenandoahBarrierSetLIRTool tool) {
        super(TYPE);
        this.fieldAddress = address;
        this.loadedObject = loadedObject;
        this.tool = tool;
        this.isStrong = isStrong;
        this.callTarget = callTarget;
        GraalError.guarantee(loadedObject.equals(Value.ILLEGAL) || loadedObject.getPlatformKind().getSizeInBytes() == 8, "expected uncompressed pointer");
    }

    @Override
    public void emitCode(CompilationResultBuilder crb, AArch64MacroAssembler masm) {
        try (AArch64MacroAssembler.ScratchRegister sc1 = masm.getScratchRegister(); AArch64MacroAssembler.ScratchRegister sc2 = masm.getScratchRegister()) {
            Register rscratch1 = sc1.getRegister();
            Register rscratch2 = sc2.getRegister();
            Register thread = tool.getThread(masm);
            Register fieldAddressInRegister = asRegister(fieldAddress);
            Register objLoadedInRegister = asRegister(loadedObject);
            guaranteeDifferentRegisters(rscratch1, rscratch2, thread, fieldAddressInRegister, objLoadedInRegister);

            Label heapIsStable = new Label();
            Label notInCSet = new Label();

            // Check for heap stability
            AArch64Address gcState = masm.makeAddress(8, thread, tool.gcStateOffset());
            masm.ldr(8, rscratch2, gcState);

            if (isStrong) {
                masm.tbz(rscratch2, GCStateBitPos.HAS_FORWARDED_BITPOS.getValue(), heapIsStable);

                // Test for in-cset
                if (isStrong) {
                    masm.mov(rscratch2, tool.csetFastTestAddr());
                    masm.lsr(64, rscratch1, objLoadedInRegister, tool.regionSizeBytesShift());
                    masm.ldr(8, rscratch2, AArch64Address.createRegisterOffsetAddress(8, rscratch2, rscratch1, false));
                    masm.tbz(rscratch2, 0, notInCSet);
                }
            } else {
                Label lrb = new Label();
                masm.tbnz(rscratch2, GCStateBitPos.WEAK_ROOTS_BITPOS.getValue(), lrb);
                masm.tbz(rscratch2, GCStateBitPos.HAS_FORWARDED_BITPOS.getValue(), heapIsStable);
                masm.bind(lrb);
            }

            // Make the call to LRB barrier
            CallingConvention cc = callTarget.getOutgoingCallingConvention();
            assert cc.getArgumentCount() == 2 : "Expecting callTarget to have only 2 parameters. It has " + cc.getArgumentCount();

            // Store first argument
            AArch64Address cArg0 = (AArch64Address) crb.asAddress(cc.getArgument(0));
            masm.str(64, objLoadedInRegister, cArg0);

            // Store second argument
            AArch64Address cArg1 = (AArch64Address) crb.asAddress(cc.getArgument(1));
            masm.str(64, fieldAddressInRegister, cArg1);

            // Make the call
            AArch64Call.directCall(crb, masm, callTarget, AArch64Call.isNearCall(callTarget) ? null : rscratch1, null);

            // Retrieve result and move to same register that our input was in
            AArch64Address cRet = (AArch64Address) crb.asAddress(cc.getReturn());
            masm.ldr(64, objLoadedInRegister, cRet);

            masm.bind(notInCSet);
            masm.bind(heapIsStable);
        }
    }
}
