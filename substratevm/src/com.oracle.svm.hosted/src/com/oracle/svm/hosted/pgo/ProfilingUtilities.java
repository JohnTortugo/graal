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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.graalvm.collections.EconomicMap;

import com.oracle.svm.hosted.pgo.profiles.ConditionalProfileContextResolver;
import com.oracle.svm.hosted.pgo.profiles.ConditionalProfileSiteDescriptor;
import com.oracle.svm.hosted.pgo.profiles.ConditionalProfileSiteDescriptor.Stage;

import jdk.graal.compiler.graph.Node;
import jdk.graal.compiler.graph.NodeSourcePosition;
import jdk.graal.compiler.graph.iterators.NodeIterable;
import jdk.graal.compiler.nodes.AbstractBeginNode;
import jdk.graal.compiler.nodes.ControlSplitNode;
import jdk.graal.compiler.nodes.IfNode;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.extended.BytecodeExceptionNode;
import jdk.graal.compiler.nodes.extended.SwitchNode;
import jdk.vm.ci.code.BytecodeFrame;

public class ProfilingUtilities {

    public static EconomicMap<NodeSourcePosition, List<ControlSplitNode>> relevantConditionalNodesFromGraph(StructuredGraph graph) {
        return chooseConditionalNodesToInstrument(
                        getConditionalNodesFromGraph(graph).filter(n -> isNotForImplicitException((ControlSplitNode) n)).filter(n -> !hasUnknownBci(n)));
    }

    public static boolean isNotForImplicitException(ControlSplitNode conditionalNode) {
        for (Node successor : conditionalNode.successors().snapshot()) {
            if (((AbstractBeginNode) successor).next() instanceof BytecodeExceptionNode) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasUnknownBci(Node node) {
        for (NodeSourcePosition pos = node.getNodeSourcePosition(); pos != null; pos = pos.getCaller()) {
            if (pos.getBCI() == BytecodeFrame.UNKNOWN_BCI || pos.getBCI() == BytecodeFrame.INVALID_FRAMESTATE_BCI) {
                return true;
            }
        }
        return false;
    }

    /**
     * Input graphs contain multiple {@link IfNode}s with the same position. This method selects the
     * most important nodes to instrument for each position. Conditionals without injected
     * probabilities are prioritized over conditionals without injected probabilities.
     *
     * @param candidateConditionalNodes candidate nodes for instrumentation.
     * @return map of position to the chosen nodes to instrument.
     */
    private static EconomicMap<NodeSourcePosition, List<ControlSplitNode>> chooseConditionalNodesToInstrument(NodeIterable<Node> candidateConditionalNodes) {
        /* choose the best candidates */
        EconomicMap<NodeSourcePosition, List<ControlSplitNode>> nodesToInstrument = EconomicMap.create();
        candidateConditionalNodes.forEach(node -> {
            NodeSourcePosition position = node.getNodeSourcePosition();
            if (position == null) {
                return;
            }
            if (nodesToInstrument.containsKey(position)) {
                List<ControlSplitNode> existingNodes = nodesToInstrument.get(position);
                nodesToInstrument.put(position, chooseRelevantConditionalNodes((ControlSplitNode) node, existingNodes));
            } else {
                List<ControlSplitNode> relevantNodes = new ArrayList<>();
                relevantNodes.add((ControlSplitNode) node);
                nodesToInstrument.put(position, relevantNodes);
            }
        });
        return nodesToInstrument;
    }

    /**
     * If two {@link IfNode}s have the same source position we need to decide which nodes to
     * instrument. We always choose the ones with probability 0.5. The rationale is that the node
     * that was injected probably should not be instrumented.
     */
    private static List<ControlSplitNode> chooseRelevantConditionalNodes(ControlSplitNode node, List<ControlSplitNode> nodes) {
        assert node.getNodeSourcePosition().equals(nodes.get(0).getNodeSourcePosition()) : "This method distinguishes between nodes with the same source position.";
        if (hasNonDefaultProbability(node)) {
            return nodes;
        }
        if (hasNonDefaultProbability(nodes.get(0))) {
            nodes.remove(0);
        }
        nodes.add(node);
        return nodes;
    }

    public static NodeIterable<Node> getConditionalNodesFromGraph(StructuredGraph graph) {
        return graph.getNodes().filter(n -> n instanceof IfNode || n instanceof SwitchNode);
    }

    public record ConditionalSite(ControlSplitNode node, NodeSourcePosition context, ConditionalProfileSiteDescriptor descriptor) {
    }

    /**
     * Enumerates conditionals with a stage-qualified identity shared by producer and consumer.
     * Occurrence ordinals are assigned in deterministic graph iteration order within equal
     * context/successor/condition shapes; graph-local node ids are never serialized.
     */
    /**
     * Groups every candidate conditional by source position without the probability-based pruning
     * of {@link #relevantConditionalNodesFromGraph}. Precise site identities distinguish physical
     * copies, so all of them are kept: after loop transformations the bytecode branch of a loop
     * header exists as a peeled guard (default probability) and as the in-loop exit condition
     * (compiler-assigned probability); pruning would drop the copy that carries the iteration count.
     */
    private static EconomicMap<NodeSourcePosition, List<ControlSplitNode>> allConditionalNodesFromGraph(StructuredGraph graph) {
        EconomicMap<NodeSourcePosition, List<ControlSplitNode>> groups = EconomicMap.create();
        getConditionalNodesFromGraph(graph).filter(n -> isNotForImplicitException((ControlSplitNode) n)).filter(n -> !hasUnknownBci(n)).forEach(node -> {
            NodeSourcePosition position = node.getNodeSourcePosition();
            if (position != null) {
                List<ControlSplitNode> nodes = groups.get(position);
                if (nodes == null) {
                    nodes = new ArrayList<>();
                    groups.put(position, nodes);
                }
                nodes.add((ControlSplitNode) node);
            }
        });
        return groups;
    }

    public static List<ConditionalSite> relevantConditionalSitesFromGraph(StructuredGraph graph, Stage stage, NodeSourcePosition inliningContext) {
        EconomicMap<NodeSourcePosition, List<ControlSplitNode>> groups = allConditionalNodesFromGraph(graph);
        Map<String, Integer> nextOccurrence = new HashMap<>();
        List<ConditionalSite> result = new ArrayList<>();
        for (List<ControlSplitNode> group : groups.getValues()) {
            for (ControlSplitNode node : group) {
                NodeSourcePosition context = node.getNodeSourcePosition();
                if (inliningContext != null) {
                    context = context.addCaller(inliningContext);
                }
                List<Integer> successorBcis = node.successors().snapshot().stream()
                                .map(successor -> successor.getNodeSourcePosition() == null ? BytecodeFrame.UNKNOWN_BCI : successor.getNodeSourcePosition().getBCI()).toList();
                String conditionKind = node instanceof IfNode ifNode ? ifNode.condition().getClass().getName() : node.getClass().getName();
                long fingerprint = conditionFingerprint(node);
                String occurrenceKey = canonicalContext(context) + '|' + successorBcis + '|' + conditionKind + '|' + Long.toUnsignedString(fingerprint, 16);
                int occurrence = nextOccurrence.getOrDefault(occurrenceKey, 0);
                nextOccurrence.put(occurrenceKey, occurrence + 1);
                result.add(new ConditionalSite(node, context, new ConditionalProfileSiteDescriptor(stage, successorBcis, conditionKind, fingerprint, occurrence)));
            }
        }
        return result;
    }

    private static String canonicalContext(NodeSourcePosition position) {
        StringBuilder result = new StringBuilder();
        for (NodeSourcePosition frame = position; frame != null; frame = frame.getCaller()) {
            if (!result.isEmpty()) {
                result.append('<');
            }
            result.append(ConditionalProfileContextResolver.methodDescriptor(frame.getMethod())).append(':').append(frame.getBCI());
        }
        return result.toString();
    }

    private static long conditionFingerprint(ControlSplitNode node) {
        long hash = fingerprintText(0xcbf29ce484222325L, node.getClass().getName());
        if (node instanceof IfNode ifNode) {
            hash = fingerprintNode(hash, ifNode.condition(), 3);
        }
        return hash;
    }

    private static long fingerprintNode(long hash, Node node, int depth) {
        hash = fingerprintText(hash, node.getClass().getName());
        NodeSourcePosition position = node.getNodeSourcePosition();
        if (position != null) {
            hash = fingerprintText(hash, canonicalContext(position));
        }
        if (depth > 0) {
            for (Node input : node.inputs()) {
                hash = fingerprintNode(hash, input, depth - 1);
            }
        }
        return hash;
    }

    private static long fingerprintText(long hash, String value) {
        long result = hash;
        for (int i = 0; i < value.length(); i++) {
            result ^= value.charAt(i);
            result *= 0x100000001b3L;
        }
        return result;
    }

    private static boolean hasNonDefaultProbability(ControlSplitNode n1) {
        double defaultProbability = 1.0 / n1.successors().count();
        for (Node s : n1.successors().snapshot()) {
            if (Double.compare(n1.probability((AbstractBeginNode) s), defaultProbability) != 0) {
                return true;
            }
        }
        return false;
    }
}
