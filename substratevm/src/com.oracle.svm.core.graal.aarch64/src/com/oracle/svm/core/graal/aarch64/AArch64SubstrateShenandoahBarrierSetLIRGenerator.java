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

import org.graalvm.nativeimage.ImageSingletons;

import com.oracle.svm.core.config.ConfigurationValues;
import com.oracle.svm.core.gc.shenandoah.graal.ShenandoahBarrierSupport;
import com.oracle.svm.core.snippets.SnippetRuntime.SubstrateForeignCallDescriptor;

import jdk.graal.compiler.asm.aarch64.AArch64Address;
import jdk.graal.compiler.core.aarch64.AArch64LIRGenerator;
import jdk.graal.compiler.core.aarch64.AArch64ReadBarrierSetLIRGenerator;
import jdk.graal.compiler.core.common.CompressEncoding;
import jdk.graal.compiler.core.common.LIRKind;
import jdk.graal.compiler.core.common.memory.BarrierType;
import jdk.graal.compiler.core.common.memory.MemoryExtendKind;
import jdk.graal.compiler.core.common.memory.MemoryOrderMode;
import jdk.graal.compiler.core.common.spi.ForeignCallLinkage;
import jdk.graal.compiler.debug.GraalError;
import jdk.graal.compiler.lir.LIRFrameState;
import jdk.graal.compiler.lir.Variable;
import jdk.graal.compiler.lir.aarch64.AArch64AddressValue;
import jdk.graal.compiler.lir.aarch64.AArch64AtomicMove.CompareAndSwapOp;
import jdk.graal.compiler.lir.gen.LIRGeneratorTool;
import jdk.graal.compiler.lir.gen.ShenandoahBarrierSetLIRGeneratorTool;
import jdk.graal.compiler.nodes.gc.shenandoah.ShenandoahLoadRefBarrierNode;
import jdk.vm.ci.aarch64.AArch64Kind;
import jdk.vm.ci.meta.AllocatableValue;
import jdk.vm.ci.meta.Value;

/**
 * AArch64 LIR generation for the Shenandoah barriers on SubstrateVM (Native Image / AOT).
 *
 * The shared {@link jdk.graal.compiler.nodes.gc.shenandoah.ShenandoahBarrierSet} inserts the
 * barrier nodes ({@link jdk.graal.compiler.nodes.gc.shenandoah.ShenandoahSATBBarrierNode},
 * {@link ShenandoahLoadRefBarrierNode}) into the graph; this class lowers them to LIR.
 *
 * This is the AArch64 counterpart of
 * {@link com.oracle.svm.core.graal.amd64.AMD64SubstrateShenandoahBarrierSetLIRGenerator}: each
 * barrier is lowered to an inlined fast-path that tests the per-thread {@code gc_state} and only
 * falls through to an (uninterruptible, leaf) foreign call into the Shenandoah C++ library on the
 * slow path. See {@link AArch64SubstrateShenandoahSATBBarrierOp},
 * {@link AArch64SubstrateShenandoahLoadRefBarrierOp} and {@link AArch64SubstrateShenandoahCASHealOp}.
 */
public class AArch64SubstrateShenandoahBarrierSetLIRGenerator implements ShenandoahBarrierSetLIRGeneratorTool, AArch64ReadBarrierSetLIRGenerator {

    @Override
    public Value emitLoadReferenceBarrier(LIRGeneratorTool tool, Value obj, Value address, ShenandoahLoadRefBarrierNode.ReferenceStrength strength, boolean narrow, boolean notNull) {
        SubstrateForeignCallDescriptor descriptor = switch (strength) {
            case STRONG -> ShenandoahBarrierSupport.LOAD_REFERENCE_BARRIER;
            case WEAK -> ShenandoahBarrierSupport.LOAD_REFERENCE_BARRIER_WEAK;
            case PHANTOM -> ShenandoahBarrierSupport.LOAD_REFERENCE_BARRIER_PHANTOM;
        };
        ForeignCallLinkage linkage = tool.getForeignCalls().lookupForeignCall(descriptor);
        Variable result = tool.newVariable(obj.getValueKind());
        AArch64AddressValue loadAddress = ((AArch64LIRGenerator) tool).asAddressValue(address, AArch64Address.ANY_SIZE);
        tool.getResult().getFrameMapBuilder().callsMethod(linkage.getOutgoingCallingConvention());
        tool.append(new AArch64SubstrateShenandoahLoadRefBarrierOp(result, tool.asAllocatable(obj), loadAddress, linkage, strength, notNull));
        return result;
    }

    @Override
    public void emitPreWriteBarrier(LIRGeneratorTool tool, Value address, AllocatableValue expectedObject, boolean narrow, boolean nonNull) {
        boolean valuePassed = !Value.ILLEGAL.equals(expectedObject);
        // A value-passed pre-value is already uncompressed, so it is enqueued as a wide reference.
        boolean useNarrow = narrow && !valuePassed;
        ForeignCallLinkage linkage = tool.getForeignCalls().lookupForeignCall(
                        useNarrow ? ShenandoahBarrierSupport.PRE_WRITE_BARRIER_NARROW : ShenandoahBarrierSupport.PRE_WRITE_BARRIER_WIDE);

        // The barrier op keeps the store address in a register (LSE-free enqueue), so materialize it.
        AllocatableValue addressValue = tool.newVariable(address.getValueKind());
        tool.emitMove(addressValue, address);

        AllocatableValue tmp = tool.newVariable(LIRKind.value(AArch64Kind.QWORD));
        AllocatableValue preval = tool.newVariable(LIRKind.value(AArch64Kind.QWORD));
        AllocatableValue preValue = valuePassed ? expectedObject : Value.ILLEGAL;
        // See AMD64SubstrateShenandoahBarrierSetLIRGenerator#emitPreWriteBarrier for the reasoning:
        // a narrow reference occupies 8 bytes on Graal CE (heap-base-relative offset, no size
        // reduction), so loading only 32 bits would truncate it for heaps > 4 GB.
        boolean loadWordSized = !useNarrow || ConfigurationValues.getObjectLayout().getReferenceSize() == 8;
        int narrowShift = ImageSingletons.lookup(CompressEncoding.class).getShift();
        tool.getResult().getFrameMapBuilder().callsMethod(linkage.getOutgoingCallingConvention());
        tool.append(new AArch64SubstrateShenandoahSATBBarrierOp(addressValue, tmp, preval, preValue, linkage, loadWordSized, useNarrow, narrowShift, nonNull));
    }

    @Override
    public void emitCardBarrier(LIRGeneratorTool lirTool, Value address) {
        /* Card barriers are only needed for generational Shenandoah, which is not yet supported. */
        throw GraalError.shouldNotReachHere("card barriers are not used on SubstrateVM Shenandoah");
    }

    @Override
    public Value emitAtomicReadAndWrite(LIRGeneratorTool tool, LIRKind readKind, Value address, Value newValue, BarrierType barrierType) {
        /*
         * getAndSet needs no location heal: the xchg unconditionally overwrites the field with
         * newValue (a to-space reference provided by the mutator), so it cannot leave a stale
         * from-space pointer in the field, and the load-reference barrier inserted as a separate node
         * canonicalizes the returned old value.
         */
        return tool.emitAtomicReadAndWrite(readKind, address, newValue, BarrierType.NONE);
    }

    @Override
    public void emitCompareAndSwapOp(LIRGeneratorTool tool, boolean isLogic, Value address, MemoryOrderMode memoryOrder, AArch64Kind memKind, Variable result,
                    AllocatableValue expectedValue, AllocatableValue newValue, BarrierType barrierType) {
        /*
         * For an oop CAS, first self-heal the field location so that a concurrent evacuation cannot
         * cause a false negative (which would otherwise leave a stale from-space pointer in the
         * field, later crashing concurrent marking). This mirrors the "fix up early" atomic barrier
         * model used by AMD64SubstrateShenandoahBarrierSetLIRGenerator. A primitive CAS (barrierType
         * == NONE) needs no barrier and is not routed here.
         */
        AllocatableValue addressReg = tool.asAllocatable(address);
        emitLocationHeal(tool, addressReg, memKind);
        ((AArch64LIRGenerator) tool).append(new CompareAndSwapOp(memKind, memoryOrder, isLogic, result, expectedValue, newValue, addressReg));
    }

    /**
     * Emit a self-healing load-reference barrier on the location {@code addressReg}: if the field
     * holds a from-space pointer, heal it to its to-space value before the following plain atomic.
     * See {@link AArch64SubstrateShenandoahCASHealOp} and {@code svm_gc_load_reference_barrier_heal}.
     */
    private static void emitLocationHeal(LIRGeneratorTool tool, AllocatableValue addressReg, AArch64Kind memKind) {
        ForeignCallLinkage healLinkage = tool.getForeignCalls().lookupForeignCall(ShenandoahBarrierSupport.LOAD_REFERENCE_BARRIER_HEAL);
        CompressEncoding oopEncoding = ImageSingletons.lookup(CompressEncoding.class);
        int narrowShift = oopEncoding.getShift();
        // The inline path needs a heap-base-relative encoding (always true for SubstrateVM Shenandoah)
        // to decode the field value to an object address.
        boolean canInlineCsetCheck = oopEncoding.hasBase();
        boolean loadWordSized = memKind == AArch64Kind.QWORD;
        AllocatableValue tmp = tool.newVariable(LIRKind.value(AArch64Kind.QWORD));
        AllocatableValue tmp2 = tool.newVariable(LIRKind.value(AArch64Kind.QWORD));
        AllocatableValue tmp3 = tool.newVariable(LIRKind.value(AArch64Kind.QWORD));
        tool.getResult().getFrameMapBuilder().callsMethod(healLinkage.getOutgoingCallingConvention());
        tool.append(new AArch64SubstrateShenandoahCASHealOp(addressReg, tmp, tmp2, tmp3, healLinkage, loadWordSized, narrowShift, canInlineCsetCheck));
    }

    @Override
    public Variable emitBarrieredLoad(LIRGeneratorTool tool, LIRKind kind, Value address, LIRFrameState state, MemoryOrderMode memoryOrder, BarrierType barrierType) {
        /* The load-reference barrier is inserted as a separate node, so emit a plain load here. */
        return tool.getArithmetic().emitLoad(kind, address, state, memoryOrder, MemoryExtendKind.DEFAULT);
    }
}
