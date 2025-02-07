/*
 * Copyright (c) 2025, Oracle and/or its affiliates. All rights reserved.
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
package jdk.graal.compiler.nodes.gc;

import static jdk.graal.compiler.nodes.NamedLocationIdentity.OFF_HEAP_LOCATION;
import org.graalvm.word.LocationIdentity;

import jdk.graal.compiler.core.common.memory.BarrierType;
import jdk.graal.compiler.core.common.type.AbstractObjectStamp;
import jdk.graal.compiler.core.common.type.Stamp;
import jdk.graal.compiler.debug.GraalError;
import jdk.graal.compiler.nodes.NodeView;
import jdk.graal.compiler.nodes.FixedWithNextNode;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.extended.ArrayRangeWrite;
import jdk.graal.compiler.nodes.extended.RawStoreNode;
import jdk.graal.compiler.nodes.java.AbstractCompareAndSwapNode;
import jdk.graal.compiler.nodes.type.StampTool;
import jdk.graal.compiler.nodes.java.LoweredAtomicReadAndWriteNode;
import jdk.graal.compiler.nodes.memory.FixedAccessNode;
import jdk.graal.compiler.nodes.memory.ReadNode;
import jdk.graal.compiler.nodes.memory.WriteNode;
import jdk.graal.compiler.nodes.memory.address.AddressNode;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.ResolvedJavaField;
import jdk.vm.ci.meta.ResolvedJavaType;

public class ShenandoahBarrierSet implements BarrierSet {
    // This is the ResolvedJavaType for Object[]
    private final ResolvedJavaType objectArrayType;

    // This is the ResolvedJavaType for Reference.referent
    private final ResolvedJavaField referentField;

    // This is current JVM SHenandoah configuration
    private final ShenandoahBarrierConfig config;

    public ShenandoahBarrierSet(ShenandoahBarrierConfig config, ResolvedJavaType objectArrayType, ResolvedJavaField referentField) {
        this.objectArrayType = objectArrayType;
        this.referentField = referentField;
        this.config = config;
        System.out.println("new ShenandoahBarrierSet) config: " + config + ", objectArrayType: " + objectArrayType + ", referentField: " + referentField);
    }

    @Override
    public void addBarriers(FixedAccessNode n) {
        System.out.println("addBarriers) Node: " + n + ", barrierType: " + n.getBarrierType());

        if (n.getBarrierType() == BarrierType.NONE) {
            return;
        }

        if (n instanceof ReadNode) {
            addReadNodeBarriers((ReadNode) n);
        }

        /**
         * else if (n instanceof WriteNode) { WriteNode write = (WriteNode) n;
         * addWriteBarriers(write, write.value(), null, true, write.getUsedAsNullCheck()); } else if
         * (n instanceof LoweredAtomicReadAndWriteNode) { LoweredAtomicReadAndWriteNode atomic =
         * (LoweredAtomicReadAndWriteNode) n; addWriteBarriers(atomic, atomic.getNewValue(), null,
         * true, atomic.getUsedAsNullCheck()); } else if (n instanceof AbstractCompareAndSwapNode) {
         * if (config.isUseCASBarrier()) { System.out.println("Possibly incomplete."); //
         * GraalError.unimplemented("nope"); AbstractCompareAndSwapNode cmpSwap =
         * (AbstractCompareAndSwapNode) n; addWriteBarriers(cmpSwap, cmpSwap.getNewValue(),
         * cmpSwap.getExpectedValue(), false, false); } } else if (n instanceof ArrayRangeWrite) {
         * System.out.println("Possibly incomplete."); // GraalError.unimplemented("nope");
         * addArrayRangeBarriers((ArrayRangeWrite) n); } else {
         * GraalError.guarantee(n.getBarrierType() == BarrierType.NONE, "missed a node that requires
         * a GC barrier: %s", n.getClass()); }
         */
    }

    private void addReadNodeBarriers(ReadNode node) {
        assert config.useLRB();
        assert node.getBarrierType() != BarrierType.UNKNOWN;
        assert node.getBarrierType() != BarrierType.NONE;

        Stamp stamp = node.getAccessStamp(NodeView.DEFAULT);
        if (!stamp.isObjectStamp()) {
            GraalError.guarantee(false, "not an object stamp.");
            return;
        }

        ValueNode base = node.getAddress().getBase();
        if (!base.stamp(NodeView.DEFAULT).isObjectStamp()) {
            GraalError.guarantee(false, "base not an object stamp.");
            return;
        }

        StructuredGraph graph = node.graph();
        if (node.getBarrierType() == BarrierType.REFERENCE_GET) {
            ShenandoahReferentFieldReadBarrierNode lrb = new ShenandoahReferentFieldReadBarrierNode(node.getAddress(), node);
            node.graph().addAfterFixed(node, graph.add(lrb));
        } else {
            ShenandoahLoadReferenceBarrierNode lrb = new ShenandoahLoadReferenceBarrierNode(node);
            node.graph().addAfterFixed(node, graph.add(lrb));
        }
    }

    private void addWriteBarriers(FixedAccessNode node, ValueNode writtenValue, ValueNode expectedValue, boolean doLoad, boolean nullCheck) {
        BarrierType barrierType = node.getBarrierType();
        switch (barrierType) {
            case NONE:
                // nothing to do
                break;
            case FIELD:
            case ARRAY:
            case UNKNOWN:
                if (writtenValue.stamp(NodeView.DEFAULT) instanceof AbstractObjectStamp) {
                    boolean init = node.getLocationIdentity().isInit();
                    if (!init) {
                        if (config.useSATB()) {
                            // The pre-write barrier does nothing if the value being
                            // read is null, so it can be explicitly skipped when
                            // this is an initializing store.
                            addPreWriteBarrier(node, node.getAddress(), expectedValue, doLoad, nullCheck);
                        }

                        addPosWriteBarrier(node, node.getAddress(), expectedValue, doLoad, nullCheck);
                    }
                }
                break;
            default:
                throw new GraalError("unexpected barrier type: " + barrierType);
        }
    }

    private static void addArrayRangeBarriers(ArrayRangeWrite write) {
        if (write.writesObjectArray()) {
            StructuredGraph graph = write.asNode().graph();
            if (!write.isInitialization()) {
                // The pre barrier does nothing if the value being read is null, so it can
                // be explicitly skipped when this is an initializing store.
                ShenandoahArrayRangePreWriteBarrier arrayRangePreWriteBarrier = graph.add(new ShenandoahArrayRangePreWriteBarrier(write.getAddress(), write.getLength(), write.getElementStride()));
                graph.addBeforeFixed(write.preBarrierInsertionPosition(), arrayRangePreWriteBarrier);
            }
        }
    }

    private static void addPreWriteBarrier(FixedAccessNode node, AddressNode address, ValueNode value, boolean doLoad, boolean nullCheck) {
        StructuredGraph graph = node.graph();
        ShenandoahPreWriteBarrier preBarrier = graph.add(new ShenandoahPreWriteBarrier(address, value, doLoad, nullCheck));
        preBarrier.setStateBefore(node.stateBefore());
        node.setUsedAsNullCheck(false);
        node.setStateBefore(null);

        // Adds 'preBarrier' before 'node'
        graph.addBeforeFixed(node, preBarrier);
    }

    private static void addPosWriteBarrier(FixedAccessNode node, AddressNode address, ValueNode value, boolean doLoad, boolean nullCheck) {
        StructuredGraph graph = node.graph();
        ShenandoahPosWriteBarrier posBarrier = graph.add(new ShenandoahPosWriteBarrier(address, value, doLoad, nullCheck));

        // Adds 'posBarrier' after 'node'
        node.graph().addAfterFixed(node, posBarrier);
    }

    @Override
    public boolean hasWriteBarrier() {
        return true;
    }

    @Override
    public boolean hasReadBarrier() {
        return true;
    }

    /**
     * The methods below this line are for detecting which type of barrier is needed for accessing
     * some data.
     */

    @Override
    public BarrierType fieldReadBarrierType(ResolvedJavaField field, JavaKind storageKind) {
        BarrierType type = BarrierType.NONE;

        if (field.getJavaKind() == JavaKind.Object) {
            // TODO: Need to handle the different "Weak" references types.
            // For now I'll consider all of them as "Strong" references.
            type = BarrierType.FIELD;
        }

        System.out.println("fieldReadBarrierType) field: " + field + ", storageKind: " + storageKind + ", barrierType: " + type);
        return type;
    }

    @Override
    public BarrierType fieldWriteBarrierType(ResolvedJavaField field, JavaKind storageKind) {
        return BarrierType.NONE;
        // BarrierType type = storageKind == JavaKind.Object ? BarrierType.FIELD : BarrierType.NONE;
        // System.out.println("fieldWriteBarrierType) field: " + field + ", storageKind: " +
        // storageKind + ", barrierType: " + type);
        // return type;
    }

    @Override
    public BarrierType readBarrierType(LocationIdentity location, ValueNode address, Stamp loadStamp) {
        BarrierType type = BarrierType.NONE;

        // if (location.equals(OFF_HEAP_LOCATION)) {
        // // Off heap locations are never expected to contain objects
        // assert !loadStamp.isObjectStamp() : location;
        // type = BarrierType.NONE;
        // } else if (loadStamp.isObjectStamp()) {
        // if (address.stamp(NodeView.DEFAULT).isObjectStamp()) {
        // // A read of an Object from an Object requires a barrier
        // type = BarrierType.READ;
        // } else if (address instanceof AddressNode) {
        // AddressNode addr = (AddressNode) address;
        // if (addr.getBase().stamp(NodeView.DEFAULT).isObjectStamp()) {
        // // A read of an Object from an Object requires a barrier
        // type = BarrierType.READ;
        // }
        // } else {
        // throw GraalError.shouldNotReachHere("Unexpected location type " + loadStamp);
        // }
        // }

        // System.out.println("readBarrierType) Location: " + location + ", address: " + address +
        // ", stamp: " + loadStamp + ", barrierType: " + type);
        return type;
    }

    @Override
    public BarrierType writeBarrierType(RawStoreNode store) {
        // "object" is the base object relative to where "value" will be stored.
        ValueNode object = store.object();
        ValueNode value = store.value();
        BarrierType type = BarrierType.NONE;

        // if (store.needsBarrier()) {
        // if (value.getStackKind() == JavaKind.Object && object.getStackKind() == JavaKind.Object)
        // {
        // ResolvedJavaType objType = StampTool.typeOrNull(object);
        // if (objType != null && objType.isArray()) {
        // type = BarrierType.ARRAY;
        // } else if (objType == null || objType.isAssignableFrom(objectArrayType)) {
        // type = BarrierType.UNKNOWN;
        // } else {
        // type = BarrierType.FIELD;
        // }
        // }
        // }

        // System.out.println("writeBarrierType) store: " + store + ", object: " + object + ",
        // value: " + value + ", barrierType: " + type);
        return type;
    }

    @Override
    public BarrierType arrayWriteBarrierType(JavaKind storageKind) {
        return BarrierType.NONE;
        // BarrierType type = storageKind == JavaKind.Object ? BarrierType.ARRAY : BarrierType.NONE;
        // System.out.println("arrayWriteBarrierType) storageKind: " + storageKind + ", barrierType:
        // " + type);
        // return type;
    }

    @Override
    public BarrierType readWriteBarrier(ValueNode object, ValueNode value) {
        BarrierType type = BarrierType.NONE;
        // if (value.getStackKind() == JavaKind.Object && object.getStackKind() == JavaKind.Object)
        // {
        // ResolvedJavaType objType = StampTool.typeOrNull(object);
        // if (objType != null && objType.isArray()) {
        // type = BarrierType.ARRAY;
        // } else if (objType == null || objType.isAssignableFrom(objectArrayType)) {
        // type = BarrierType.UNKNOWN;
        // } else {
        // type = BarrierType.FIELD;
        // }
        // }

        // System.out.println("readWriteBarrier) object: " + object + ", value: " + value + ",
        // barrierType: " + type);
        return type;
    }
}