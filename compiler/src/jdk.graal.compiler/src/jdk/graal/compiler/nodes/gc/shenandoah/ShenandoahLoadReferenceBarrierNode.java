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
package jdk.graal.compiler.nodes.gc.shenandoah;

import static jdk.graal.compiler.nodeinfo.NodeCycles.CYCLES_64;
import static jdk.graal.compiler.nodeinfo.NodeSize.SIZE_64;

import jdk.graal.compiler.graph.NodeClass;
import jdk.graal.compiler.lir.gen.LIRGeneratorTool;
import jdk.graal.compiler.lir.gen.ShenandoahBarrierSetLIRGeneratorTool;
import jdk.graal.compiler.nodeinfo.NodeInfo;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.memory.address.AddressNode;
import jdk.graal.compiler.nodes.spi.LIRLowerable;
import jdk.graal.compiler.nodes.spi.NodeLIRBuilderTool;

@NodeInfo(cycles = CYCLES_64, size = SIZE_64)
public class ShenandoahLoadReferenceBarrierNode extends ShenandoahBarrierNode implements LIRLowerable {
    public static final NodeClass<ShenandoahLoadReferenceBarrierNode> TYPE = NodeClass.create(ShenandoahLoadReferenceBarrierNode.class);
    private final boolean isNarrow;
    private final boolean isStrong;
    private final boolean isWeak;
    private final boolean isPhantom;

    public ShenandoahLoadReferenceBarrierNode(AddressNode address, ValueNode loadedObject, boolean isNarrow, boolean isStrong, boolean isWeak, boolean isPhantom) {
        super(TYPE, address, loadedObject);
        this.isNarrow = isNarrow;
        this.isStrong = isStrong;
        this.isWeak = isWeak;
        this.isPhantom = isPhantom;
    }

    @Override
    public void generate(NodeLIRBuilderTool generator) {
        LIRGeneratorTool lirGen = generator.getLIRGeneratorTool();
        ShenandoahBarrierSetLIRGeneratorTool shenandoahBarrierSet = (ShenandoahBarrierSetLIRGeneratorTool) generator.getLIRGeneratorTool().getWriteBarrierSet();

        shenandoahBarrierSet.emitLoadReferenceBarrier(lirGen,
                        lirGen.asAllocatable(generator.operand(address)),
                        lirGen.asAllocatable(generator.operand(value)),
                        isStrong, isNarrow, isWeak, isPhantom);
    }
}