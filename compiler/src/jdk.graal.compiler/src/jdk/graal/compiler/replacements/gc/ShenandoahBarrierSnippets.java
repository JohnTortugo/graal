package jdk.graal.compiler.replacements.gc;

import org.graalvm.word.UnsignedWord;
import static jdk.graal.compiler.nodes.PiNode.piCastToSnippetReplaceeStamp;
import static jdk.graal.compiler.nodes.extended.BranchProbabilityNode.FREQUENT_PROBABILITY;
import static jdk.graal.compiler.nodes.extended.BranchProbabilityNode.NOT_FREQUENT_PROBABILITY;
import static jdk.graal.compiler.nodes.extended.BranchProbabilityNode.probability;

import org.graalvm.word.LocationIdentity;
import org.graalvm.word.Pointer;

import jdk.graal.compiler.nodes.memory.address.AddressNode.Address;

import jdk.graal.compiler.api.replacements.Snippet;
import jdk.graal.compiler.core.common.GraalOptions;
import jdk.graal.compiler.core.common.memory.BarrierType;
import jdk.graal.compiler.core.common.spi.ForeignCallDescriptor;
import jdk.graal.compiler.graph.Node;
import jdk.graal.compiler.nodes.NamedLocationIdentity;
import jdk.graal.compiler.nodes.NodeView;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.extended.FixedValueAnchorNode;
import jdk.graal.compiler.nodes.extended.ForeignCallNode;
import jdk.graal.compiler.nodes.extended.NullCheckNode;
import jdk.graal.compiler.nodes.gc.ShenandoahArrayRangePreWriteBarrier;
import jdk.graal.compiler.nodes.gc.ShenandoahLoadReferenceBarrierNode;
import jdk.graal.compiler.nodes.gc.ShenandoahPosWriteBarrier;
import jdk.graal.compiler.nodes.gc.ShenandoahPreWriteBarrier;
import jdk.graal.compiler.nodes.gc.ShenandoahReferentFieldReadBarrierNode;
import jdk.graal.compiler.nodes.java.InstanceOfNode;
import jdk.graal.compiler.nodes.memory.address.AddressNode;
import jdk.graal.compiler.nodes.memory.address.OffsetAddressNode;
import jdk.graal.compiler.nodes.spi.LoweringTool;
import jdk.graal.compiler.nodes.type.NarrowOopStamp;
import jdk.graal.compiler.replacements.SnippetCounter;
import jdk.graal.compiler.replacements.SnippetTemplate;
import jdk.graal.compiler.replacements.Snippets;
import jdk.graal.compiler.replacements.nodes.AssertionNode;
import jdk.graal.compiler.replacements.nodes.CStringConstant;
import jdk.graal.compiler.word.Word;
import jdk.vm.ci.meta.ResolvedJavaType;

public abstract class ShenandoahBarrierSnippets extends WriteBarrierSnippets implements Snippets {

    public static final byte HAS_FORWORDED = 1 << 0;
    public static final byte WEAK_ROOTS = 1 << 4;

    public static final LocationIdentity SATB_QUEUE_MARKING_ACTIVE_LOCATION = NamedLocationIdentity.mutable("Shenandoah-GC-SATB-Marking-Active");
    public static final LocationIdentity SATB_QUEUE_BUFFER_LOCATION = NamedLocationIdentity.mutable("Shenandoah-GC-SATB-Queue-Buffer");
    public static final LocationIdentity SATB_QUEUE_LOG_LOCATION = NamedLocationIdentity.mutable("Shenandoah-GC-SATB-Queue-Log");
    public static final LocationIdentity SATB_QUEUE_INDEX_LOCATION = NamedLocationIdentity.mutable("Shenandoah-GC-SATB-Queue-Index");
    public static final LocationIdentity GC_STATE_LOCATION = NamedLocationIdentity.mutable("Shenandoah-GC-State");

    protected static final LocationIdentity[] KILLED_PRE_WRITE_BARRIER_STUB_LOCATIONS = new LocationIdentity[]{SATB_QUEUE_INDEX_LOCATION, SATB_QUEUE_BUFFER_LOCATION, SATB_QUEUE_LOG_LOCATION};

    public static class Counters {
        Counters(SnippetCounter.Group.Factory factory) {
            SnippetCounter.Group countersBarriers = factory.createSnippetCounterGroup("Shenandoah Barriers");
            shenandoahAttemptedPreWriteBarrierCounter = new SnippetCounter(countersBarriers, "shenandoahAttemptedPreWriteBarrier", "Number of attempted Shenandoah Pre Write Barriers");
            shenandoahEffectivePreWriteBarrierCounter = new SnippetCounter(countersBarriers, "shenandoahEffectivePreWriteBarrier", "Number of effective Shenandoah Pre Write Barriers");
            shenandoahExecutedPreWriteBarrierCounter = new SnippetCounter(countersBarriers, "shenandoahExecutedPreWriteBarrier", "Number of executed Shenandoah Pre Write Barriers");
        }

        final SnippetCounter shenandoahAttemptedPreWriteBarrierCounter;
        final SnippetCounter shenandoahEffectivePreWriteBarrierCounter;
        final SnippetCounter shenandoahExecutedPreWriteBarrierCounter;
    }

    @Snippet
    public void shenandoahPreWriteBarrier(AddressNode.Address address, Object object, Object expectedObject, @Snippet.ConstantParameter boolean doLoad, @Snippet.ConstantParameter boolean nullCheck,
                    @Snippet.ConstantParameter int traceStartCycle, @Snippet.ConstantParameter ShenandoahBarrierSnippets.Counters counters) {
        if (nullCheck) {
            NullCheckNode.nullCheck(address);
        }
        Word thread = getThread();
        verifyOop(object);
        Word field = Word.fromAddress(address);
        byte markingValue = thread.readByte(satbQueueMarkingOffset(), SATB_QUEUE_MARKING_ACTIVE_LOCATION);

        boolean trace = isTracingActive(traceStartCycle);
        int gcCycle = 0;
        if (trace) {
            Pointer gcTotalCollectionsAddress = Word.pointer(gcTotalCollectionsAddress());
            gcCycle = (int) gcTotalCollectionsAddress.readLong(0);
            log(trace, "[%d] Shenandoah-Pre Thread %p Object %p\n", gcCycle, thread.rawValue(), Word.objectToTrackedPointer(object).rawValue());
            log(trace, "[%d] Shenandoah-Pre Thread %p Expected Object %p\n", gcCycle, thread.rawValue(), Word.objectToTrackedPointer(expectedObject).rawValue());
            log(trace, "[%d] Shenandoah-Pre Thread %p Field %p\n", gcCycle, thread.rawValue(), field.rawValue());
            log(trace, "[%d] Shenandoah-Pre Thread %p Marking %d\n", gcCycle, thread.rawValue(), markingValue);
            log(trace, "[%d] Shenandoah-Pre Thread %p DoLoad %d\n", gcCycle, thread.rawValue(), doLoad ? 1L : 0L);
        }

        counters.shenandoahAttemptedPreWriteBarrierCounter.inc();
        // If the concurrent marker is enabled, the barrier is issued.
        if (probability(NOT_FREQUENT_PROBABILITY, markingValue != (byte) 0)) {
            // If the previous value has to be loaded (before the write), the load is issued.
            // The load is always issued except the cases of CAS and referent field.
            Object previousObject;
            if (doLoad) {
                previousObject = field.readObject(0, BarrierType.NONE, LocationIdentity.any());
                if (trace) {
                    log(trace, "[%d] G1-Pre Thread %p Previous Object %p\n ", gcCycle, thread.rawValue(), Word.objectToTrackedPointer(previousObject).rawValue());
                    verifyOop(previousObject);
                }
            } else {
                previousObject = FixedValueAnchorNode.getObject(expectedObject);
            }

            counters.shenandoahEffectivePreWriteBarrierCounter.inc();
            // If the previous value is null the barrier should not be issued.
            if (probability(FREQUENT_PROBABILITY, previousObject != null)) {
                counters.shenandoahExecutedPreWriteBarrierCounter.inc();
                // If the thread-local SATB buffer is full issue a native call which will
                // initialize a new one and add the entry.
                Word indexValue = thread.readWord(satbQueueIndexOffset(), SATB_QUEUE_INDEX_LOCATION);
                if (probability(FREQUENT_PROBABILITY, indexValue.notEqual(0))) {
                    Word bufferAddress = thread.readWord(satbQueueBufferOffset(), SATB_QUEUE_BUFFER_LOCATION);
                    Word nextIndex = indexValue.subtract(wordSize());

                    // Log the object to be marked as well as update the SATB's buffer next index.
                    bufferAddress.writeWord(nextIndex, Word.objectToTrackedPointer(previousObject), SATB_QUEUE_LOG_LOCATION);
                    thread.writeWord(satbQueueIndexOffset(), nextIndex, SATB_QUEUE_INDEX_LOCATION);
                } else {
                    shenandoahPreBarrierStub(previousObject);
                }
            }
        }
    }

    @Snippet
    public void shenandoahPosWriteBarrier(AddressNode.Address address, Object object, Object expectedObject, @Snippet.ConstantParameter boolean doLoad, @Snippet.ConstantParameter boolean nullCheck,
                    @Snippet.ConstantParameter int traceStartCycle, @Snippet.ConstantParameter ShenandoahBarrierSnippets.Counters counters) {
        //@formatter:off
        //if (nullCheck) {
        //    NullCheckNode.nullCheck(address);
        //}
        //Word thread = getThread();
        //verifyOop(object);
        //Word field = Word.fromAddress(address);
        //byte markingValue = thread.readByte(satbQueueMarkingOffset(), SATB_QUEUE_MARKING_ACTIVE_LOCATION);

        //boolean trace = isTracingActive(traceStartCycle);
        //int gcCycle = 0;
        //if (trace) {
        //    Pointer gcTotalCollectionsAddress = Word.pointer(gcTotalCollectionsAddress());
        //    gcCycle = (int) gcTotalCollectionsAddress.readLong(0);
        //    log(trace, "[%d] Shenandoah-Pre Thread %p Object %p\n", gcCycle, thread.rawValue(), Word.objectToTrackedPointer(object).rawValue());
        //    log(trace, "[%d] Shenandoah-Pre Thread %p Expected Object %p\n", gcCycle, thread.rawValue(), Word.objectToTrackedPointer(expectedObject).rawValue());
        //    log(trace, "[%d] Shenandoah-Pre Thread %p Field %p\n", gcCycle, thread.rawValue(), field.rawValue());
        //    log(trace, "[%d] Shenandoah-Pre Thread %p Marking %d\n", gcCycle, thread.rawValue(), markingValue);
        //    log(trace, "[%d] Shenandoah-Pre Thread %p DoLoad %d\n", gcCycle, thread.rawValue(), doLoad ? 1L : 0L);
        //}

        //counters.shenandoahAttemptedPreWriteBarrierCounter.inc();
        //// If the concurrent marker is enabled, the barrier is issued.
        //if (probability(NOT_FREQUENT_PROBABILITY, markingValue != (byte) 0)) {
        //    // If the previous value has to be loaded (before the write), the load is issued.
        //    // The load is always issued except the cases of CAS and referent field.
        //    Object previousObject;
        //    if (doLoad) {
        //        previousObject = field.readObject(0, BarrierType.NONE, LocationIdentity.any());
        //        if (trace) {
        //            log(trace, "[%d] G1-Pre Thread %p Previous Object %p\n ", gcCycle, thread.rawValue(), Word.objectToTrackedPointer(previousObject).rawValue());
        //            verifyOop(previousObject);
        //        }
        //    } else {
        //        previousObject = FixedValueAnchorNode.getObject(expectedObject);
        //    }

        //    counters.shenandoahEffectivePreWriteBarrierCounter.inc();
        //    // If the previous value is null the barrier should not be issued.
        //    if (probability(FREQUENT_PROBABILITY, previousObject != null)) {
        //        counters.shenandoahExecutedPreWriteBarrierCounter.inc();
        //        // If the thread-local SATB buffer is full issue a native call which will
        //        // initialize a new one and add the entry.
        //        Word indexValue = thread.readWord(satbQueueIndexOffset(), SATB_QUEUE_INDEX_LOCATION);
        //        if (probability(FREQUENT_PROBABILITY, indexValue.notEqual(0))) {
        //            Word bufferAddress = thread.readWord(satbQueueBufferOffset(), SATB_QUEUE_BUFFER_LOCATION);
        //            Word nextIndex = indexValue.subtract(wordSize());

        //            // Log the object to be marked as well as update the SATB's buffer next index.
        //            bufferAddress.writeWord(nextIndex, Word.objectToTrackedPointer(previousObject), SATB_QUEUE_LOG_LOCATION);
        //            thread.writeWord(satbQueueIndexOffset(), nextIndex, SATB_QUEUE_INDEX_LOCATION);
        //        } else {
        //            shenandoahPreBarrierStub(previousObject);
        //        }
        //    }
        //}
        //@formatter:on
    }

    @Snippet
    public void shenandoahReferentReadBarrier(AddressNode.Address address, Object object, Object expectedObject, @Snippet.ConstantParameter boolean isDynamicCheck, Word offset,
                    @Snippet.ConstantParameter int traceStartCycle, @Snippet.ConstantParameter ShenandoahBarrierSnippets.Counters counters) {
        if (!isDynamicCheck ||
                        (offset == Word.unsigned(referentOffset()) && InstanceOfNode.doInstanceof(referenceType(), object))) {
            shenandoahPreWriteBarrier(address, object, expectedObject, false, false, traceStartCycle, counters);
        }
    }

    @Snippet
    public void shenandoahArrayRangePreWriteBarrier(AddressNode.Address address, int length, @Snippet.ConstantParameter int elementStride) {
        System.out.println("Possibly incomplete.");
        Word thread = getThread();
        byte markingValue = thread.readByte(satbQueueMarkingOffset(), SATB_QUEUE_MARKING_ACTIVE_LOCATION);
        // If the concurrent marker is not enabled or the vector length is zero, return.
        if (probability(FREQUENT_PROBABILITY, markingValue == (byte) 0 || length == 0)) {
            return;
        }

        Word bufferAddress = thread.readWord(satbQueueBufferOffset(), SATB_QUEUE_BUFFER_LOCATION);
        Word indexAddress = thread.add(satbQueueIndexOffset());
        long indexValue = indexAddress.readWord(0, SATB_QUEUE_INDEX_LOCATION).rawValue();
        int scale = objectArrayIndexScale();
        Word start = getPointerToFirstArrayElement(address, length, elementStride);

        for (int i = 0; i < length; i++) {
            Word arrElemPtr = start.add(i * scale);
            Object previousObject = arrElemPtr.readObject(0, BarrierType.NONE);
            verifyOop(previousObject);
            if (probability(FREQUENT_PROBABILITY, previousObject != null)) {
                if (probability(FREQUENT_PROBABILITY, indexValue != 0)) {
                    indexValue = indexValue - wordSize();
                    Word logAddress = bufferAddress.add(Word.unsigned(indexValue));
                    // Log the object to be marked as well as update the SATB's buffer next index.
                    Word previousOop = Word.objectToTrackedPointer(previousObject);
                    logAddress.writeWord(0, previousOop, SATB_QUEUE_LOG_LOCATION);
                    indexAddress.writeWord(0, Word.unsigned(indexValue), SATB_QUEUE_INDEX_LOCATION);
                } else {
                    shenandoahPreBarrierStub(previousObject);
                }
            }
        }
    }

    /**
     * bla
     *
     * @param address
     * @param value
     * @param isNarrowReference
     * @param isStrongReference
     * @param isWeakReference
     * @param isPhantomReference
     * @return blx
     */
    @Snippet
    public Object shenandoahLoadReferenceBarrier(Address address, Object value, boolean isNarrowReference,
                    boolean isStrongReference, boolean isWeakReference, boolean isPhantomReference) {
        verifyOop(value);

        Word thread = getThread();
        byte gcStateValue = thread.readByte(gcStateOffset(), GC_STATE_LOCATION);
        boolean forwarded_is_zero = (gcStateValue & HAS_FORWORDED) == 0;
        boolean weak_roots_is_zero = (gcStateValue & WEAK_ROOTS) == 0;

        if (isStrongReference) {
            if (forwarded_is_zero) {
                return value;
            }
        } else {
            if (weak_roots_is_zero) {
                if (forwarded_is_zero) {
                    return value;
                }
            }
        }

        if (isStrongReference) {
            Pointer in_cset_fast_test_addr = Word.pointer(gcCSetFastTestAddr());
            Pointer loadedReference = Word.objectToTrackedPointer(value);
            UnsignedWord word = loadedReference.unsignedShiftRight(gcRegionSizeBytesShift());

            byte val_to_cmp = in_cset_fast_test_addr.readByte(word);
            if (val_to_cmp == 0) { // not in cset
                return value;
            }
        }

        // TODO: I think this can be improved but I don't know how.
        // I expected that we should just "consume" the "value" object here instead of having to
        // reload it.
        Word field = Word.fromAddress(address);
        Object loadedValue = field.readObject(0, BarrierType.NONE, LocationIdentity.any());
        if (isNarrowReference) {
            return piCastToSnippetReplaceeStamp(shenandoahNarrowStrongLoadReferenceBarrierStub(loadedValue, Word.fromAddress(address)));
        } else {
            return piCastToSnippetReplaceeStamp(shenandoahStrongLoadReferenceBarrierStub(loadedValue, Word.fromAddress(address)));
        }
    }

    protected abstract Word getThread();

    protected abstract int wordSize();

    protected abstract int objectArrayIndexScale();

    protected abstract int satbQueueMarkingOffset();

    protected abstract int satbQueueBufferOffset();

    protected abstract int satbQueueIndexOffset();

    protected abstract int gcStateOffset();

    protected abstract int gcRegionSizeBytesShift();

    protected abstract long gcCSetFastTestAddr();

    protected abstract ForeignCallDescriptor preWriteBarrierCallDescriptor();

    protected abstract ForeignCallDescriptor narrowStrongLoadReferenceBarrierCallDescriptor();

    protected abstract ForeignCallDescriptor strongLoadReferenceBarrierCallDescriptor();

    // the data below is only needed for the verification logic
    protected abstract boolean verifyOops();

    protected abstract boolean verifyBarrier();

    protected abstract long gcTotalCollectionsAddress();

    protected abstract ForeignCallDescriptor verifyOopCallDescriptor();

    protected abstract ForeignCallDescriptor validateObjectCallDescriptor();

    protected abstract ForeignCallDescriptor printfCallDescriptor();

    protected abstract ResolvedJavaType referenceType();

    protected abstract long referentOffset();

    private boolean isTracingActive(int traceStartCycle) {
        return traceStartCycle > 0 && ((Pointer) Word.pointer(gcTotalCollectionsAddress())).readLong(0) > traceStartCycle;
    }

    private void log(boolean enabled, String format, long value1, long value2, long value3) {
        if (enabled) {
            printf(printfCallDescriptor(), CStringConstant.cstring(format), value1, value2, value3);
        }
    }

    private void verifyOop(Object object) {
        if (verifyOops()) {
            verifyOopStub(verifyOopCallDescriptor(), object);
        }
    }

    private void shenandoahPreBarrierStub(Object previousObject) {
        shenandoahPreBarrierStub(preWriteBarrierCallDescriptor(), previousObject);
    }

    private Object shenandoahNarrowStrongLoadReferenceBarrierStub(Object src, Word load_addr) {
        return shenandoahNarrowStrongLoadReferenceBarrierStub(narrowStrongLoadReferenceBarrierCallDescriptor(), src, load_addr);
    }

    private Object shenandoahStrongLoadReferenceBarrierStub(Object src, Word load_addr) {
        return shenandoahStrongLoadReferenceBarrierStub(strongLoadReferenceBarrierCallDescriptor(), src, load_addr);
    }

    @Node.NodeIntrinsic(ForeignCallNode.class)
    private static native Object verifyOopStub(@Node.ConstantNodeParameter ForeignCallDescriptor descriptor, Object object);

    @Node.NodeIntrinsic(ForeignCallNode.class)
    private static native boolean validateOop(@Node.ConstantNodeParameter ForeignCallDescriptor descriptor, Word parent, Word object);

    @Node.NodeIntrinsic(ForeignCallNode.class)
    private static native void shenandoahPreBarrierStub(@Node.ConstantNodeParameter ForeignCallDescriptor descriptor, Object object);

    @Node.NodeIntrinsic(ForeignCallNode.class)
    private static native Object shenandoahNarrowStrongLoadReferenceBarrierStub(@Node.ConstantNodeParameter ForeignCallDescriptor descriptor, Object src, Word load_addr);

    @Node.NodeIntrinsic(ForeignCallNode.class)
    private static native Object shenandoahStrongLoadReferenceBarrierStub(@Node.ConstantNodeParameter ForeignCallDescriptor descriptor, Object src, Word load_addr);

    @Node.NodeIntrinsic(ForeignCallNode.class)
    private static native void printf(@Node.ConstantNodeParameter ForeignCallDescriptor logPrintf, Word format, long v1, long v2, long v3);

    public abstract static class ShenandoahBarrierLowerer {
        private final ShenandoahBarrierSnippets.Counters counters;

        public ShenandoahBarrierLowerer(SnippetCounter.Group.Factory factory) {
            this.counters = new ShenandoahBarrierSnippets.Counters(factory);
        }

        public void lower(SnippetTemplate.AbstractTemplates templates, SnippetTemplate.SnippetInfo snippet, ShenandoahPreWriteBarrier barrier, LoweringTool tool) {
            SnippetTemplate.Arguments args = new SnippetTemplate.Arguments(snippet, barrier.graph().getGuardsStage(), tool.getLoweringStage());
            AddressNode address = barrier.getAddress();
            args.add("address", address);
            if (address instanceof OffsetAddressNode) {
                args.add("object", ((OffsetAddressNode) address).getBase());
            } else {
                args.add("object", null);
            }

            ValueNode expected = barrier.getExpectedObject();
            if (expected != null && expected.stamp(NodeView.DEFAULT) instanceof NarrowOopStamp) {
                expected = uncompress(expected);
            }
            args.add("expectedObject", expected);

            args.add("doLoad", barrier.doLoad());
            args.add("nullCheck", barrier.getNullCheck());
            args.add("traceStartCycle", traceStartCycle(barrier.graph()));
            args.add("counters", counters);

            templates.template(tool, barrier, args).instantiate(tool.getMetaAccess(), barrier, SnippetTemplate.DEFAULT_REPLACER, args);
        }

        public void lower(SnippetTemplate.AbstractTemplates templates, SnippetTemplate.SnippetInfo snippet, ShenandoahPosWriteBarrier barrier, LoweringTool tool) {
            SnippetTemplate.Arguments args = new SnippetTemplate.Arguments(snippet, barrier.graph().getGuardsStage(), tool.getLoweringStage());
            AddressNode address = barrier.getAddress();
            args.add("address", address);
            if (address instanceof OffsetAddressNode) {
                args.add("object", ((OffsetAddressNode) address).getBase());
            } else {
                args.add("object", null);
            }

            ValueNode expected = barrier.getExpectedObject();
            if (expected != null && expected.stamp(NodeView.DEFAULT) instanceof NarrowOopStamp) {
                expected = uncompress(expected);
            }
            args.add("expectedObject", expected);

            args.add("doLoad", barrier.doLoad());
            args.add("nullCheck", barrier.getNullCheck());
            args.add("traceStartCycle", traceStartCycle(barrier.graph()));
            args.add("counters", counters);

            templates.template(tool, barrier, args).instantiate(tool.getMetaAccess(), barrier, SnippetTemplate.DEFAULT_REPLACER, args);
        }

        public void lower(SnippetTemplate.AbstractTemplates templates, SnippetTemplate.SnippetInfo snippet, ShenandoahReferentFieldReadBarrierNode barrier, LoweringTool tool) {
            SnippetTemplate.Arguments args = new SnippetTemplate.Arguments(snippet, barrier.graph().getGuardsStage(), tool.getLoweringStage());
            // This is expected to be lowered before address lowering
            OffsetAddressNode address = (OffsetAddressNode) barrier.getAddress();
            args.add("address", address);
            args.add("object", address.getBase());

            ValueNode expected = barrier.getExpectedObject();
            if (expected != null && expected.stamp(NodeView.DEFAULT) instanceof NarrowOopStamp) {
                expected = uncompress(expected);
            }

            args.add("expectedObject", expected);
            args.add("isDynamicCheck", false);
            args.add("offset", address.getOffset());
            args.add("traceStartCycle", traceStartCycle(barrier.graph()));
            args.add("counters", counters);

            templates.template(tool, barrier, args).instantiate(tool.getMetaAccess(), barrier, SnippetTemplate.DEFAULT_REPLACER, args);
        }

        public void lower(SnippetTemplate.AbstractTemplates templates, SnippetTemplate.SnippetInfo snippet, ShenandoahArrayRangePreWriteBarrier barrier, LoweringTool tool) {
            SnippetTemplate.Arguments args = new SnippetTemplate.Arguments(snippet, barrier.graph().getGuardsStage(), tool.getLoweringStage());
            args.add("address", barrier.getAddress());
            args.add("length", barrier.getLength());
            args.add("elementStride", barrier.getElementStride());

            templates.template(tool, barrier, args).instantiate(tool.getMetaAccess(), barrier, SnippetTemplate.DEFAULT_REPLACER, args);
        }

        public void lower(SnippetTemplate.AbstractTemplates templates, SnippetTemplate.SnippetInfo snippet, ShenandoahLoadReferenceBarrierNode barrier, LoweringTool tool) {
            SnippetTemplate.Arguments args = new SnippetTemplate.Arguments(snippet, barrier.graph().getGuardsStage(), tool.getLoweringStage());
            args.add("address", barrier.getAddress());
            args.add("value", barrier.getValue());
            args.add("isNarrowReference", barrier.isNarrowReference());
            args.add("isStrongReference", barrier.isStrongReference());
            args.add("isWeakReference", barrier.isWeakReference());
            args.add("isPhantomReference", barrier.isPhantomReference());
            templates.template(tool, barrier, args).instantiate(tool.getMetaAccess(), barrier, SnippetTemplate.DEFAULT_REPLACER, args);
        }

        private static int traceStartCycle(StructuredGraph graph) {
            return GraalOptions.GCDebugStartCycle.getValue(graph.getOptions());
        }

        protected abstract ValueNode uncompress(ValueNode value);
    }
}