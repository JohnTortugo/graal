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
import static jdk.graal.compiler.lir.LIRInstruction.OperandFlag.ILLEGAL;
import static jdk.graal.compiler.lir.LIRInstruction.OperandFlag.REG;
import static jdk.vm.ci.code.ValueUtil.asRegister;

import com.oracle.svm.core.ReservedRegisters;
import com.oracle.svm.core.gc.shenandoah.ShenandoahConstants;
import com.oracle.svm.core.graal.meta.SubstrateForeignCallLinkage;

import jdk.graal.compiler.asm.Label;
import jdk.graal.compiler.asm.aarch64.AArch64Address;
import jdk.graal.compiler.asm.aarch64.AArch64Assembler.ConditionFlag;
import jdk.graal.compiler.asm.aarch64.AArch64Assembler.ShiftType;
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
import jdk.vm.ci.meta.Value;

/**
 * SubstrateVM AArch64 implementation of the Shenandoah SATB pre-write barrier.
 *
 * The barrier has an inlined fast-path that checks the per-thread {@code gc_state}: if concurrent
 * marking is not in progress, nothing needs to be done. When marking is active and the previous
 * field value is non-null, it enqueues that value into the thread's SATB mark queue buffer inline
 * (decrement the queue index, store the uncompressed oop into the buffer), mirroring HotSpot. Only
 * when the buffer is full does it fall through to an (uninterruptible, register-preserving) runtime
 * stub that flushes/refills the buffer and enqueues the value.
 *
 * This is the AArch64 counterpart of
 * {@link com.oracle.svm.core.graal.amd64.AMD64SubstrateShenandoahSATBBarrierOp}; the SVM-specific
 * semantics (heap-base-relative null, inline narrow-reference uncompress before the enqueue, and the
 * early skip when the thread register is not yet set up) are taken from there. The AArch64 assembly
 * shape follows the Graal HotSpot backend op
 * {@link jdk.graal.compiler.hotspot.aarch64.shenandoah.AArch64HotSpotShenandoahSATBBarrierOp}.
 */
@Opcode("SHENANDOAH_SATB_BARRIER")
public class AArch64SubstrateShenandoahSATBBarrierOp extends AArch64LIRInstruction {
    public static final LIRInstructionClass<AArch64SubstrateShenandoahSATBBarrierOp> TYPE = LIRInstructionClass.create(AArch64SubstrateShenandoahSATBBarrierOp.class);

    /** The address of the reference field that is about to be overwritten (held in a register). */
    @Alive({REG}) private AllocatableValue address;

    /** Scratch register: holds the gc_state byte and then the SATB queue index / slot address. */
    @Temp({REG}) private AllocatableValue tmp;

    /** Scratch register holding the loaded previous value (and, for narrow refs, its decoded oop). */
    @Temp({REG}) private AllocatableValue preval;

    /**
     * The pre-value to enqueue, when it is supplied by the caller: for a compare-and-swap / atomic
     * read-write it is the <em>expected</em> value being replaced, and for a reference-get it is the
     * loaded referent. It is already a full-width (uncompressed) oop. It is {@link Value#ILLEGAL} for
     * a plain store, in which case the op loads the current field value from {@link #address}.
     */
    @Alive({REG, ILLEGAL}) private AllocatableValue preValue;

    /**
     * The calling-convention register that the slow-path stub call passes its argument in. On
     * SubstrateVM the foreign-call argument is passed in a register (not a stack slot), so it is
     * declared {@link Temp} to keep the register allocator from placing any live operand (in
     * particular the {@link #address} base) in it.
     */
    @Temp({REG}) private AllocatableValue callArg;

    /** The runtime stub that enqueues the previous value (narrow or wide variant). */
    private final ForeignCallLinkage callTarget;

    /**
     * Whether the previous value must be loaded from the field as a full machine word (8 bytes)
     * rather than a 32-bit value. True for uncompressed (wide) references, and also for narrow
     * references that are not size-reduced (Graal CE: a narrow reference is an 8-byte
     * heap-base-relative offset). Only false for size-reduced 4-byte compressed references.
     */
    private final boolean loadWordSized;

    /** Whether the field holds a compressed (narrow) reference that must be uncompressed. */
    private final boolean narrow;

    /** Compression shift for narrow references (0 on Graal CE). */
    private final int narrowShift;

    /** If true, the previous value is known to be non-null, so no null-check is emitted. */
    private final boolean nonNull;

    public AArch64SubstrateShenandoahSATBBarrierOp(AllocatableValue address, AllocatableValue tmp, AllocatableValue preval, AllocatableValue preValue,
                    ForeignCallLinkage callTarget, boolean loadWordSized, boolean narrow, int narrowShift, boolean nonNull) {
        super(TYPE);
        this.address = address;
        this.tmp = tmp;
        this.preval = preval;
        this.preValue = preValue;
        this.callArg = (AllocatableValue) callTarget.getOutgoingCallingConvention().getArgument(0);
        this.callTarget = callTarget;
        this.loadWordSized = loadWordSized;
        this.narrow = narrow;
        this.narrowShift = narrowShift;
        this.nonNull = nonNull;
    }

    @Override
    public void emitCode(CompilationResultBuilder crb, AArch64MacroAssembler masm) {
        Register thread = ReservedRegisters.singleton().getThreadRegister();
        Register heapBase = ReservedRegisters.singleton().getHeapBaseRegister();
        Register storeAddress = asRegister(address);
        Register rtmp = asRegister(tmp);
        Register rpre = asRegister(preval);
        boolean valuePassed = !Value.ILLEGAL.equals(preValue);

        Label done = new Label();
        Label runtime = new Label();

        // Skip the barrier if the thread register is not yet set up. This happens for reference
        // accesses in the very early isolate-creation code that runs before the current
        // IsolateThread has been installed. No GC can be in progress at that point.
        masm.cbz(64, thread, done);

        // Fast path: skip the barrier unless concurrent marking is in progress for this thread.
        masm.ldr(8, rtmp, masm.makeAddress(8, thread, ShenandoahConstants.gcStateOffset()));
        masm.tst(64, rtmp, ShenandoahConstants.GC_STATE_MARKING);
        masm.branchConditionally(ConditionFlag.EQ, done);

        // Obtain the previous value to enqueue. When the caller supplied it (a CAS/atomic expected
        // value or a reference-get referent) it is already a full-width oop, so use it directly;
        // re-loading the field would be wrong for a contended CAS, whose field may hold a value other
        // than the one actually replaced. For a plain store, load the current field value.
        if (valuePassed) {
            masm.mov(64, rpre, asRegister(preValue));
        } else if (loadWordSized) {
            masm.ldr(64, rpre, AArch64Address.createImmediateAddress(64, IMMEDIATE_SIGNED_UNSCALED, storeAddress, 0));
        } else {
            masm.ldr(32, rpre, AArch64Address.createImmediateAddress(32, IMMEDIATE_SIGNED_UNSCALED, storeAddress, 0));
        }

        // Skip the enqueue if the previous value is null. A value-passed pre-value is a full-width
        // (uncompressed) reference, and on SubstrateVM with linear pointer compression the canonical
        // UNCOMPRESSED null is the heap base, not 0: a null CAS-expected value or a cleared
        // reference-get referent arrives here as heapBase. Compare against the heap-base register in
        // that case; otherwise (a narrow value loaded from the field) compressed null is plain 0.
        if (!nonNull) {
            if (valuePassed) {
                masm.cmp(64, rpre, heapBase);
                masm.branchConditionally(ConditionFlag.EQ, done);
                masm.cbz(64, rpre, done);
            } else {
                masm.cbz(loadWordSized ? 64 : 32, rpre, done);
            }
        }

        // Inline the SATB enqueue: write the previous value into the thread's SATB mark queue buffer
        // and only fall through to the runtime stub when the buffer is full. The SubstrateVM-specific
        // difference vs. the Graal HotSpot backend op is the inline uncompression of a narrow
        // (compressed) reference (an add of the heap-base register) before the store, because the SATB
        // buffer holds uncompressed oops. We can only produce that oop with a plain add when the
        // compression shift is zero (always the case on Graal CE); for a non-zero shift we always take
        // the stub instead, which decodes.
        boolean inlineEnqueue = !narrow || narrowShift == 0;
        if (inlineEnqueue) {
            AArch64Address indexAddr = masm.makeAddress(64, thread, ShenandoahConstants.satbIndexOffset());
            // rtmp := *index; if rtmp == 0 the buffer is full -> take the runtime stub.
            masm.ldr(64, rtmp, indexAddr);
            masm.cbz(64, rtmp, runtime);
            // rtmp := rtmp - wordSize; *index := rtmp
            masm.sub(64, rtmp, rtmp, 8);
            masm.str(64, rtmp, indexAddr);
            // rtmp := *buffer + rtmp  (address of the slot to write)
            try (ScratchRegister sc = masm.getScratchRegister()) {
                Register rbuf = sc.getRegister();
                masm.ldr(64, rbuf, masm.makeAddress(64, thread, ShenandoahConstants.satbBufferOffset()));
                masm.add(64, rtmp, rtmp, rbuf);
            }
            // The SATB buffer holds uncompressed oops. For a narrow (compressed, shift==0) reference
            // the decoded oop is heapBase + previousValue; for a wide reference it is the value
            // itself. Note the previous value is only decoded on this (non-overflow) path, so the
            // slow path below still sees the raw value.
            if (narrow) {
                masm.add(64, rpre, rpre, heapBase);
            }
            masm.str(64, rpre, AArch64Address.createImmediateAddress(64, IMMEDIATE_SIGNED_UNSCALED, rtmp, 0));
        } else {
            masm.jmp(runtime);
        }
        masm.bind(done);

        // Out-of-line slow path: buffer full (or narrow with a non-zero compression shift). Call the
        // SATB enqueue stub with the raw previous value; it flushes/refills the buffer and enqueues.
        // On SubstrateVM the argument is passed in a register (callArg), not a stack slot.
        crb.getLIR().addSlowPath(this, () -> {
            masm.bind(runtime);
            Register arg0 = asRegister(callArg);
            masm.mov(loadWordSized ? 64 : 32, arg0, rpre);
            // All direct calls assume that they are within +-128 MB.
            AArch64Call.directCall(crb, masm, ((SubstrateForeignCallLinkage) callTarget).getMethod(), null, null);
            masm.jmp(done);
        });
    }
}
