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
import static jdk.graal.compiler.lir.LIRInstruction.OperandFlag.ILLEGAL;
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

import jdk.vm.ci.code.Register;
import jdk.vm.ci.meta.AllocatableValue;
import jdk.vm.ci.meta.Value;

/**
 * SubstrateVM AMD64 implementation of the Shenandoah SATB pre-write barrier.
 *
 * The barrier has an inlined fast-path that checks the per-thread {@code gc_state}: if concurrent
 * marking is not in progress, nothing needs to be done. When marking is active and the previous
 * field value is non-null, it enqueues that value into the thread's SATB mark queue buffer inline
 * (decrement the queue index, store the uncompressed oop into the buffer), mirroring HotSpot. Only
 * when the buffer is full does it fall through to an (uninterruptible, register-preserving) runtime
 * stub that flushes/refills the buffer and enqueues the value.
 * 
 * The code in this class is based on {@link jdk.graal.compiler.hotspot.amd64.shenandoah.AMD64HotSpotShenandoahSATBBarrierOp}.
 */
@Opcode("SHENANDOAH_SATB_BARRIER")
public class AMD64SubstrateShenandoahSATBBarrierOp extends AMD64LIRInstruction {
    public static final LIRInstructionClass<AMD64SubstrateShenandoahSATBBarrierOp> TYPE = LIRInstructionClass.create(AMD64SubstrateShenandoahSATBBarrierOp.class);

    /** The address of the reference field that is about to be overwritten. */
    @Alive({COMPOSITE}) private AMD64AddressValue address;

    /** Scratch register: holds the gc_state byte and then the SATB queue index / slot address. */
    @Temp({REG}) private AllocatableValue tmp;

    /** Scratch register holding the loaded previous value (and, for narrow refs, its decoded oop). */
    @Temp({REG}) private AllocatableValue preval;

    /** Scratch register holding the SATB queue buffer pointer. */
    @Temp({REG}) private AllocatableValue buf;

    /**
     * The pre-value to enqueue, when it is supplied by the caller: for a compare-and-swap / atomic
     * read-write it is the <em>expected</em> value being replaced, and for a reference-get it is the
     * loaded referent. It is already a full-width (uncompressed) oop. It is {@link Value#ILLEGAL} for
     * a plain store, in which case the op loads the current field value from {@link #address}. Using
     * the supplied value (rather than re-loading the field) is required for a contended CAS, whose
     * field may momentarily hold a value other than the one the CAS actually replaces.
     */
    @Alive({REG, ILLEGAL}) private AllocatableValue preValue;

    /**
     * The calling-convention register that the slow-path stub call passes its argument in. It is
     * declared {@link Temp} so the register allocator will not place any live operand (in
     * particular the {@link #address} base) in it; otherwise the slow-path move into this register
     * would clobber the field address that the subsequent store still needs.
     */
    @Temp({REG}) private AllocatableValue callArg;

    /** The runtime stub that enqueues the previous value (narrow or wide variant). */
    private final ForeignCallLinkage callTarget;

    /**
     * Whether the previous value must be loaded from the field and passed to the stub as a full
     * machine word (8 bytes) rather than a 32-bit value. True for uncompressed (wide) references,
     * and also for narrow references that are not size-reduced (Graal CE: a narrow reference is an
     * 8-byte heap-base-relative offset). Only false for size-reduced 4-byte compressed references.
     */
    private final boolean loadWordSized;

    /** Whether the field holds a compressed (narrow) reference that must be uncompressed. */
    private final boolean narrow;

    /** Compression shift for narrow references (0 on Graal CE). */
    private final int narrowShift;

    /** If true, the previous value is known to be non-null, so no null-check is emitted. */
    private final boolean nonNull;

    public AMD64SubstrateShenandoahSATBBarrierOp(AMD64AddressValue address, AllocatableValue tmp, AllocatableValue preval, AllocatableValue buf,
                    AllocatableValue preValue, ForeignCallLinkage callTarget, boolean loadWordSized, boolean narrow, int narrowShift, boolean nonNull) {
        super(TYPE);
        this.address = address;
        this.tmp = tmp;
        this.preval = preval;
        this.buf = buf;
        this.preValue = preValue;
        this.callArg = (AllocatableValue) callTarget.getOutgoingCallingConvention().getArgument(0);
        this.callTarget = callTarget;
        this.loadWordSized = loadWordSized;
        this.narrow = narrow;
        this.narrowShift = narrowShift;
        this.nonNull = nonNull;
    }

    @Override
    public void emitCode(CompilationResultBuilder crb, AMD64MacroAssembler masm) {
        Register thread = ReservedRegisters.singleton().getThreadRegister();
        Register rtmp = asRegister(tmp);
        Register rpre = asRegister(preval);
        Register rbuf = asRegister(buf);
        AMD64Address fieldAddr = address.toAddress(masm);

        Label done = new Label();
        Label runtime = new Label();

        // Skip the barrier if the thread register is not yet set up. This happens for reference
        // accesses in the very early isolate-creation code that runs before the current
        // IsolateThread has been installed. No GC can be in progress at that point.
        masm.testq(thread, thread);
        masm.jcc(ConditionFlag.Zero, done);

        // Fast path: skip the barrier unless concurrent marking is in progress for this thread.
        masm.movb(rtmp, new AMD64Address(thread, ShenandoahConstants.gcStateOffset()));
        masm.testlAndJcc(rtmp, ShenandoahConstants.GC_STATE_MARKING, ConditionFlag.Zero, done, false);

        // Obtain the previous value to enqueue. When the caller supplied it (a CAS/atomic expected
        // value or a reference-get referent) it is already a full-width oop, so use it directly;
        // re-loading the field would be wrong for a contended CAS, whose field may hold a value other
        // than the one actually replaced. For a plain store, load the current field value.
        if (!Value.ILLEGAL.equals(preValue)) {
            masm.movq(rpre, asRegister(preValue));
        } else if (loadWordSized) {
            masm.movq(rpre, fieldAddr);
        } else {
            masm.movl(rpre, fieldAddr);
        }

        // Skip the enqueue if the previous value is null. A value-passed pre-value is a full-width
        // (uncompressed) reference, and on SubstrateVM with linear pointer compression the canonical
        // UNCOMPRESSED null is the heap base, not 0 (SubstrateAMD64Backend.emitUncompress always adds
        // the heap base, and CompilationResultBuilder.uncompressedNullRegister is the heap-base
        // register): a null CAS-expected value or a cleared reference-get referent arrives here as
        // heapBase. Compare against the heap-base register in that case; otherwise (a narrow value
        // loaded from the field) compressed null is plain 0.
        if (!nonNull) {
            if (!Value.ILLEGAL.equals(preValue)) {
                masm.cmpqAndJcc(rpre, ReservedRegisters.singleton().getHeapBaseRegister(), ConditionFlag.Equal, done, false);
                masm.testqAndJcc(rpre, rpre, ConditionFlag.Zero, done, false);
            } else {
                masm.testAndJcc(loadWordSized ? OperandSize.QWORD : OperandSize.DWORD, rpre, rpre, ConditionFlag.Zero, done, false);
            }
        }

        // Inline the SATB enqueue: write the previous value into the thread's SATB mark queue buffer
        // and only fall through to the runtime stub when the buffer is full. This sequence is adapted
        // from the Graal compiler's own HotSpot AMD64 backend for this barrier (see
        // {@link jdk.graal.compiler.hotspot.amd64.shenandoah.AMD64HotSpotShenandoahSATBBarrierOp}), i.e. it
        // comes from Graal's HotSpot backend, not from HotSpot's C1/C2. The SubstrateVM-specific
        // difference is the inline uncompression of a narrow (compressed) reference (an add of the
        // heap-base register) before the store, because the SATB buffer holds uncompressed oops. We
        // can only produce that oop with a plain add when the compression shift is zero (always the
        // case on Graal CE); for a non-zero shift we always take the stub instead, which decodes.
        boolean inlineEnqueue = !narrow || narrowShift == 0;
        if (inlineEnqueue) {
            AMD64Address indexAddr = new AMD64Address(thread, ShenandoahConstants.satbIndexOffset());
            // rtmp := *index; if rtmp == 0 the buffer is full -> take the runtime stub.
            masm.movq(rtmp, indexAddr);
            masm.cmpq(rtmp, 0);
            masm.jcc(ConditionFlag.Equal, runtime);
            // rtmp := rtmp - wordSize; *index := rtmp
            masm.subq(rtmp, 8);
            masm.movq(indexAddr, rtmp);
            // rtmp := *buffer + rtmp  (address of the slot to write)
            masm.movq(rbuf, new AMD64Address(thread, ShenandoahConstants.satbBufferOffset()));
            masm.addq(rtmp, rbuf);
            // The SATB buffer holds uncompressed oops. For a narrow (compressed, shift==0) reference
            // the decoded oop is heapBase + previousValue; for a wide reference it is the value
            // itself. Note the previous value is only decoded on this (non-overflow) path, so the
            // slow path below still sees the raw value.
            if (narrow) {
                masm.addq(rpre, ReservedRegisters.singleton().getHeapBaseRegister());
            }
            masm.movq(new AMD64Address(rtmp), rpre);
        } else {
            masm.jmp(runtime);
        }
        masm.bind(done);

        // Out-of-line slow path: buffer full (or narrow with a non-zero compression shift). Call the
        // SATB enqueue stub with the raw previous value; it flushes/refills the buffer and enqueues.
        crb.getLIR().addSlowPath(this, () -> {
            masm.bind(runtime);
            Register arg0 = asRegister(callArg);
            if (loadWordSized) {
                masm.movq(arg0, rpre);
            } else {
                masm.movl(arg0, rpre);
            }
            AMD64Call.directCall(crb, masm, ((SubstrateForeignCallLinkage) callTarget).getMethod(), null, false, null);
            masm.jmp(done);
        });
    }
}
