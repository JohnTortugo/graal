/*
 * Copyright (c) 2026, 2026, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.hosted.pgo;

import static jdk.graal.compiler.nodeinfo.NodeCycles.CYCLES_0;
import static jdk.graal.compiler.nodeinfo.NodeSize.SIZE_0;
import static jdk.graal.compiler.replacements.SnippetTemplate.DEFAULT_REPLACER;

import org.graalvm.word.LocationIdentity;

import com.oracle.svm.core.graal.snippets.SubstrateTemplates;
import com.oracle.svm.core.pgo.SplitHistogramRecorder;

import jdk.graal.compiler.api.replacements.Snippet;
import jdk.graal.compiler.core.common.spi.ForeignCallDescriptor;
import jdk.graal.compiler.core.common.type.StampFactory;
import jdk.graal.compiler.graph.NodeClass;
import jdk.graal.compiler.nodeinfo.InputType;
import jdk.graal.compiler.nodeinfo.NodeInfo;
import jdk.graal.compiler.nodes.FixedWithNextNode;
import jdk.graal.compiler.nodes.NamedLocationIdentity;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.graph.Node.ConstantNodeParameter;
import jdk.graal.compiler.graph.Node.NodeIntrinsic;
import jdk.graal.compiler.nodes.extended.ForeignCallNode;
import jdk.graal.compiler.nodes.memory.SingleMemoryKill;
import jdk.graal.compiler.nodes.spi.Lowerable;
import jdk.graal.compiler.nodes.spi.LoweringTool;
import jdk.graal.compiler.options.OptionValues;
import jdk.graal.compiler.phases.util.Providers;
import jdk.graal.compiler.replacements.SnippetTemplate.Arguments;
import jdk.graal.compiler.replacements.SnippetTemplate.SnippetInfo;
import jdk.graal.compiler.replacements.Snippets;

/** Records one invocation of the selected split method; zero-cost for inliner accounting. */
@NodeInfo(cycles = CYCLES_0, size = SIZE_0)
public final class SplitHistogramNode extends FixedWithNextNode implements Lowerable, SingleMemoryKill {
    static final NodeClass<SplitHistogramNode> TYPE = NodeClass.create(SplitHistogramNode.class);
    private static final LocationIdentity LOCATION = NamedLocationIdentity.mutable("PGOSplitHistogramCounters");

    @Input(InputType.Value) private ValueNode line;
    @Input(InputType.Value) private ValueNode delimiter;
    @Input(InputType.Value) private ValueNode result;

    public SplitHistogramNode(ValueNode line, ValueNode delimiter, ValueNode result) {
        super(TYPE, StampFactory.forVoid());
        this.line = line;
        this.delimiter = delimiter;
        this.result = result;
    }

    @Override
    public LocationIdentity getKilledLocationIdentity() {
        return LOCATION;
    }

    @Override
    public void lower(LoweringTool tool) {
        if (graph().getGuardsStage().areFrameStatesAtDeopts()) {
            tool.getReplacements().getSnippetTemplateCache(Templates.class).lower(this, tool);
        }
    }

    static final class HistogramSnippet implements Snippets {
        @Snippet
        static void record(Object line, int delimiter, Object result) {
            runtimeCall(SplitHistogramRecorder.RECORD, line, delimiter, result);
        }

        /** The recorder dereferences its arguments, so it must not be inlined into this snippet. */
        @NodeIntrinsic(value = ForeignCallNode.class)
        static native void runtimeCall(@ConstantNodeParameter ForeignCallDescriptor descriptor, Object line, int delimiter, Object result);
    }

    static final class Templates extends SubstrateTemplates {
        private final SnippetInfo record;

        Templates(OptionValues options, Providers providers) {
            super(options, providers);
            record = snippet(providers, HistogramSnippet.class, "record");
        }

        void lower(SplitHistogramNode node, LoweringTool tool) {
            StructuredGraph graph = node.graph();
            Arguments args = new Arguments(record, graph, tool.getLoweringStage());
            args.add("line", node.line);
            args.add("delimiter", node.delimiter);
            args.add("result", node.result);
            template(tool, node, args).instantiate(tool.getMetaAccess(), node, DEFAULT_REPLACER, args);
        }
    }
}
