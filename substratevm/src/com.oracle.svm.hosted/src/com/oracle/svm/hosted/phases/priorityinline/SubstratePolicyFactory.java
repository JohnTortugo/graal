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
package com.oracle.svm.hosted.phases.priorityinline;

import static com.oracle.svm.hosted.phases.priorityinline.InterproceduralPartialEscapeAnalysisCallTreeState.getIPEACallTreeState;
import static com.oracle.svm.hosted.phases.priorityinline.SubstratePriorityInliningPhase.Options.IPEAFrequency;
import static com.oracle.svm.hosted.phases.priorityinline.SubstratePriorityInliningPhase.Options.IPEAMaxForce;
import static com.oracle.svm.hosted.phases.priorityinline.SubstratePriorityInliningPhase.Options.SizeForIPEAFrequencyDecrease;
import static com.oracle.svm.hosted.phases.priorityinline.SubstratePriorityInliningPhase.Options.UseIPEA;
import static jdk.graal.compiler.phases.common.priorityinline.PriorityInliningPhase.Options.CallGraphSizePenaltyCoefficient;
import static jdk.graal.compiler.phases.common.priorityinline.PriorityInliningPhase.Options.ExpansionInertiaBaseValue;
import static jdk.graal.compiler.phases.common.priorityinline.PriorityInliningPhase.Options.ExpansionInertiaInvokeBonus;
import static jdk.graal.compiler.phases.common.priorityinline.PriorityInliningPhase.Options.RootSizePenaltyCoefficient;
import static jdk.graal.compiler.phases.common.priorityinline.PriorityInliningPhase.Options.RootSizePenaltyTypicalGraphSize;
import static jdk.graal.compiler.phases.common.priorityinline.PriorityInliningPhase.Options.TuneInlinerExploration;
import static jdk.graal.compiler.phases.common.priorityinline.PriorityInliningPhase.Options.TypicalCallGraphSize;
import static jdk.graal.compiler.phases.common.priorityinline.PriorityInliningPhase.Options.TypicalGraphSize;
import static jdk.graal.compiler.phases.common.priorityinline.PriorityInliningPhase.Options.TypicalGraphSizeInvokeBonus;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.graalvm.collections.EconomicMap;
import org.graalvm.nativeimage.ImageInfo;
import org.graalvm.nativeimage.ImageSingletons;
import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;

import com.oracle.svm.core.interpreter.InterpreterSupport;
import com.oracle.svm.hosted.BytecodeHandlerFeature;
import com.oracle.svm.hosted.meta.HostedMethod;
import com.oracle.svm.shared.option.HostedOptionKey;

import jdk.graal.compiler.core.common.type.ObjectStamp;
import jdk.graal.compiler.debug.DebugCloseable;
import jdk.graal.compiler.debug.DebugContext;
import jdk.graal.compiler.debug.TimerKey;
import jdk.graal.compiler.nodes.FixedNode;
import jdk.graal.compiler.nodes.Invoke;
import jdk.graal.compiler.nodes.NodeView;
import jdk.graal.compiler.nodes.ParameterNode;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.ValuePhiNode;
import jdk.graal.compiler.nodes.cfg.ControlFlowGraph;
import jdk.graal.compiler.nodes.cfg.HIRBlock;
import jdk.graal.compiler.nodes.java.AbstractNewObjectNode;
import jdk.graal.compiler.nodes.spi.CoreProviders;
import jdk.graal.compiler.nodes.util.GraphUtil;
import jdk.graal.compiler.nodes.virtual.AllocatedObjectNode;
import jdk.graal.compiler.options.OptionKey;
import jdk.graal.compiler.options.OptionValues;
import jdk.graal.compiler.phases.common.inlining.InliningUtil;
import jdk.graal.compiler.phases.common.priorityinline.CallTree;
import jdk.graal.compiler.phases.common.priorityinline.CallTreeState;
import jdk.graal.compiler.phases.common.priorityinline.DefaultPolicyFactory;
import jdk.graal.compiler.phases.common.priorityinline.Expander;
import jdk.graal.compiler.phases.common.priorityinline.Inliner;
import jdk.graal.compiler.phases.common.priorityinline.InliningMath;
import jdk.graal.compiler.phases.common.priorityinline.Optimizer;
import jdk.graal.compiler.phases.common.priorityinline.PriorityInliningPhase;
import jdk.graal.compiler.phases.common.priorityinline.TunableOptionKey;
import jdk.graal.compiler.phases.common.priorityinline.nodes.CallTreeNode;
import jdk.graal.compiler.phases.common.priorityinline.nodes.CutoffNode;
import jdk.graal.compiler.phases.common.priorityinline.nodes.ParentNode;
import jdk.graal.compiler.phases.common.priorityinline.nodes.SubgraphNode;
import jdk.graal.compiler.phases.common.priorityinline.tuning.BytecodeInterpreterTuningPolicy;
import jdk.graal.compiler.phases.common.priorityinline.tuning.CompositeTuningPolicy;
import jdk.graal.compiler.phases.common.priorityinline.tuning.TuningPolicy;
import jdk.graal.compiler.phases.tiers.HighTierContext;
import jdk.vm.ci.meta.ResolvedJavaMethod;

/**
 * This enables InterproceduralPartialEscapeAnalysis, see
 * {@link InterproceduralPartialEscapeAnalysisPhase} for more details on the phase. When building
 * for native image, SubstratePolicyFactory will be selected by default and subsequently
 * {@link SubstratePriorityInliningPhase} will use {@link SubstrateExpanderPolicy} and
 * {@link SubstrateInlinerPolicy}.
 */
@Platforms(Platform.HOSTED_ONLY.class)
public class SubstratePolicyFactory extends DefaultPolicyFactory {

    @Override
    public Expander.Policy createExpanderPolicy(OptionValues options, HighTierContext context) {
        return new SubstrateExpanderPolicy();
    }

    @Override
    public SubstrateInlinerPolicy createInlinerPolicy(OptionValues options) {
        return new SubstrateInlinerPolicy();
    }

    @Override
    public TuningPolicy createTuningPolicy(OptionValues options) {
        List<TuningPolicy> policies = new ArrayList<>();
        policies.add(super.createTuningPolicy(options));
        policies.add(new HotRootTuningPolicy());
        if (InterpreterSupport.isEnabled() && ImageSingletons.contains(BytecodeHandlerFeature.class)) {
            policies.add(new CremaBytecodeHandlerStubTuningPolicy());
        }
        return new CompositeTuningPolicy(policies);
    }

    /**
     * Gives every call in a hot compilation root a larger inlining budget. The inliner values a call
     * by its frequency relative to one entry of the root and charges code size in absolute terms,
     * so in a root that the whole program runs millions of times a callee invoked once per entry is
     * worth as little as in a root that runs once; the exploration budget of such a root is then
     * spent on a few large callees whose own calls stay unexpanded and whose inlining is judged
     * harmful, and most calls are never even evaluated. The sampling profile knows which roots are
     * hot: for those, benefits are multiplied and the size penalties on the expansion threshold
     * divided by the configured boost, which is equivalent to pricing their code at a fraction of
     * its size. Code growth stays bounded by the number of hot roots.
     */
    private static final class HotRootTuningPolicy extends TuningPolicy {
        private static double boost(CallTreeNode node) {
            CallTree callTree = node.callTree();
            if (!(callTree.inliningProvider() instanceof SubstrateInliningProvider inliningProvider)) {
                return 1.0;
            }
            OptionValues options = node.getOptions();
            double boost = inliningProvider.hotRootInliningBoost(options);
            if (boost <= 1.0) {
                return 1.0;
            }
            ResolvedJavaMethod rootMethod = callTree.root().getReadonlySubgraph().method();
            if (!(rootMethod instanceof HostedMethod root) || inliningProvider.inclusiveTimeShare(root) < inliningProvider.hotRootMinInclusiveShare(options)) {
                return 1.0;
            }
            return boost;
        }

        @Override
        public double cutoffLocalBenefitAmplifier(CutoffNode node) {
            return boost(node);
        }

        @Override
        public double parentLocalBenefitAmplifier(ParentNode node) {
            double boost = boost(node);
            if (boost <= 1.0) {
                return 1.0;
            }
            SubstrateInliningProvider inliningProvider = (SubstrateInliningProvider) node.callTree().inliningProvider();
            return boost * coldCodeDiscount(node, inliningProvider.hotRootColdBlockFrequency(node.getOptions()), inliningProvider.hotRootMaxColdCodeDiscount(node.getOptions()));
        }

        /**
         * The inliner charges a callee its whole node count, but the blocks of a callee that the
         * profile shows are almost never executed (exception paths, slow paths) cost the hot path
         * nothing once laid out of line. In a hot root, discount them: the inlining benefit is
         * multiplied by (fixed nodes) / (fixed nodes in blocks at or above the cold frequency),
         * capped, so that a large callee with a small hot path is priced by its hot path. Floating
         * nodes are not scheduled here; the fixed-node ratio is the estimate.
         */
        private static double coldCodeDiscount(ParentNode node, double coldBlockFrequency, double maxDiscount) {
            if (maxDiscount <= 1.0 || !(node instanceof SubgraphNode subgraph)) {
                return 1.0;
            }
            StructuredGraph graph = subgraph.getReadonlySubgraph();
            ControlFlowGraph cfg = ControlFlowGraph.newBuilder(graph).connectBlocks(true).computeFrequency(true).build();
            int fixedNodes = 0;
            int hotFixedNodes = 0;
            for (HIRBlock block : cfg.getBlocks()) {
                int count = fixedNodeCount(block);
                fixedNodes += count;
                if (block.getRelativeFrequency() >= coldBlockFrequency) {
                    hotFixedNodes += count;
                }
            }
            if (hotFixedNodes == 0) {
                return 1.0;
            }
            return Math.min(maxDiscount, (double) fixedNodes / hotFixedNodes);
        }

        @SuppressWarnings("unused")
        private static int fixedNodeCount(HIRBlock block) {
            int count = 0;
            for (FixedNode fixedNode : block.getNodes()) {
                count++;
            }
            return count;
        }

        @Override
        public boolean mustInline(ParentNode node) {
            return false;
        }

        @Override
        public double callGraphSizePenaltyMultiplier(CutoffNode node) {
            return 1.0 / boost(node);
        }

        @Override
        public double smallRootIrPenaltyMultiplier(CutoffNode node) {
            return 1.0 / boost(node);
        }

        @Override
        public double largeChildrenCountPenaltyMultiplier(CutoffNode node) {
            return 1.0 / boost(node);
        }

        @Override
        public double rootSizePenaltyMultiplier(CutoffNode node) {
            return 1.0 / boost(node);
        }
    }

    @Override
    public int priority() {
        return 10;
    }

    /**
     * This policy uses {@link HostedOptionKey}s (e.g.
     * {@link SubstratePriorityInliningPhase.Options#UseIPEA}) so can only be used when in a native
     * image or during building a native image.
     */
    @Override
    public boolean isAllowed() {
        return ImageInfo.inImageCode();
    }

    /**
     * Applies the bytecode-interpreter tuning to call trees rooted at a Crema bytecode handler
     * stub, allowing the handler implementation to be incorporated into the stub without changing
     * the tuning of other bytecode interpreters.
     */
    private static final class CremaBytecodeHandlerStubTuningPolicy extends BytecodeInterpreterTuningPolicy {
        @Override
        protected boolean appliesTo(CallTreeNode node) {
            ResolvedJavaMethod rootMethod = node.callTree().root().getReadonlySubgraph().method();
            return InterpreterSupport.singleton().isInterpreterBytecodeHandlerStub(rootMethod);
        }

        @Override
        public double callGraphSizePenaltyMultiplier(CutoffNode node) {
            return appliesTo(node) ? 0.0 : super.callGraphSizePenaltyMultiplier(node);
        }
    }

    public static class SubstrateInlinerPolicy extends Inliner.DefaultPolicy {
        @Override
        public boolean shouldContinueInlining(CallTree callTree, Optimizer optimizer, CoreProviders coreProviders) {
            if (super.shouldContinueInlining(callTree, optimizer, coreProviders)) {
                return true;
            }
            return shouldForceContinueInlining(callTree);
        }

        private static boolean shouldForceContinueInlining(CallTree callTree) {
            InterproceduralPartialEscapeAnalysisCallTreeState callTreeState = getIPEACallTreeState(callTree);
            if (!UseIPEA.getValue()) {
                return false;
            }

            if (callTreeState.numForceIPEA() >= getMaxForceIPEA(callTree)) {
                return false;
            }

            if (callTree.isCallGraphTooBig() || callTree.getPolicy().isInlinedGraphTooBig(callTree)) {
                return false;
            }

            if (callTreeState.shouldRunIPEA() && !callTreeState.callTreeModifiedInLastRound()) {
                /*
                 * If we have run IPEA this round and the CallTree has not changed, do not force
                 * another IPEA run.
                 */
                return false;
            }
            return true;
        }

        @Override
        public void beforeRound(CallTree callTree, Optimizer optimizer, CoreProviders coreProviders) {
            if (!super.shouldContinueInlining(callTree, optimizer, coreProviders) && shouldForceContinueInlining(callTree)) {
                getIPEACallTreeState(callTree).setForceIPEA(true);
            }
        }

        /* For now evaluates to 1, requires retuning. */
        private static int getMaxForceIPEA(CallTree callTree) {
            return Math.max(1, IPEAMaxForce.getValue() - callTree.getNodeCount() / 4 * SizeForIPEAFrequencyDecrease.getValue());
        }
    }

    public static class SubstrateExpanderPolicy extends Expander.DefaultPolicy {

        /**
         * Minimum value when {@link #scaleOption(OptionValues, OptionKey, int, int, boolean)
         * scaling}
         * {@link jdk.graal.compiler.phases.common.priorityinline.PriorityInliningPhase.Options#TypicalGraphSize}
         * and
         * {@link jdk.graal.compiler.phases.common.priorityinline.PriorityInliningPhase.Options#RootSizePenaltyTypicalGraphSize}.
         * We set the minimum to 0 so if scaling is minimal it disables inlining as much as possible.
         */
        private static final int GRAPH_SIZE_MIN_VALUE = 0;
        /**
         * Minimum value when {@link #scaleOption(OptionValues, OptionKey, int, int, boolean)
         * scaling}
         * {@link jdk.graal.compiler.phases.common.priorityinline.PriorityInliningPhase.Options#TypicalGraphSizeInvokeBonus}.
         * We set the minimum to 0 so if scaling is minimal it disables any bonus given.
         */
        private static final int GRAPH_SIZE_BONUS_MIN_VALUE = 0;
        /**
         * Minimum value when {@link #scaleOption(OptionValues, OptionKey, int, int, boolean)
         * scaling}
         * {@link jdk.graal.compiler.phases.common.priorityinline.PriorityInliningPhase.Options#ExpansionInertiaInvokeBonus}.
         * We set the minimum to 0 so if scaling is minimal it disables any bonus given.
         */
        private static final int EXPANSION_INERTIA_BONUS_MIN_VALUE = 0;
        /**
         * Minimum value when {@link #scaleOption(OptionValues, OptionKey, int, int, boolean)
         * scaling}
         * {@link jdk.graal.compiler.phases.common.priorityinline.PriorityInliningPhase.Options#ExpansionInertiaBaseValue}.
         * Cannot be 0 since the value is used as a divisor by the
         * {@link PriorityInliningPhase}.
         */
        private static final int EXPANSION_INERTIA_MIN_VALUE = 1;
        /**
         * When {@link #scaleOption(OptionValues, OptionKey, int, int, boolean) scaling} values,
         * multiply the value in the options with this constant to get the max value.
         */
        private static final int SCALING_MAX_VALUE_MULTIPLIER = 2;

        /** Escape inlining statistics, reported after compilation. */
        private static final AtomicLong ESCAPE_CONSIDERED = new AtomicLong();
        private static final AtomicLong ESCAPE_FORCED = new AtomicLong();

        /** How many graphs up the call tree escape inlining follows an argument to its allocation. */
        private static final int ESCAPE_MAX_DEPTH = 8;

        private final EconomicMap<StructuredGraph, LoopCfg> loopCfgs = EconomicMap.create();

        private static double boostBasedOnHotness(CallTreeNode node, double value) {
            SubstrateInliningProvider inliningProvider = (SubstrateInliningProvider) node.callTree().inliningProvider();
            SamplingCallTreeState samplingCallTreeState = SamplingCallTreeState.getSamplingCallTreeState(node.callTree());
            double hotness = samplingCallTreeState.hotness(node);
            assert 0.0 <= hotness && hotness <= 1.0;
            int scaledBonus = inliningProvider.hotBonusWhileInlining(node.getOptions());
            int selectedContextBonus = inliningProvider.selectedContextBonusWhileInlining(node.getOptions(), hotness, samplingCallTreeState.samples(node));
            if (scaledBonus == 0 && selectedContextBonus == 0) {
                return value;
            }
            return value * hotnessMultiplier(hotness, scaledBonus, selectedContextBonus);
        }

        static double hotnessMultiplier(double hotness, int scaledBonus, int selectedContextBonus) {
            return 1 + (scaledBonus * hotness) + selectedContextBonus;
        }

        private static int getFrequency(CallTree callTree) {
            return IPEAFrequency.getValue() + callTree.getNodeCount() / SizeForIPEAFrequencyDecrease.getValue();
        }

        @Override
        public void beforeRound(CallTree callTree) {
            InterproceduralPartialEscapeAnalysisCallTreeState callTreeState = getIPEACallTreeState(callTree);
            if (UseIPEA.getValue()) {
                if (callTreeState.forceIPEA()) {
                    callTreeState.setShouldRunIPEA(true);
                    callTreeState.incNumForceIPEA();
                    callTreeState.setForceIPEA(false);
                    callTreeState.resetRoundsSinceForce();
                } else {
                    callTreeState.setShouldRunIPEA(callTreeState.roundsSinceForce() % getFrequency(callTree) == 0);
                }
                if (callTreeState.shouldRunIPEA()) {
                    callTreeState.incNumIPEARounds();
                }
                callTreeState.incRoundsSinceForce();
            }
        }

        @Override
        public void afterExpansionPhase(CallTree callTree, CoreProviders coreProviders, int expansionRound, TimerKey expanderExtraAnalysisDuration) {
            InterproceduralPartialEscapeAnalysisCallTreeState callTreeState = getIPEACallTreeState(callTree);
            if (callTreeState.shouldRunIPEA()) {
                try (DebugCloseable _ = expanderExtraAnalysisDuration.start(callTree.getDebug())) {
                    DebugContext debugContext = callTree.getDebug();
                    InterproceduralPartialEscapeAnalysisUtil.afterExpansionPhase(callTree, callTreeState.analysisResult());
                    debugContext.dump(DebugContext.VERBOSE_LEVEL, callTree, "round %d, after post-expansion analysis", expansionRound);
                }
            }
        }

        @Override
        public void afterExpandingCutoffNode(CallTreeNode replacementNode, CallTreeNode replacedNode, CoreProviders coreProviders, int expansionRound, TimerKey expanderExtraAnalysisDuration) {
            InterproceduralPartialEscapeAnalysisCallTreeState callTreeState = getIPEACallTreeState(replacementNode.callTree());
            if (callTreeState.shouldRunIPEA()) {
                try (DebugCloseable _ = expanderExtraAnalysisDuration.start(replacementNode.getDebug())) {
                    callTreeState.setAnalysisResult(InterproceduralPartialEscapeAnalysisUtil.afterExpandingCutoffNode(replacementNode, replacedNode, coreProviders, callTreeState.analysisResult()));
                    replacementNode.callTree().restoreSubtreeInvariants(replacementNode.parent(), true);
                }
            }
        }

        @Override
        public void beforeExpansion(CallTree callTree, CoreProviders coreProviders, int expansionRound, TimerKey expanderExtraAnalysisDuration) {
            InterproceduralPartialEscapeAnalysisCallTreeState callTreeState = getIPEACallTreeState(callTree);
            if (callTreeState.shouldRunIPEA()) {
                try (DebugCloseable _ = expanderExtraAnalysisDuration.start(callTree.getDebug())) {
                    DebugContext debugContext = callTree.getDebug();
                    callTreeState.setAnalysisResult(InterproceduralPartialEscapeAnalysisUtil.runOnFullTree(callTree, coreProviders));
                    debugContext.dump(DebugContext.VERBOSE_LEVEL, callTree, "round %d, after pre-expansion analysis", expansionRound);
                }
            }
        }

        @Override
        public void updateCutoffNodeLocalBenefit(CutoffNode node) {
            InterproceduralPartialEscapeAnalysisCallTreeState callTreeState = getIPEACallTreeState(node.callTree());
            double originalLocalBenefit = node.getLocalBenefit();
            super.updateCutoffNodeLocalBenefit(node);
            double updatedLocalBenefit = node.getLocalBenefit() * InterproceduralPartialEscapeAnalysisUtil.escapingObjectCutoffBonus(node, callTreeState.analysisResult());
            updatedLocalBenefit = boostBasedOnHotness(node, updatedLocalBenefit);
            node.setLocalBenefit(updatedLocalBenefit);
            if (originalLocalBenefit != updatedLocalBenefit && node.activeCutoffCount() == 0) {
                // TODO BS this should be done anytime the local benefit or priority is updated
                node.setActiveCutoffCount(1);
            }
        }

        /** Forces escape inlining; see {@link #escapeInlineForced}. */
        @Override
        public boolean profileForcesInline(CallTreeNode node) {
            return escapeInlineForced(node);
        }

        public static String escapeInlineStatistics() {
            return String.format("small callees taking a caller allocation inside a loop: %d, forced inline: %d", ESCAPE_CONSIDERED.get(), ESCAPE_FORCED.get());
        }

        /** Control-flow graphs with loop information, rebuilt when the graph changed. */
        private record LoopCfg(ControlFlowGraph cfg, int nodeIdCount, int edgeModificationCount) {
        }

        /**
         * Escape-aware inlining. A call that is not inlined and takes a caller allocation as an
         * argument makes that allocation escape; if the call sits inside a loop the object must be
         * materialized before the loop and every field access in the loop goes through memory. The
         * callee's own hotness is beside the point (it is usually cold), so such a call is forced
         * inline when the callee is small. The argument is followed up the call tree through
         * parameters, proxies and merges to an allocation; the loop may be in any of the graphs on
         * the way.
         */
        private boolean escapeInlineForced(CallTreeNode node) {
            CallTree callTree = node.callTree();
            OptionValues options = node.getOptions();
            if (!(callTree.inliningProvider() instanceof SubstrateInliningProvider inliningProvider)) {
                return false;
            }
            int maxCodeSize = inliningProvider.escapeInlineMaxCodeSize(options);
            ResolvedJavaMethod target = node.targetMethod();
            Invoke invoke = node.invoke();
            if (maxCodeSize <= 0 || target == null || target.getCodeSize() > maxCodeSize || invoke == null || !invoke.asNode().isAlive() || !(node instanceof CutoffNode ||
                            node instanceof SubgraphNode)) {
                return false;
            }
            if (!callTree.root().getReadonlySubgraph().globalProfileProvider().hotCaller()) {
                return false;
            }
            if (node instanceof CutoffNode cutoff && InliningMath.defaultRecursionPenalty(cutoff) > 0.0) {
                return false;
            }
            boolean found = false;
            for (int i = 0; i < invoke.callTarget().arguments().size() && !found; i++) {
                found = argumentIsLoopInvariantCallerAllocation(node, i, 0);
            }
            if (!found) {
                return false;
            }
            ESCAPE_CONSIDERED.incrementAndGet();
            if (isCallGraphTooBig(callTree) || isInlinedGraphTooBig(callTree)) {
                return false;
            }
            ESCAPE_FORCED.incrementAndGet();
            InliningUtil.logInliningDecision(node.getDebug(), "escape inlining forces %s: it takes a caller allocation inside a loop", node);
            return true;
        }

        /**
         * Whether argument {@code index} of {@code node}'s invoke is, followed up the call tree, an
         * allocation that was made outside a loop which contains the call.
         */
        private boolean argumentIsLoopInvariantCallerAllocation(CallTreeNode node, int index, int depth) {
            Invoke invoke = node.invoke();
            if (depth > ESCAPE_MAX_DEPTH || invoke == null || !invoke.asNode().isAlive() || index >= invoke.callTarget().arguments().size()) {
                return false;
            }
            ValueNode argument = invoke.callTarget().arguments().get(index);
            if (!(argument.stamp(NodeView.DEFAULT) instanceof ObjectStamp)) {
                return false;
            }
            StructuredGraph graph = invoke.asNode().graph();
            HIRBlock invokeBlock = loopCfg(graph).blockFor(invoke.asFixedNode());
            boolean invokeInLoop = invokeBlock != null && invokeBlock.getLoop() != null;
            Set<ValueNode> seen = new HashSet<>();
            ArrayDeque<ValueNode> work = new ArrayDeque<>();
            work.add(argument);
            while (!work.isEmpty()) {
                ValueNode value = GraphUtil.unproxify(work.poll());
                if (!seen.add(value)) {
                    continue;
                }
                if (value instanceof ValuePhiNode phi) {
                    for (ValueNode input : phi.values()) {
                        work.add(input);
                    }
                } else if (value instanceof ParameterNode parameter) {
                    if (node.parent() instanceof SubgraphNode caller && argumentIsLoopInvariantCallerAllocation(caller, parameter.index(), depth + 1)) {
                        return true;
                    }
                } else if (value instanceof AbstractNewObjectNode || value instanceof AllocatedObjectNode) {
                    if (invokeInLoop) {
                        FixedNode allocation = value instanceof AllocatedObjectNode allocated ? allocated.getCommit() : (FixedNode) value;
                        HIRBlock allocationBlock = loopCfg(graph).blockFor(allocation);
                        if (allocationBlock != null && allocationBlock.getLoop() != invokeBlock.getLoop() && (allocationBlock.getLoop() == null || invokeBlock.getLoop().isAncestorOrSelf(
                                        allocationBlock.getLoop()))) {
                            return true;
                        }
                    } else if (depth > 0) {
                        /* The call is inside a loop of a callee graph; the allocation is in this caller. */
                        return true;
                    }
                }
            }
            return false;
        }

        private ControlFlowGraph loopCfg(StructuredGraph graph) {
            LoopCfg cached = loopCfgs.get(graph);
            if (cached == null || cached.nodeIdCount != graph.nodeIdCount() || cached.edgeModificationCount != graph.getEdgeModificationCount()) {
                ControlFlowGraph cfg = ControlFlowGraph.newBuilder(graph).connectBlocks(true).computeFrequency(false).computeLoops(true).computeDominators(true).build();
                cached = new LoopCfg(cfg, graph.nodeIdCount(), graph.getEdgeModificationCount());
                loopCfgs.put(graph, cached);
            }
            return cached.cfg;
        }

        @Override
        public void updateCutoffNodePriority(CutoffNode node) {
            super.updateCutoffNodePriority(node);
            SubstrateInliningProvider inliningProvider = (SubstrateInliningProvider) node.callTree().inliningProvider();
            SamplingCallTreeState samplingCallTreeState = SamplingCallTreeState.getSamplingCallTreeState(node.callTree());
            double hotness = samplingCallTreeState.hotness(node);
            assert 0.0 <= hotness && hotness <= 1.0;
            int scaledBonus = inliningProvider.hotBonusWhileExpanding(node.callTree().getOptions());
            int selectedContextBonus = inliningProvider.selectedContextBonusWhileExpanding(node.getOptions(), hotness, samplingCallTreeState.samples(node));
            if (scaledBonus == 0 && selectedContextBonus == 0) {
                return;
            }
            double newPriority = node.getPriority() + (scaledBonus * hotness) + selectedContextBonus;
            node.setPriorityAndMaxLeafPriority(newPriority);
        }

        @Override
        protected int getTypicalCallGraphSizeValue(OptionValues options) {
            if (TypicalCallGraphSize.hasBeenSet(options)) {
                return TypicalCallGraphSize.getValue(options);
            }
            // Default value for SVM
            return 200;
        }

        @Override
        protected double getCallGraphSizePenaltyCoefficientValue(OptionValues options) {
            if (CallGraphSizePenaltyCoefficient.hasBeenSet(options)) {
                return CallGraphSizePenaltyCoefficient.getValue(options);
            }
            // The default value for SVM
            return 0.001;
        }

        @Override
        protected double getRootSizePenaltyCoefficientValue(OptionValues options) {
            if (RootSizePenaltyCoefficient.hasBeenSet(options)) {
                return RootSizePenaltyCoefficient.getValue(options);
            }
            // We currently do not apply any penalty for large root sizes on SVM
            return 0;
        }

        @Override
        public void updateParentNodeLocalBenefit(ParentNode node) {
            super.updateParentNodeLocalBenefit(node);
            double localBenefit = node.getLocalBenefit();
            Double boost = getIPEACallTreeState(node.callTree()).getCachedLocalBenefitBoost(node);
            if (UseIPEA.getValue() && boost != null) {
                localBenefit = localBenefit * boost;
            }
            node.setLocalBenefit(boostBasedOnHotness(node, localBenefit));
        }

        @Override
        public CallTreeState createCallTreeState() {
            return new SamplingCallTreeState();
        }

        @Override
        public int expansionInertiaBaseValue(OptionValues options) {
            return scaleOption(options, ExpansionInertiaBaseValue, EXPANSION_INERTIA_MIN_VALUE, SCALING_MAX_VALUE_MULTIPLIER * ExpansionInertiaInvokeBonus.getValue(options), true);
        }

        @Override
        public int expansionInertiaInvokeBonus(OptionValues options) {
            return scaleOption(options, ExpansionInertiaInvokeBonus, EXPANSION_INERTIA_BONUS_MIN_VALUE, SCALING_MAX_VALUE_MULTIPLIER * ExpansionInertiaInvokeBonus.getValue(options), true);
        }

        @Override
        public int typicalGraphSize(OptionValues options) {
            return scaleOption(options, TypicalGraphSize, GRAPH_SIZE_MIN_VALUE, SCALING_MAX_VALUE_MULTIPLIER * TypicalGraphSize.getValue(options), true);
        }

        @Override
        public int typicalGraphSizeInvokeBonus(OptionValues options) {
            return scaleOption(options, TypicalGraphSizeInvokeBonus, GRAPH_SIZE_BONUS_MIN_VALUE, SCALING_MAX_VALUE_MULTIPLIER * TypicalGraphSizeInvokeBonus.getValue(options), true);
        }

        @Override
        protected int rootSizePenaltyTypicalGraphSize(OptionValues options) {
            return scaleOption(options, RootSizePenaltyTypicalGraphSize, GRAPH_SIZE_MIN_VALUE, SCALING_MAX_VALUE_MULTIPLIER * RootSizePenaltyTypicalGraphSize.getValue(options), true);
        }

        private static int scaleOption(OptionValues options, OptionKey<Integer> optionKey, int minValue, int maxValue, boolean higherIsMoreExpensive) {
            return (int) TunableOptionKey.getTunedValue(TuneInlinerExploration.getValue(options),
                            minValue,
                            optionKey.getValue(options),
                            maxValue,
                            higherIsMoreExpensive);
        }

        @Override
        public int getExtraStatisticsMetric(CallTree callTree) {
            return getIPEACallTreeState(callTree).numIPEARounds();
        }
    }
}
