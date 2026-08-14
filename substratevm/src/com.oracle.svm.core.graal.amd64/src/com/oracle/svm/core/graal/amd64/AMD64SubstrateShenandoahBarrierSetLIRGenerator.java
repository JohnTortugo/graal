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

import org.graalvm.nativeimage.ImageSingletons;

import com.oracle.svm.core.config.ConfigurationValues;
import com.oracle.svm.core.gc.shenandoah.graal.ShenandoahBarrierSupport;
import com.oracle.svm.core.snippets.SnippetRuntime.SubstrateForeignCallDescriptor;

import jdk.graal.compiler.core.amd64.AMD64LIRGenerator;
import jdk.graal.compiler.core.amd64.AMD64ReadBarrierSetLIRGenerator;
import jdk.graal.compiler.core.common.CompressEncoding;
import jdk.graal.compiler.core.common.LIRKind;
import jdk.graal.compiler.core.common.memory.BarrierType;
import jdk.graal.compiler.core.common.memory.MemoryExtendKind;
import jdk.graal.compiler.core.common.memory.MemoryOrderMode;
import jdk.graal.compiler.core.common.spi.ForeignCallLinkage;
import jdk.graal.compiler.debug.GraalError;
import jdk.graal.compiler.lir.LIRFrameState;
import jdk.graal.compiler.lir.Variable;
import jdk.graal.compiler.lir.amd64.AMD64AddressValue;
import jdk.graal.compiler.lir.amd64.AMD64Move.CompareAndSwapOp;
import jdk.graal.compiler.lir.gen.LIRGeneratorTool;
import jdk.graal.compiler.lir.gen.ShenandoahBarrierSetLIRGeneratorTool;
import jdk.graal.compiler.nodes.gc.shenandoah.ShenandoahLoadRefBarrierNode;
import jdk.vm.ci.amd64.AMD64Kind;
import jdk.vm.ci.code.RegisterValue;
import jdk.vm.ci.meta.AllocatableValue;
import jdk.vm.ci.meta.Value;

/**
 * AMD64 LIR generation for the Shenandoah barriers on SubstrateVM (Native Image / AOT).
 *
 * The shared {@link jdk.graal.compiler.nodes.gc.shenandoah.ShenandoahBarrierSet} inserts the
 * barrier nodes ({@link jdk.graal.compiler.nodes.gc.shenandoah.ShenandoahSATBBarrierNode},
 * {@link ShenandoahLoadRefBarrierNode}) into the graph. This class lowers them to LIR.
 *
 * Like in the HotSpot backend
 * {@link jdk.graal.compiler.hotspot.amd64.shenandoah.AMD64HotSpotShenandoahBarrierSetLIRGenerator},
 * which this class is derived from, each barrier is lowered to an inlined fast-path that tests
 * the per-thread {@code gc_state} (the SATB pre-write barrier checks the marking bit; the
 * load-reference barrier checks the has-forwarded bit, plus the weak-roots bit for non-strong
 * references) and only falls through to an (uninterruptible, leaf) foreign call into the
 * Shenandoah C++ library on the slow path. See {@link AMD64SubstrateShenandoahSATBBarrierOp} and
 * {@link AMD64SubstrateShenandoahLoadRefBarrierOp}.
 */
public class AMD64SubstrateShenandoahBarrierSetLIRGenerator implements ShenandoahBarrierSetLIRGeneratorTool, AMD64ReadBarrierSetLIRGenerator {

    @Override
    public Value emitLoadReferenceBarrier(LIRGeneratorTool tool, Value obj, Value address, ShenandoahLoadRefBarrierNode.ReferenceStrength strength, boolean narrow, boolean notNull) {
        /*
         * Select the runtime stub by reference strength: the WEAK/PHANTOM variants additionally
         * return null for an unreachable (unmarked) referent during the concurrent weak-roots phase,
         * preventing a mutator from resurrecting a dead, never-evacuated collection-set object (the
         * inline fast path already takes the slow path in that window via the WEAK_ROOTS gc_state
         * bit; see AMD64SubstrateShenandoahLoadRefBarrierOp).
         */
        SubstrateForeignCallDescriptor descriptor = switch (strength) {
            case STRONG -> ShenandoahBarrierSupport.LOAD_REFERENCE_BARRIER;
            case WEAK -> ShenandoahBarrierSupport.LOAD_REFERENCE_BARRIER_WEAK;
            case PHANTOM -> ShenandoahBarrierSupport.LOAD_REFERENCE_BARRIER_PHANTOM;
        };
        ForeignCallLinkage linkage = tool.getForeignCalls().lookupForeignCall(descriptor);
        Variable result = tool.newVariable(obj.getValueKind());
        AMD64AddressValue loadAddress = ((AMD64LIRGenerator) tool).asAddressValue(address);
        AllocatableValue tmp = tool.newVariable(LIRKind.value(AMD64Kind.QWORD));
        AllocatableValue tmp2 = tool.newVariable(LIRKind.value(AMD64Kind.QWORD));
        tool.getResult().getFrameMapBuilder().callsMethod(linkage.getOutgoingCallingConvention());
        tool.append(new AMD64SubstrateShenandoahLoadRefBarrierOp(result, tool.asAllocatable(obj), loadAddress, tmp, tmp2, linkage, strength, notNull));
        return result;
    }

    @Override
    public void emitPreWriteBarrier(LIRGeneratorTool tool, Value address, AllocatableValue expectedObject, boolean narrow, boolean nonNull) {
        /*
         * If a pre-value is provided (for compare-and-swap / atomic read-write that's the expected
         * value being replaced, for reference-get it is the loaded referent) we must enqueue exactly
         * that value.
         * The compiler already uncompressed it, so it is a full-width oop. We must not re-load the
         * current field value in that case: for a contended CAS the field may hold a different value
         * than the one the CAS actually replaces, so re-loading would enqueue the wrong reference and
         * miss the replaced one. Only for a plain store (no pre-value, expectedObject == ILLEGAL) do
         * we load the current (pre-write) field value inside the barrier op.
         */
        boolean valuePassed = !Value.ILLEGAL.equals(expectedObject);
        // A value-passed pre-value is already uncompressed, so it is enqueued as a wide reference.
        boolean useNarrow = narrow && !valuePassed;
        ForeignCallLinkage linkage = tool.getForeignCalls().lookupForeignCall(
                        useNarrow ? ShenandoahBarrierSupport.PRE_WRITE_BARRIER_NARROW : ShenandoahBarrierSupport.PRE_WRITE_BARRIER_WIDE);
        AMD64AddressValue addr = ((AMD64LIRGenerator) tool).asAddressValue(address);
        AllocatableValue tmp = tool.newVariable(LIRKind.value(AMD64Kind.QWORD));
        AllocatableValue preval = tool.newVariable(LIRKind.value(AMD64Kind.QWORD));
        AllocatableValue buf = tool.newVariable(LIRKind.value(AMD64Kind.QWORD));
        AllocatableValue preValue = valuePassed ? expectedObject : Value.ILLEGAL;
        // A wide (uncompressed) reference is always a full machine word. A narrow (compressed)
        // reference occupies 'referenceSize' bytes: 8 with isolates but without size-reducing
        // compression (Graal CE, where a narrow reference is an 8-byte heap-base-relative offset),
        // or 4 with size-reducing compressed references. Loading only 32 bits of an 8-byte narrow
        // reference would truncate the offset and corrupt the SATB pre-value for heaps > 4 GB.
        // A value-passed pre-value is always a full word.
        boolean loadWordSized = !useNarrow || ConfigurationValues.getObjectLayout().getReferenceSize() == 8;
        // Compression shift, so the barrier can decode a narrow reference (oop = heapBase + value <<
        // shift) inline when enqueuing it into the SATB buffer. It is 0 on Graal CE.
        int narrowShift = ImageSingletons.lookup(CompressEncoding.class).getShift();
        tool.getResult().getFrameMapBuilder().callsMethod(linkage.getOutgoingCallingConvention());
        tool.append(new AMD64SubstrateShenandoahSATBBarrierOp(addr, tmp, preval, buf, preValue, linkage, loadWordSized, useNarrow, narrowShift, nonNull));
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
         * canonicalizes the returned old value. (Unlike compare-and-swap, there is no from-space
         * false-negative to avoid here - see AMD64SubstrateShenandoahCASHealOp / emitCompareAndSwapOp.)
         */
        return tool.emitAtomicReadAndWrite(readKind, address, newValue, BarrierType.NONE);
    }

    @Override
    public void emitCompareAndSwapOp(LIRGeneratorTool tool, boolean isLogic, LIRKind accessKind, AMD64Kind memKind, RegisterValue raxValue, AMD64AddressValue address, AllocatableValue newValue,
                    BarrierType barrierType) {
        /*
         * For an oop CAS, first self-heal the field location so that a concurrent evacuation cannot
         * cause a false negative: without this, a plain CAS whose field momentarily holds a from-space
         * pointer would spuriously fail and leave that stale from-space pointer in the field (later
         * crashing concurrent marking). This mirrors HotSpot's "fix up early" atomic barrier model
         * (JDK-8384080 / JDK-8383810). A primitive CAS (barrierType == NONE) needs no barrier.
         */
        if (barrierType != BarrierType.NONE) {
            /*
             * The heal op clobbers rax (its inline CAS-heal uses cmpxchg). Preserve the CAS expected
             * value (which lives in rax) across it.
             */
            Variable savedExpected = tool.newVariable(raxValue.getValueKind());
            tool.emitMove(savedExpected, raxValue);
            emitLocationHeal(tool, address);
            tool.emitMove(raxValue, savedExpected);
        }
        ((AMD64LIRGenerator) tool).append(new CompareAndSwapOp(memKind, raxValue, address, raxValue, newValue));
    }

    /**
     * Emit a self-healing load-reference barrier on the location {@code address}: if the field holds a
     * from-space pointer, heal it to its to-space value before the following plain atomic. See
     * {@link AMD64SubstrateShenandoahCASHealOp} and {@code svm_gc_load_reference_barrier_heal}.
     */
    private static void emitLocationHeal(LIRGeneratorTool tool, AMD64AddressValue address) {
        ForeignCallLinkage healLinkage = tool.getForeignCalls().lookupForeignCall(ShenandoahBarrierSupport.LOAD_REFERENCE_BARRIER_HEAL);
        CompressEncoding oopEncoding = ImageSingletons.lookup(CompressEncoding.class);
        int narrowShift = oopEncoding.getShift();
        // The inline path needs a heap-base-relative encoding (always true for SubstrateVM Shenandoah)
        // to decode the field value to an object address.
        boolean canInlineCsetCheck = oopEncoding.hasBase();
        boolean loadWordSized = ConfigurationValues.getObjectLayout().getReferenceSize() == 8;
        AllocatableValue tmp = tool.newVariable(LIRKind.value(AMD64Kind.QWORD));
        AllocatableValue tmp2 = tool.newVariable(LIRKind.value(AMD64Kind.QWORD));
        AllocatableValue tmp3 = tool.newVariable(LIRKind.value(AMD64Kind.QWORD));
        AllocatableValue tmp4 = tool.newVariable(LIRKind.value(AMD64Kind.QWORD));
        tool.getResult().getFrameMapBuilder().callsMethod(healLinkage.getOutgoingCallingConvention());
        tool.append(new AMD64SubstrateShenandoahCASHealOp(address, tmp, tmp2, tmp3, tmp4, healLinkage, loadWordSized, narrowShift, canInlineCsetCheck));
    }

    @Override
    public Variable emitBarrieredLoad(LIRGeneratorTool tool, LIRKind kind, Value address, LIRFrameState state, MemoryOrderMode memoryOrder, BarrierType barrierType) {
        /* The load-reference barrier is inserted as a separate node, so emit a plain load here. */
        return tool.getArithmetic().emitLoad(kind, address, state, memoryOrder, MemoryExtendKind.DEFAULT);
    }
}
