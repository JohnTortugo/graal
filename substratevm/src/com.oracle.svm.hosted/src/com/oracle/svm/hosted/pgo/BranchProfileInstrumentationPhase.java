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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.graalvm.collections.EconomicMap;

import com.oracle.svm.core.pgo.BranchProfileCounter;
import com.oracle.svm.core.pgo.BranchProfileRecorder;
import com.oracle.svm.hosted.pgo.phases.PGOApplyProfilesPhase;
import com.oracle.svm.hosted.pgo.profiles.ConditionalProfileContextResolver;

import jdk.graal.compiler.graph.NodeSourcePosition;
import jdk.graal.compiler.nodes.AbstractBeginNode;
import jdk.graal.compiler.nodes.ControlSplitNode;
import jdk.graal.compiler.nodes.IfNode;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.phases.BasePhase;
import jdk.graal.compiler.phases.tiers.HighTierContext;

/** Instruments profile-relevant {@link IfNode}s, optionally under an explicit inlining context. */
final class BranchProfileInstrumentationPhase extends BasePhase<HighTierContext> {

    private static final AtomicLong INSTRUMENTED_BRANCHES = new AtomicLong();
    private static final AtomicLong SKIPPED_BRANCHES = new AtomicLong();

    private final NodeSourcePosition inliningContext;

    BranchProfileInstrumentationPhase(NodeSourcePosition inliningContext) {
        this.inliningContext = inliningContext;
    }

    @Override
    protected void run(StructuredGraph graph, HighTierContext context) {
        instrumentGraph(graph, inliningContext);
    }

    static void instrumentGraph(StructuredGraph graph, NodeSourcePosition explicitInliningContext) {
        if (graph.method() == null || isNativeImageRuntimeMethod(graph.method().getDeclaringClass().getName())) {
            return;
        }

        EconomicMap<NodeSourcePosition, List<ControlSplitNode>> conditionalGroups = ProfilingUtilities.relevantConditionalNodesFromGraph(graph);
        for (List<ControlSplitNode> group : conditionalGroups.getValues()) {
            for (ControlSplitNode candidate : group) {
                if (candidate instanceof IfNode conditional) {
                    instrument(graph, conditional, explicitInliningContext);
                }
            }
        }
    }

    private static void instrument(StructuredGraph graph, IfNode conditional, NodeSourcePosition explicitInliningContext) {
        NodeSourcePosition position = PGOApplyProfilesPhase.createPointContext(conditional.getNodeSourcePosition(), explicitInliningContext);
        AbstractBeginNode trueSuccessor = conditional.trueSuccessor();
        AbstractBeginNode falseSuccessor = conditional.falseSuccessor();
        NodeSourcePosition truePosition = trueSuccessor.getNodeSourcePosition();
        NodeSourcePosition falsePosition = falseSuccessor.getNodeSourcePosition();
        if (position == null || truePosition == null || falsePosition == null) {
            SKIPPED_BRANCHES.incrementAndGet();
            return;
        }

        List<String> descriptors = new ArrayList<>();
        List<Integer> bcis = new ArrayList<>();
        for (NodeSourcePosition frame = position; frame != null; frame = frame.getCaller()) {
            descriptors.add(ConditionalProfileContextResolver.methodDescriptor(frame.getMethod()));
            bcis.add(frame.getBCI());
        }
        BranchProfileCounter counter = BranchProfileRecorder.lookup(
                        descriptors.toArray(String[]::new), bcis.stream().mapToInt(Integer::intValue).toArray(),
                        truePosition.getBCI(), falsePosition.getBCI());
        insertCounter(graph, trueSuccessor, counter, true);
        insertCounter(graph, falseSuccessor, counter, false);
        INSTRUMENTED_BRANCHES.incrementAndGet();
    }

    private static boolean isNativeImageRuntimeMethod(String declaringClassName) {
        return declaringClassName.startsWith("Lcom/oracle/svm/") ||
                        declaringClassName.startsWith("Lorg/graalvm/nativeimage/") ||
                        declaringClassName.startsWith("Lorg/graalvm/word/");
    }

    private static void insertCounter(StructuredGraph graph, AbstractBeginNode successor, BranchProfileCounter counter, boolean trueSuccessor) {
        BranchProfileCounterNode counterNode = graph.add(new BranchProfileCounterNode(counter.getCounterIndex(), trueSuccessor));
        counterNode.setNodeSourcePosition(successor.getNodeSourcePosition());
        graph.addAfterFixed(successor, counterNode);
    }

    static long instrumentedBranches() {
        return INSTRUMENTED_BRANCHES.get();
    }

    static long skippedBranches() {
        return SKIPPED_BRANCHES.get();
    }
}
