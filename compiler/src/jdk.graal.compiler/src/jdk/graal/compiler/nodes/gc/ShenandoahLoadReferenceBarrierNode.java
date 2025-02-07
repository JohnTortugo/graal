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

import static jdk.graal.compiler.nodeinfo.NodeCycles.CYCLES_64;
import static jdk.graal.compiler.nodeinfo.NodeSize.SIZE_64;

import org.graalvm.word.LocationIdentity;

import jdk.graal.compiler.nodes.memory.address.AddressNode;
import jdk.graal.compiler.nodes.NodeView;
import jdk.graal.compiler.nodes.memory.ReadNode;
import jdk.graal.compiler.core.common.memory.BarrierType;
import jdk.graal.compiler.graph.NodeClass;
import jdk.graal.compiler.nodeinfo.InputType;
import jdk.graal.compiler.nodeinfo.NodeInfo;
import jdk.graal.compiler.nodes.FixedWithNextNode;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.memory.MemoryKill;
import jdk.graal.compiler.nodes.spi.Lowerable;
import jdk.graal.compiler.nodes.spi.LoweringTool;
import jdk.graal.compiler.nodes.type.NarrowOopStamp;

@NodeInfo(allowedUsageTypes = {InputType.Memory, InputType.Guard, InputType.Value, InputType.Anchor}, cycles = CYCLES_64, size = SIZE_64)
public class ShenandoahLoadReferenceBarrierNode extends FixedWithNextNode implements Lowerable, MemoryKill {
    public static final NodeClass<ShenandoahLoadReferenceBarrierNode> TYPE = NodeClass.create(ShenandoahLoadReferenceBarrierNode.class);

    @Input(InputType.Value) private ReadNode value;

    private boolean isNarrowReference = false;
    private boolean isPhantomReference = false;
    private boolean isWeakReference = false;
    private boolean isStrongReference = false;

    public ShenandoahLoadReferenceBarrierNode(ReadNode value) {
        super(TYPE, value.stamp(NodeView.DEFAULT));
        this.value = value;
        this.isNarrowReference = value.stamp(NodeView.DEFAULT) instanceof NarrowOopStamp;
        this.isStrongReference = value.getBarrierType() == BarrierType.FIELD;
        this.isWeakReference = value.getBarrierType() == BarrierType.WEAK_REFERS_TO;
        this.isPhantomReference = value.getBarrierType() == BarrierType.PHANTOM_REFERS_TO;
    }

    @Override
    public void lower(LoweringTool tool) {
        assert graph().getGuardsStage().areFrameStatesAtDeopts();
        tool.getLowerer().lower(this, tool);
    }

    /**
     * @return false because this node does not kills {@link LocationIdentity#INIT_LOCATION}.
     */
    @Override
    public boolean killsInit() {
        return false;
    }

    public AddressNode getAddress() {
        return value.getAddress();
    }

    public ValueNode getValue() {
        return value;
    }

    public boolean isNarrowReference() {
        return isNarrowReference;
    }

    public boolean isPhantomReference() {
        return isPhantomReference;
    }

    public boolean isWeakReference() {
        return isWeakReference;
    }

    public boolean isStrongReference() {
        return isStrongReference;
    }
}