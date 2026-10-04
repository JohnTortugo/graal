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

import java.util.ArrayList;
import java.util.List;

import org.graalvm.nativeimage.ImageInfo;
import org.graalvm.nativeimage.ImageSingletons;
import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;

import com.oracle.svm.core.interpreter.InterpreterSupport;
import com.oracle.svm.hosted.BytecodeHandlerFeature;
import com.oracle.svm.hosted.meta.HostedMethod;
import com.oracle.svm.shared.option.HostedOptionKey;

import jdk.graal.compiler.debug.DebugCloseable;
import jdk.graal.compiler.graph.Node;
import jdk.graal.compiler.graph.NodeInputList;
import jdk.graal.compiler.nodes.Invoke;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.CallTargetNode;
import jdk.graal.compiler.debug.DebugContext;
import jdk.graal.compiler.debug.TimerKey;
import jdk.graal.compiler.nodes.spi.CoreProviders;
import jdk.graal.compiler.options.OptionKey;
import jdk.graal.compiler.options.OptionValues;
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
import jdk.graal.compiler.phases.common.priorityinline.nodes.SubgraphNode;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.FixedNode;
import jdk.graal.compiler.nodes.cfg.HIRBlock;
import jdk.graal.compiler.nodes.cfg.ControlFlowGraph;
import jdk.graal.compiler.phases.common.priorityinline.nodes.ParentNode;
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
    static final class HotRootTuningPolicy extends TuningPolicy {
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
            double smallRootBoost = inliningProvider.hotSmallRootInliningBoost(options);
            if (smallRootBoost <= boost) {
                return boost;
            }
            /*
             * The expansion and inlining thresholds already grow exponentially with the root's size,
             * so a uniform boost either starves a small hot root (its callees are big relative to
             * their benefit) or over-inlines into a large one. Scale with the root's current size:
             * the full small-root boost up to the typical graph size, decaying hyperbolically to the
             * base boost at four times that size.
             */
            double typicalSize = TypicalGraphSize.getValue(options);
            int currentSize = callTree.root().getSubtreeTotalCompilerNodeCount();
            return Math.max(boost, smallRootBoost * typicalSize / Math.max(typicalSize, currentSize));
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
                int count = 0;
                for (FixedNode ignored : block.getNodes()) {
                    count++;
                }
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

        /** Hot-leaf absorption statistics, reported after compilation. */
        private static final java.util.concurrent.atomic.AtomicLong HOT_LEAF_CONSIDERED = new java.util.concurrent.atomic.AtomicLong();
        private static final java.util.concurrent.atomic.AtomicLong HOT_LEAF_ABSORBED = new java.util.concurrent.atomic.AtomicLong();
        private static final java.util.concurrent.atomic.AtomicLong HOT_LEAF_COLD_ROOT = new java.util.concurrent.atomic.AtomicLong();
        private static final java.util.concurrent.atomic.AtomicLong HOT_LEAF_COLD_EDGE = new java.util.concurrent.atomic.AtomicLong();
        private static final java.util.concurrent.atomic.AtomicLong HOT_LEAF_AT_LIMIT = new java.util.concurrent.atomic.AtomicLong();
        private static final java.util.concurrent.atomic.AtomicLong HOT_LEAF_UNSHARED = new java.util.concurrent.atomic.AtomicLong();
        private static final java.util.concurrent.atomic.AtomicLong HOT_LEAF_WRONG_BUCKET = new java.util.concurrent.atomic.AtomicLong();

        public static String hotLeafStatistics() {
            return String.format("queries=%d, forced=%d, cold root=%d, cold edge=%d, not library-into-application=%d, unshared value=%d, at graph limit=%d",
                            HOT_LEAF_CONSIDERED.get(), HOT_LEAF_ABSORBED.get(), HOT_LEAF_COLD_ROOT.get(),
                            HOT_LEAF_COLD_EDGE.get(), HOT_LEAF_WRONG_BUCKET.get(), HOT_LEAF_UNSHARED.get(), HOT_LEAF_AT_LIMIT.get());
        }

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

        /**
         * The adaptive expansion threshold grows exponentially with the size of the root graph, so in
         * the largest methods, which are precisely the hottest ones, small callees are never expanded
         * and therefore never offered to the inliner. Their calls then dominate the measured self time
         * of those methods. When the profile shows that the root is hot and that this edge runs often,
         * treat a small callee as force-inlined: merely expanding it is not enough, because the
         * expansion would still compete for, and displace, other candidates in the same budget.
         */
        @Override
        public boolean profileForcesInline(CallTreeNode node) {
            CallTree callTree = node.callTree();
            OptionValues options = node.getOptions();
            if (!(callTree.inliningProvider() instanceof SubstrateInliningProvider inliningProvider)) {
                return false;
            }
            int maxCodeSize = inliningProvider.hotLeafMaxCodeSize(options);
            if (maxCodeSize <= 0) {
                return false;
            }
            ResolvedJavaMethod target = node.targetMethod();
            if (target == null || target.getCodeSize() > maxCodeSize) {
                return false;
            }
            HOT_LEAF_CONSIDERED.incrementAndGet();
            if (inliningProvider.hotLeafRequiresSampledRoot(options) && !callTree.root().getReadonlySubgraph().globalProfileProvider().hotCaller()) {
                /*
                 * Restricting absorption to sampled roots was measured to give up the whole benefit:
                 * stack sampling covers a few hundred stacks, while the frequent leaf calls that
                 * dominate self time also occur in roots sampling never observed.
                 */
                HOT_LEAF_COLD_ROOT.incrementAndGet();
                return false;
            }
            /*
             * Two profile signals qualify an edge. The call-site frequency is corrected by the
             * consumed branch profiles and is available for every call site, which matters because
             * these leaves are usually inlined in the instrumentation build and therefore have no
             * call-count record of their own. Measured root-relative hotness qualifies an edge on its
             * own when the profile does carry it.
             */
            boolean frequentEdge = node.getFrequency() >= inliningProvider.hotLeafMinFrequency(options);
            boolean measuredHotEdge = SamplingCallTreeState.getSamplingCallTreeState(callTree).hotness(node) >= inliningProvider.hotLeafMinHotness(options);
            if (!frequentEdge && !measuredHotEdge) {
                HOT_LEAF_COLD_EDGE.incrementAndGet();
                return false;
            }
            if (inliningProvider.hotLeafLibraryOnly(options) && !isLibraryLeafIntoApplicationCaller(node, target)) {
                /*
                 * Per-bucket attribution of the remaining gap to a commercial native image put all of
                 * it in JDK library leaves called from application code; application-to-application
                 * and library-to-library edges were already cheaper. Restricting absorption to that
                 * edge class is what the size and frequency rules measured before were missing.
                 */
                HOT_LEAF_WRONG_BUCKET.incrementAndGet();
                return false;
            }
            if (inliningProvider.hotLeafMinSharedCalls(options) > 1 && sharedArgumentCallCount(node) < inliningProvider.hotLeafMinSharedCalls(options)) {
                /*
                 * Absorbing a callee pays when it unblocks optimization of a value that several calls
                 * share, which is the measured case: inlining charAt and substring into a scanning
                 * loop lets the coder and bounds checks fold and the substring allocation sink.
                 * Callee size alone does not predict that, and forcing calls by size regresses.
                 */
                HOT_LEAF_UNSHARED.incrementAndGet();
                return false;
            }
            if (node instanceof CutoffNode cutoff && InliningMath.defaultRecursionPenalty(cutoff) > 0.0) {
                /* Never force a recursive call: the expansion would not terminate. */
                return false;
            }
            if (isCallGraphTooBig(callTree) || isInlinedGraphTooBig(callTree)) {
                HOT_LEAF_AT_LIMIT.incrementAndGet();
                return false;
            }
            HOT_LEAF_ABSORBED.incrementAndGet();
            return true;
        }



        private static boolean isLibraryLeafIntoApplicationCaller(CallTreeNode node, ResolvedJavaMethod target) {
            ResolvedJavaMethod caller = node.callTree().root().getReadonlySubgraph().method();
            return caller != null && isJdkLibrary(target) && !isJdkLibrary(caller);
        }

        private static boolean isJdkLibrary(ResolvedJavaMethod method) {
            String name = method.getDeclaringClass().toJavaName();
            return name.startsWith("java.") || name.startsWith("jdk.") || name.startsWith("sun.");
        }

        /**
         * Counts the calls in the caller that pass the same value as this call's first argument, which
         * for an instance call is its receiver. A value handed to several calls is one whose
         * representation the compiler cannot reason about while any of them stays out of line.
         */
        private static int sharedArgumentCallCount(CallTreeNode node) {
            Invoke invoke = node.invoke();
            if (invoke == null || !invoke.asNode().isAlive() || invoke.callTarget() == null) {
                return 0;
            }
            NodeInputList<ValueNode> arguments = invoke.callTarget().arguments();
            if (arguments.isEmpty()) {
                return 0;
            }
            ValueNode shared = arguments.first();
            if (shared == null || shared.isConstant()) {
                return 0;
            }
            int calls = 0;
            for (Node usage : shared.usages()) {
                if (usage instanceof CallTargetNode callTarget && !callTarget.arguments().isEmpty() && callTarget.arguments().first() == shared) {
                    calls++;
                }
            }
            return calls;
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
