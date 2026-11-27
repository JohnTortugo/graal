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

import static jdk.graal.compiler.nodes.extended.BranchProbabilityNode.EXTREMELY_SLOW_PATH_PROBABILITY;
import static jdk.graal.compiler.phases.common.priorityinline.PriorityInliningPhase.Options.UseGraphCache;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import com.oracle.svm.core.SubstrateTarget;
import com.oracle.svm.core.graal.meta.RuntimeConfiguration;
import com.oracle.svm.core.imagelayer.ImageLayerBuildingSupport;
import com.oracle.svm.core.nodes.CFunctionPrologueNode;
import com.oracle.svm.core.nodes.SubstrateMethodCallTargetNode;
import com.oracle.svm.hosted.cai.PrefixTree;
import com.oracle.svm.hosted.code.CompileQueue;
import com.oracle.svm.hosted.meta.HostedMethod;
import com.oracle.svm.hosted.meta.HostedType;
import com.oracle.svm.hosted.meta.HostedUniverse;
import com.oracle.svm.hosted.pgo.PGOInstrumentationFeature;
import com.oracle.svm.hosted.pgo.phases.PGOApplyProfilesPhase;
import com.oracle.svm.hosted.pgo.profiles.PGOProfilesLookup;
import com.oracle.svm.hosted.pgo.profiles.ProfiledInliningProvider;
import com.oracle.svm.hosted.phases.OOMEExceptionEdgePolicy;
import com.oracle.svm.shared.option.HostedOptionKey;
import com.oracle.svm.shared.option.HostedOptionValues;

import jdk.graal.compiler.core.common.CompilationIdentifier;
import jdk.graal.compiler.core.common.type.ObjectStamp;
import jdk.graal.compiler.core.common.type.Stamp;
import jdk.graal.compiler.core.common.type.StampFactory;
import jdk.graal.compiler.debug.DebugContext;
import jdk.graal.compiler.graph.Node;
import jdk.graal.compiler.graph.NodeSourcePosition;
import jdk.graal.compiler.nodes.CallTargetNode;
import jdk.graal.compiler.nodes.GraphDecoder.DecodeContext;
import jdk.graal.compiler.nodes.IndirectCallTargetNode;
import jdk.graal.compiler.nodes.Invoke;
import jdk.graal.compiler.nodes.NodeView;
import jdk.graal.compiler.nodes.ParameterNode;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.ValuePhiNode;
import jdk.graal.compiler.nodes.java.MethodCallTargetNode;
import jdk.graal.compiler.nodes.java.NewInstanceNode;
import jdk.graal.compiler.nodes.spi.CoreProviders;
import jdk.graal.compiler.nodes.util.GraphUtil;
import jdk.graal.compiler.nodes.virtual.AllocatedObjectNode;
import jdk.graal.compiler.options.EnumOptionKey;
import jdk.graal.compiler.options.Option;
import jdk.graal.compiler.options.OptionKey;
import jdk.graal.compiler.options.OptionType;
import jdk.graal.compiler.options.OptionValues;
import jdk.graal.compiler.phases.common.BoxNodeIdentityPhase;
import jdk.graal.compiler.phases.common.CanonicalizerPhase;
import jdk.graal.compiler.phases.common.inlining.InliningUtil;
import jdk.graal.compiler.phases.common.priorityinline.CallTree;
import jdk.graal.compiler.phases.common.priorityinline.Expander;
import jdk.graal.compiler.phases.common.priorityinline.GraphCache;
import jdk.graal.compiler.phases.common.priorityinline.PriorityInliningPhase;
import jdk.graal.compiler.phases.common.priorityinline.data.BenefitKind;
import jdk.graal.compiler.phases.common.priorityinline.nodes.CallTreeNode;
import jdk.graal.compiler.phases.common.priorityinline.nodes.CutoffNode;
import jdk.graal.compiler.phases.common.priorityinline.nodes.DontInlineCause;
import jdk.graal.compiler.phases.common.priorityinline.nodes.GenericNode;
import jdk.graal.compiler.phases.common.priorityinline.nodes.InlineCacheNode;
import jdk.graal.compiler.phases.common.priorityinline.nodes.SubgraphNode;
import jdk.graal.compiler.phases.common.priorityinline.nodes.devirtualization.Devirtualization;
import jdk.graal.compiler.phases.common.priorityinline.nodes.devirtualization.DevirtualizationUtil;
import jdk.graal.compiler.phases.common.priorityinline.tuning.TuningPolicy;
import jdk.graal.compiler.phases.tiers.HighTierContext;
import jdk.vm.ci.meta.AbstractJavaProfile;
import jdk.vm.ci.meta.Assumptions;
import jdk.vm.ci.meta.JavaMethodProfile;
import jdk.vm.ci.meta.JavaTypeProfile;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import jdk.vm.ci.meta.ResolvedJavaType;
import jdk.vm.ci.meta.SpeculationLog;
import jdk.vm.ci.meta.TriState;

public class SubstratePriorityInliningPhase extends PriorityInliningPhase {

    public static final String SUBSTRATE_PRIORITY_INLINING_PHASE = SubstratePriorityInliningPhase.class.getSimpleName();

    /** Receiver-type specialization statistics, reported after compilation by the PGO feature. */
    private static final AtomicLong RECEIVER_SPECIALIZATION_CANDIDATES = new AtomicLong();
    private static final AtomicLong RECEIVER_SPECIALIZATION_CREATED = new AtomicLong();
    private static final AtomicLong RECEIVER_SPECIALIZATION_NO_DOMINANT = new AtomicLong();
    private static final AtomicLong RECEIVER_SPECIALIZATION_DISAGREE = new AtomicLong();

    private static final int EXACT_RECEIVER_SET_LIMIT = 4;
    private static final AtomicLong EXACT_RECEIVER_SET_REFINED = new AtomicLong();
    private static final AtomicLong EXACT_RECEIVER_SET_FALLBACKS_REMOVED = new AtomicLong();

    public static String exactReceiverSetStatistics() {
        return String.format("inline caches refined=%d, fallbacks removed=%d", EXACT_RECEIVER_SET_REFINED.get(), EXACT_RECEIVER_SET_FALLBACKS_REMOVED.get());
    }

    public static String receiverSpecializationStatistics() {
        return String.format("candidates=%d, guarded=%d, no dominant type=%d, sites disagree=%d", RECEIVER_SPECIALIZATION_CANDIDATES.get(), RECEIVER_SPECIALIZATION_CREATED.get(),
                        RECEIVER_SPECIALIZATION_NO_DOMINANT.get(), RECEIVER_SPECIALIZATION_DISAGREE.get());
    }

    public static class Options {
        //@formatter:off
        @Option(help = "Use InterproceduralPartialEscapeAnalysisPhase in priorityInliner.")//
        public static final HostedOptionKey<Boolean> UseIPEA = new HostedOptionKey<>(true);

        @Option(help = "Track IPEA statistics in substratePriorityInliner.", type = OptionType.Debug)//
        public static final OptionKey<TrackIPEAMode> TrackIPEAStatistics = new EnumOptionKey<>(TrackIPEAMode.none);

        @Option(help = "Track IPEA statistics in substratePriorityInliner.", type = OptionType.Debug)//
        public static final HostedOptionKey<Integer> IPEAStatisticsHistogramBuckets = new HostedOptionKey<>(12);

        @Option(help = "Boost for CutoffNode for single escaping object.", type = OptionType.Expert)//
        public static final HostedOptionKey<Integer> IPEAVirtualEscapeBoostSingle = new HostedOptionKey<>(12);

        @Option(help = "Boost for Parent Nodes based on reduction of materializations.", type = OptionType.Expert)//
        public static final HostedOptionKey<Integer> IPEAMaterializationBoostConstant = new HostedOptionKey<>(12);

        @Option(help = "Weight of a materialization triggered by an Invoke corresponding to a CutoffNode in the CallTree for IPEA.", type = OptionType.Expert)//
        public static final HostedOptionKey<Double> IPEACutoffMaterializationWeight = new HostedOptionKey<>(0.0D);

        @Option(help = "Indicates how often to run IPEA analysis", type = OptionType.Expert)//
        public static final HostedOptionKey<Integer> IPEAFrequency = new HostedOptionKey<>(3);

        @Option(help = "Indicates how often to force IPEA analysis when otherwise inlining would not continue", type = OptionType.Expert)//
        public static final HostedOptionKey<Integer> IPEAMaxForce = new HostedOptionKey<>(2);

        @Option(help = "Size of the IR that forces a reduction in the number of IPEA runs made.", type = OptionType.Expert)//
        public static final HostedOptionKey<Integer> SizeForIPEAFrequencyDecrease = new HostedOptionKey<>(500);

        @Option(help = "Devirtualizes callees that are hot.")
        public static final OptionKey<Boolean> DevirtualizeHotCallees = new OptionKey<>(true);

        //@formatter:on
    }

    enum TrackIPEAMode {
        none(false, false, false),
        histogram(true, false, true),
        dumpLast(true, true, true),
        dumpAll(true, true, false);

        private final boolean shouldTrack;
        private final boolean verbose;
        private final boolean lastRound;

        TrackIPEAMode(boolean shouldTrack, boolean verbose, boolean lastRound) {
            this.shouldTrack = shouldTrack;
            this.verbose = verbose;
            this.lastRound = lastRound;
        }

        public boolean shouldTrack() {
            return shouldTrack;
        }

        public boolean verbose() {
            return verbose;
        }

        public boolean lastRound() {
            return lastRound;
        }
    }

    /** The profiles to apply to expanded callees, or {@code null} without {@code --pgo}. */
    private final PGOProfilesLookup profilesLookup;
    private final RuntimeConfiguration runtimeConfig;
    private final HostedUniverse universe;
    private final boolean layeredBuild;

    @SharedGlobalPhaseState private static volatile boolean IPEAShutDownHookAdded;
    static final InterProceduralPartialEscapeAnalysisStatistics IPEAStatistics = new InterProceduralPartialEscapeAnalysisStatistics();

    private SubstratePriorityInliningPhase(CanonicalizerPhase canonicalizer, OptionValues options, RuntimeConfiguration runtimeConfig,
                    HostedUniverse universe, SubstrateInliningProvider inliningProvider, PGOProfilesLookup profilesLookup) {
        super(canonicalizer, options, inliningProvider);
        this.runtimeConfig = runtimeConfig;
        this.universe = universe;
        this.profilesLookup = profilesLookup;
        this.layeredBuild = ImageLayerBuildingSupport.buildingExtensionLayer();
    }

    /**
     * Creates a copy of {@code oldPhase} with another inlining provider and profiles. With
     * {@code --pgo}, the profile use feature installs this copy in place of the original phase.
     *
     * @param oldPhase the phase to copy the canonicalizer, runtime configuration and universe from
     * @param substrateInliningProvider the inlining provider of the copy
     * @param profilesLookup the profiles to apply to expanded callees, or {@code null} for none
     */
    public SubstratePriorityInliningPhase(SubstratePriorityInliningPhase oldPhase, SubstrateInliningProvider substrateInliningProvider, PGOProfilesLookup profilesLookup) {
        this(oldPhase.canonicalizer, HostedOptionValues.singleton().get(), oldPhase.runtimeConfig, oldPhase.universe, substrateInliningProvider, profilesLookup);
    }

    /**
     * Creates the phase with the default inlining provider.
     *
     * @param canonicalizer the canonicalizer run on inlined graphs
     * @param options the option values of the phase
     * @param runtimeConfig the runtime configuration
     * @param universe the hosted universe
     * @param profilesLookup the profiles to apply to expanded callees, or {@code null} without
     *            {@code --pgo}
     */
    public SubstratePriorityInliningPhase(CanonicalizerPhase canonicalizer, OptionValues options, RuntimeConfiguration runtimeConfig,
                    HostedUniverse universe, PGOProfilesLookup profilesLookup) {
        this(canonicalizer, options, runtimeConfig, universe, new SubstrateInliningProvider(universe, _ -> null), profilesLookup);
    }

    private static void registerIPEAStatisticsShutdownHook(TrackIPEAMode trackIPEAMode) {
        if (!IPEAShutDownHookAdded) {
            synchronized (InterProceduralPartialEscapeAnalysisStatistics.class) {
                if (!IPEAShutDownHookAdded) {
                    try {
                        InterProceduralPartialEscapeAnalysisStatistics.IPEAStatisticsThread thread = new InterProceduralPartialEscapeAnalysisStatistics.IPEAStatisticsThread(IPEAStatistics,
                                        trackIPEAMode, Options.IPEAStatisticsHistogramBuckets.getValue());
                        Runtime.getRuntime().addShutdownHook(thread);
                        IPEAShutDownHookAdded = true;
                    } catch (IllegalStateException ise) {
                        // VM is already in process of shutting down - ignore
                    }
                }
            }
        }
    }

    @Override
    protected boolean isForceInlinedTarget(ResolvedJavaMethod targetMethod, Invoke invoke) {
        if (targetMethod instanceof HostedMethod && inliningForbidden(invoke)) {
            return false;
        }
        return super.isForceInlinedTarget(targetMethod, invoke);
    }

    @Override
    protected void runInlining(StructuredGraph graph, HighTierContext context) {
        logNodeCount("before", graph);
        super.runInlining(graph, context);
        logNodeCount("after", graph);
        TrackIPEAMode trackIPEAMode = Options.TrackIPEAStatistics.getValue(graph.getOptions());
        if (trackIPEAMode.shouldTrack()) {
            registerIPEAStatisticsShutdownHook(trackIPEAMode);
        }
    }

    private static void logNodeCount(String when, StructuredGraph graph) {
        try (DebugContext.Scope _ = graph.getDebug().scope(SUBSTRATE_PRIORITY_INLINING_PHASE)) {
            graph.getDebug().log("[%s] Node count of %s %s inlining: %d", SUBSTRATE_PRIORITY_INLINING_PHASE, graph.method().format("%H.%n"), when, graph.getNodeCount());
        }
    }

    /**
     * Priority inlining needs the parsed graph both to estimate a callee's cost and to expand it.
     * The graph can be unavailable for methods from a prior layer or for methods that become
     * reachable too late to be parsed.
     */
    private boolean inliningForbidden(Invoke invoke) {
        Predicate<HostedMethod> unavailableMethod = method -> {
            HostedMethod caller = (HostedMethod) invoke.asNode().graph().method();
            HostedMethod inlinee = CompileQueue.selectInlineeMethod(caller, method);
            boolean unavailable = inlinee.compilationInfo.getCompilationGraph() == null;
            if (unavailable && layeredBuild) {
                /*
                 * We have compiled this method in a prior layer, but don't have the graph available
                 * here.
                 */
                assert method.isCompiledInPriorLayer() || !method.wrapped.reachableInCurrentLayer() : method;
            }
            return unavailable;
        };

        HostedMethod targetMethod = (HostedMethod) invoke.getTargetMethod();
        if (invoke.getInvokeKind().isDirect()) {
            return unavailableMethod.test(targetMethod);
        }
        /*
         * GR-57274 - we can relax this if we add some hooks to the inline cache logic.
         */
        return Arrays.stream(targetMethod.getImplementations()).anyMatch(unavailableMethod);
    }

    @Override
    protected CallTree createCallTree(HighTierContext context, StructuredGraph graph, Expander.Policy expanderPolicy, TuningPolicy tuningPolicy) {
        GraphCache<ResolvedJavaMethod, StructuredGraph> graphCache = new GraphCache<>();
        SubgraphNode root = createRootNode(graphCache, graph);
        return new SubstrateCallTree(expanderPolicy, tuningPolicy, context, graphCache, root, graph.getOptions());
    }

    @Override
    protected SubstrateInliningProvider getInliningProvider() {
        return (SubstrateInliningProvider) super.getInliningProvider();
    }

    private class SubstrateCallTree extends CallTree {
        private final SubgraphNode root;
        private final IdentityHashMap<CallTreeNode, PrefixTree.Cursor> nodeContextMap = new IdentityHashMap<>();

        SubstrateCallTree(Expander.Policy expanderPolicy, TuningPolicy tuningPolicy, HighTierContext context, GraphCache<ResolvedJavaMethod, StructuredGraph> graphCache, SubgraphNode root,
                        OptionValues options) {
            super(SubstratePriorityInliningPhase.this.canonicalizer, expanderPolicy, tuningPolicy, context, getInliningProvider(), graphCache, root,
                            options, SubstratePriorityInliningPhase.this.directedRules);
            this.root = root;
        }

        /**
         * Allows priority inlining to build/use an OOME-aware graph variant only for methods that
         * can support explicit OOME control flow. For more information, see
         * {@link OOMEExceptionEdgePolicy}.
         */
        @Override
        protected boolean supportsOOMEExceptionEdges(ResolvedJavaMethod method) {
            return OOMEExceptionEdgePolicy.supportsOOMEExceptionEdges(method);
        }

        /**
         * Leaves out the counters that {@code --pgo-instrument} adds. Counted, they make every
         * instrumented root and expanded callee look larger, and the build inlines fewer methods
         * than one without counters.
         */
        @Override
        public int compilerNodeCount(StructuredGraph graph) {
            return graph.getNodeCount() - PGOInstrumentationFeature.instrumentationNodeCount(graph);
        }

        @Override
        public GraphCache.Ref<ResolvedJavaMethod, StructuredGraph> createGraph(ResolvedJavaMethod targetMethod, Invoke invoke, boolean withNodeSourcePosition,
                        NodeSourcePosition replaceePosition, boolean inOOMEProtectedInlineContext) {
            assert targetMethod instanceof HostedMethod;
            HostedMethod caller = (HostedMethod) invoke.asNode().graph().method();
            HostedMethod inlinee = CompileQueue.selectInlineeMethod(caller, (HostedMethod) targetMethod);
            CompileQueue.verifyInliningProviders((HostedMethod) root.getReadonlySubgraph().method(), inlinee, runtimeConfig);

            GraphCache<ResolvedJavaMethod, StructuredGraph> selectedGraphCache = getGraphCache(inOOMEProtectedInlineContext);
            /*
             * Instrumented expansions carry counters, and with --pgo expansions carry profiles, keyed
             * by this expansion's caller context, so a cached copy would serve every later
             * expansion of the callee under the first context. Therefore the graph cache is not used
             * with --pgo-instrument or --pgo. A cache per caller context would rarely hit: the same
             * callee is seldom expanded twice under the same context.
             */
            boolean useCache = UseGraphCache.getValue(getOptions()) && !PGOInstrumentationFeature.enabled() && profilesLookup == null;
            GraphCache.Ref<ResolvedJavaMethod, StructuredGraph> ref = useCache ? selectedGraphCache.getRef(inlinee) : null;
            if (ref == null) {
                DecodeContext decodeContext = inOOMEProtectedInlineContext ? DecodeContext.OOME_EXCEPTION_EDGES : DecodeContext.DEFAULT;
                StructuredGraph graphCopy = inlinee.compilationInfo.createGraph(getDebug(), getOptions(), CompilationIdentifier.INVALID_COMPILATION_ID, true, decodeContext);
                /* Ensure box nodes in the graph are processed before using the graph. */
                new BoxNodeIdentityPhase().apply(graphCopy, getContext());
                CallTree.mergeUnwinds(graphCopy);

                applyPGOProfiles(graphCopy, getContext(), replaceePosition);
                PGOInstrumentationFeature.instrumentExpandedGraph(graphCopy, replaceePosition);

                ref = useCache ? selectedGraphCache.createRef(inlinee, graphCopy) : selectedGraphCache.createNonCounted(graphCopy);
            }
            totalNodes += ref.readonly().getNodeCount();
            assert !withNodeSourcePosition || ref.readonly().trackNodeSourcePosition();
            return ref;
        }

        @Override
        /*
         * TODO BS GR-42485 Refactor fully. Unify the two different devirtualization approaches used
         * below.
         */
        public void devirtualizeHotCallees(CoreProviders coreProviders) {
            assert root.getOptions() != null;
            if (!root.getReadonlySubgraph().globalProfileProvider().hotCaller() ||
                            !SubstratePriorityInliningPhase.Options.DevirtualizeHotCallees.getValue(root.getOptions())) {
                return;
            }
            devirtualizeInlineCacheNodes(coreProviders);
            devirtualizeIndirectCallTargetInvokes(coreProviders);
        }

        private void devirtualizeInlineCacheNodes(CoreProviders coreProviders) {
            for (CallTreeNode child : root.children().filter(InlineCacheNode.class::isInstance)) {
                InlineCacheNode inlineCacheNode = (InlineCacheNode) child;
                if (isCFunctionInvoke(inlineCacheNode.invoke())) {
                    return;
                }
                if (!hasReceiverFrequencyProfile(inlineCacheNode.invoke())) {
                    continue;
                }
                List<SubgraphNode> hotSubgraphNodes = inlineCacheNode.children().stream().filter(e -> !(e instanceof GenericNode)).filter(this::isCallSiteToHotCaller).map(this::expandIfCutoff).filter(
                                Objects::nonNull).collect(Collectors.toList());
                if (!hotSubgraphNodes.isEmpty()) {
                    inlineCacheNode.createDevirtualizationCascade(coreProviders, hotSubgraphNodes, false, SpeculationLog.NO_SPECULATION,
                                    _ -> {
                                    });
                }
            }
        }

        /**
         * Hot-callee devirtualization guards an inline cache by receiver, so it has to know how
         * often each receiver is dispatched. Stack samples do not carry that information: they weight
         * a callee by the time spent under it, which favours long-running callees over frequently
         * dispatched ones. Guarding a hot call site on a rarely dispatched receiver makes every
         * dispatch pay for a failing guard, so only devirtualize when a frequency-based receiver
         * profile backs the call site: the call target of {@code invoke} has a dynamic type
         * profile.
         */
        private static boolean hasReceiverFrequencyProfile(Invoke invoke) {
            return invoke.callTarget() instanceof SubstrateMethodCallTargetNode target && target.hasDynamicTypeProfile();
        }

        private void devirtualizeIndirectCallTargetInvokes(CoreProviders coreProviders) {
            StructuredGraph readonlySubgraph = root.getReadonlySubgraph();
            for (Node node : readonlySubgraph.getNodes().filter(IndirectCallTargetNode.class::isInstance).snapshot()) {
                Invoke invoke = ((IndirectCallTargetNode) node).invoke();
                if (isCFunctionInvoke(invoke)) {
                    continue;
                }
                CallTargetNode callTarget = invoke.callTarget();
                if (callTarget instanceof IndirectCallTargetNode) {
                    JavaMethodProfile javaMethodProfile = getInliningProvider().samplingMethodProfiles((HostedMethod) readonlySubgraph.method(), invoke);
                    if (javaMethodProfile == null) {
                        continue;
                    }
                    List<Devirtualization> devirtualizations = new ArrayList<>();
                    for (JavaMethodProfile.ProfiledMethod profiledMethod : javaMethodProfile.getMethods()) {
                        devirtualizations.add(new StandaloneAddressBasedDevirtualization(invoke, profiledMethod.getMethod(), profiledMethod.getProbability()));
                    }
                    if (!devirtualizations.isEmpty()) {
                        DevirtualizationUtil.createDevirtualizationCascade(coreProviders, getInliningProvider(), invoke, devirtualizations, false, true, SpeculationLog.NO_SPECULATION, null);
                    }
                }
            }
        }

        private SubgraphNode expandIfCutoff(CallTreeNode currentNode) {
            if (currentNode instanceof SubgraphNode) {
                return ((SubgraphNode) currentNode);
            }
            if (currentNode instanceof CutoffNode) {
                final CallTreeNode replacementNode = expandCutoffNodeBypassPoliciesAndSetHotness((CutoffNode) currentNode);
                if (replacementNode instanceof SubgraphNode) {
                    currentNode.replaceWithAndDelete(replacementNode);
                    return (SubgraphNode) replacementNode;
                }
            }
            // TODO BS What's the correct way to handle this?
            return null;
        }

        private boolean isCallSiteToHotCaller(CallTreeNode directCallNode) {
            ResolvedJavaMethod dispatchedMethod = dispatchedMethod(directCallNode);
            if (dispatchedMethod == null) {
                return false;
            }
            HostedMethod compilationRoot = (HostedMethod) root.getReadonlySubgraph().method();
            NodeSourcePosition callPosition = directCallNode.invoke().asNode().getNodeSourcePosition();
            return getInliningProvider().isCallSiteToHotCaller(compilationRoot, callPosition, dispatchedMethod);
        }

        private ResolvedJavaMethod dispatchedMethod(CallTreeNode directCallNode) {
            if (directCallNode instanceof SubgraphNode) {
                return ((SubgraphNode) directCallNode).getDispatchedMethod();
            }
            if (directCallNode instanceof CutoffNode) {
                return directCallNode.targetMethod();
            }
            getDebug().log("[SubstratePriorityInliningPhase] Failed to lookup dispatched method for CallTreeNode");
            return null;
        }

        private void applyPGOProfiles(StructuredGraph graphCopy, HighTierContext highTierContext, NodeSourcePosition replaceePosition) {
            if (profilesLookup == null) {
                return;
            }
            assert root.getOptions() != null;
            SubstrateInliningProvider inliningProvider = getInliningProvider();
            if (root.getReadonlySubgraph().globalProfileProvider().hotCaller() && inliningProvider.shouldApplyProfilesWhileExpanding(root.getOptions())) {
                PGOApplyProfilesPhase applyProfilesPhase = inliningProvider.createPGOApplyProfilesPhase(
                                root.getReadonlySubgraph().method(),
                                null,
                                graphCopy.method(),
                                replaceePosition);
                applyProfilesPhase.apply(graphCopy, highTierContext);
                return;
            }
            /*
             * The profile is applied for this expansion's context. The graph cache is not used while
             * a profile is applied (see createGraph), so no other expansion shares this graph.
             */
            assert inliningProvider != null : "Provider must not be null";
            PGOApplyProfilesPhase.createForExpandingCutoffs(replaceePosition, universe, profilesLookup).apply(graphCopy, highTierContext);
        }

        @Override
        public CallTreeNode createChild(CallTreeNode caller, Invoke invoke, double frequency) {
            if (!invoke.useForInlining()) {
                return createGenericChild(caller, invoke, frequency, DontInlineCause.NotUsedForInlining);
            } else if (inliningForbidden(invoke)) {
                return createGenericChild(caller, invoke, frequency, DontInlineCause.NotUsedForInlining);
            }
            return super.createChild(caller, invoke, frequency);
        }

        @Override
        protected CutoffNode createCutoffNode(CallTreeNode caller, Invoke invoke, ResolvedJavaMethod targetMethod, ResolvedJavaType dispatchedType, ResolvedJavaType originalDispatchedType,
                        boolean monomorphic, double frequency, EnumSet<BenefitKind> benefits) {
            SubstrateCutoffNode substrateCutoffNode = new SubstrateCutoffNode(concatPositions(invoke, caller), invoke, frequency, targetMethod, dispatchedType, originalDispatchedType, monomorphic,
                            benefits);
            SamplingCallTreeState samplingCallTreeState = SamplingCallTreeState.getSamplingCallTreeState(caller.callTree());
            setHotness(caller, invoke, targetMethod, substrateCutoffNode, samplingCallTreeState);
            return substrateCutoffNode;
        }

        private void setHotness(CallTreeNode caller, Invoke invoke, ResolvedJavaMethod targetMethod, CallTreeNode substrateCutoffNode, SamplingCallTreeState samplingCallTreeState) {
            StructuredGraph rootGraph = caller.callTree().root().getReadonlySubgraph();
            SubstrateInliningProvider.SamplingContext context = getInliningProvider().compilationRootSamplingContext((HostedMethod) rootGraph.method(), concatPositions(invoke, caller), targetMethod);
            samplingCallTreeState.setSamplingContext(substrateCutoffNode, context);
        }

        /**
         * We return the first available profile in the following ordered list (skipping method
         * profiles if disallowed):
         *
         * 1. Fully context-sensitive method profile inferred from sampling<br>
         * 2. (Potentially context-insensitive) Dynamic type profile<br>
         * 3. (Potentially context-insensitive) Dynamic method profile<br>
         * 4. Static type profile<br>
         * 5. Static method profile
         */
        @Override
        protected AbstractJavaProfile<?, ?> getPreferredProfile(CallTreeNode caller, MethodCallTargetNode callTarget) {
            if (caller == null || callTarget == null) {
                return null;
            }
            SubstrateInliningProvider inliningProvider = getInliningProvider();
            boolean allowMethodProfiles = inliningProvider.useMethodChecks(getOptions());
            JavaMethodProfile fullyContextSensitiveMethodProfile = inliningProvider.samplingMethodProfiles(nodeContextMap,
                            (HostedMethod) root.getReadonlySubgraph().method(), caller,
                            callTarget);
            if (allowMethodProfiles && fullyContextSensitiveMethodProfile != null) {
                return fullyContextSensitiveMethodProfile;
            }
            SubstrateMethodCallTargetNode substrateCallTarget = (SubstrateMethodCallTargetNode) callTarget;
            if (substrateCallTarget.hasDynamicTypeProfile()) {
                return substrateCallTarget.getTypeProfile();
            }
            if (allowMethodProfiles && substrateCallTarget.hasDynamicMethodProfile()) {
                return substrateCallTarget.getMethodProfile();
            }
            JavaTypeProfile staticTypeProfile = substrateCallTarget.getStaticTypeProfile();
            if (staticTypeProfile != null) {
                return staticTypeProfile;
            }
            JavaMethodProfile staticMethodProfile = substrateCallTarget.getStaticMethodProfile();
            if (allowMethodProfiles && staticMethodProfile != null) {
                return staticMethodProfile;
            }
            return null;
        }

        private CallTreeNode expandCutoffNodeBypassPoliciesAndSetHotness(CutoffNode cutoffNode) {
            // Ignore expander policy for devirtualization
            CallTreeNode expanded = expandCutoffNodeBypassPolicies(cutoffNode);
            setHotnessForExpandedNode(cutoffNode, expanded);
            return expanded;
        }

        private static void setHotnessForExpandedNode(CutoffNode cutoffNode, CallTreeNode expanded) {
            SamplingCallTreeState samplingCallTreeState = SamplingCallTreeState.getSamplingCallTreeState(cutoffNode.callTree());
            samplingCallTreeState.setSamplingContext(expanded, samplingCallTreeState.samplingContext(cutoffNode));
        }

        @Override
        public CallTreeNode expandCutoffNode(CutoffNode cutoffNode) {
            CallTreeNode expanded = super.expandCutoffNode(cutoffNode);
            setHotnessForExpandedNode(cutoffNode, expanded);
            if (cutoffNode.parent() instanceof InlineCacheNode parentCache && parentCache.isDirectCallSpecialization()) {
                InliningUtil.logInliningDecision(cutoffNode.getDebug(), "expanded specialization child %s of %s to %s", cutoffNode, parentCache, expanded);
            }
            CallTreeNode specialized = specializeForDominantReceiverType(cutoffNode, expanded);
            if (specialized != expanded) {
                setHotnessForExpandedNode(cutoffNode, specialized);
                for (CallTreeNode child : ((InlineCacheNode) specialized).children()) {
                    setHotnessForExpandedNode(cutoffNode, child);
                }
                /* The graph reference and the children moved to the specialized child. */
                expanded.safeDelete();
                return specialized;
            }
            return expanded;
        }

        /**
         * Exact receiver sets. After inlining, a receiver is often a merge of allocations and
         * constants whose exact types are all known, even though its stamp only says the common
         * supertype. Inline-cache cases for other types are then impossible and are removed, and a
         * case that stands for exactly one possible type uses a type check instead of a method
         * check. When every possible type is covered, the fallback call goes too: the cascade ends
         * in an unreachable dead end instead of an indirect call, so the receiver no longer escapes
         * there and the type checks are plain floating instanceof tests that loop optimizations can
         * hoist.
         */
        @Override
        public void refineInlineCacheChildren(InlineCacheNode cache) {
            Invoke invoke = cache.invoke();
            if (invoke == null || !invoke.asNode().isAlive() || invoke.callTarget().arguments().isEmpty() || invoke.getTargetMethod() == null ||
                            !ProfiledInliningProvider.Options.PGOExactReceiverSets.getValue(root.getOptions())) {
                return;
            }
            ValueNode receiver = invoke.callTarget().arguments().get(0);
            if (!(receiver.stamp(NodeView.DEFAULT) instanceof ObjectStamp receiverStamp) || !receiverStamp.nonNull() || receiverStamp.isExactType()) {
                return;
            }
            Set<ResolvedJavaType> types = exactReceiverTypes(receiver);
            if (types == null || types.isEmpty() || types.size() > EXACT_RECEIVER_SET_LIMIT) {
                if (cache.getDebug().isLogEnabled()) {
                    InliningUtil.logInliningDecision(cache.getDebug(), "exact receiver set unknown for %s: receiver %s sources %s", cache, receiver, describeSources(receiver));
                }
                return;
            }
            ResolvedJavaMethod target = invoke.getTargetMethod();
            ResolvedJavaType contextType = invoke.getContextType();
            Map<ResolvedJavaMethod, List<ResolvedJavaType>> typesByMethod = new HashMap<>();
            for (ResolvedJavaType type : types) {
                ResolvedJavaMethod resolved = type.resolveConcreteMethod(target, contextType);
                if (resolved == null) {
                    return;
                }
                typesByMethod.computeIfAbsent(resolved, _ -> new ArrayList<>()).add(type);
            }
            if (cache.parent() == null && !keepsAnyChild(cache, types, typesByMethod)) {
                /*
                 * A cache re-created for the postponed cases of a partly inlined cache has no
                 * parent, so no case can be added for a possible type. Dropping every case would
                 * leave it without a case or a fallback, which the inliner does not allow.
                 */
                return;
            }
            Set<ResolvedJavaType> covered = new HashSet<>();
            GenericNode fallback = null;
            boolean changed = false;
            for (CallTreeNode child : cache.children().snapshot()) {
                if (child instanceof GenericNode generic) {
                    fallback = generic;
                    continue;
                }
                ResolvedJavaType dispatchedType;
                ResolvedJavaMethod dispatchedMethod;
                if (child instanceof SubgraphNode subgraph) {
                    dispatchedType = subgraph.getDispatchedType();
                    dispatchedMethod = subgraph.getDispatchedMethod();
                } else if (child instanceof CutoffNode cutoff) {
                    dispatchedType = cutoff.getDispatchedType();
                    dispatchedMethod = cutoff.targetMethod();
                } else {
                    return;
                }
                if (dispatchedType != null) {
                    if (types.contains(dispatchedType)) {
                        covered.add(dispatchedType);
                    } else {
                        removeInlineCacheChild(cache, child);
                        changed = true;
                    }
                    continue;
                }
                List<ResolvedJavaType> typesOfMethod = typesByMethod.get(dispatchedMethod);
                if (typesOfMethod == null) {
                    removeInlineCacheChild(cache, child);
                    changed = true;
                } else if (typesOfMethod.size() == 1) {
                    /* One possible type for this method: a type check replaces the method check. */
                    ResolvedJavaType type = typesOfMethod.get(0);
                    if (child instanceof SubgraphNode subgraph && !subgraph.isIntrinsic()) {
                        SubgraphNode specialized = subgraph.specializeForReceiverType(type, subgraph.getFrequency());
                        subgraph.replaceAtPredecessor(specialized);
                        subgraph.safeDelete();
                    } else if (child instanceof CutoffNode cutoff && cache.parent() != null) {
                        CallTreeNode replacement = createChild(cache.parent(), invoke, dispatchedMethod, type, type, cutoff.getFrequency());
                        cutoff.replaceAtPredecessor(replacement);
                        cutoff.safeDelete();
                    } else {
                        covered.add(type);
                        continue;
                    }
                    covered.add(type);
                    changed = true;
                } else {
                    covered.addAll(typesOfMethod);
                }
            }
            if (cache.parent() != null) {
                /* A possible type without a case would end in the fallback; give it a case. */
                for (ResolvedJavaType type : types) {
                    if (!covered.contains(type)) {
                        ResolvedJavaMethod method = type.resolveConcreteMethod(target, contextType);
                        cache.addChild(createChild(cache.parent(), invoke, method, type, type, cache.getFrequency() / types.size()));
                        covered.add(type);
                        changed = true;
                    }
                }
            }
            if (fallback != null && covered.containsAll(types)) {
                removeInlineCacheChild(cache, fallback);
                EXACT_RECEIVER_SET_FALLBACKS_REMOVED.incrementAndGet();
                changed = true;
            }
            if (changed) {
                /* Added cases may push the case frequencies above the call's; scale them back. */
                double caseFrequency = 0.0;
                for (CallTreeNode child : cache.children()) {
                    if (!(child instanceof GenericNode)) {
                        caseFrequency += child.getFrequency();
                    }
                }
                double available = cache.getFrequency() * (fallback != null && fallback.isAlive() ? 1.0 - EXTREMELY_SLOW_PATH_PROBABILITY : 1.0);
                if (caseFrequency > available && caseFrequency > 0.0) {
                    for (CallTreeNode child : cache.children()) {
                        if (!(child instanceof GenericNode)) {
                            child.setFrequency(child.getFrequency() * available / caseFrequency);
                        }
                    }
                }
                /*
                 * The profile must name every type a remaining child was created for (the dispatch
                 * code looks children up by their original dispatched type) plus the possible types.
                 */
                Set<ResolvedJavaType> profileTypes = new LinkedHashSet<>(types);
                for (CallTreeNode child : cache.children()) {
                    ResolvedJavaType original = child instanceof SubgraphNode subgraph ? subgraph.getOriginalDispatchedType()
                                    : child instanceof CutoffNode cutoff ? cutoff.getOriginalDispatchedType() : null;
                    if (original != null) {
                        profileTypes.add(original);
                    }
                }
                boolean keepNotRecorded = fallback != null && fallback.isAlive();
                if (cache.getProfile() instanceof JavaMethodProfile oldMethods) {
                    Set<ResolvedJavaMethod> methods = new LinkedHashSet<>(typesByMethod.keySet());
                    for (CallTreeNode child : cache.children()) {
                        ResolvedJavaMethod method = child instanceof SubgraphNode subgraph ? subgraph.getDispatchedMethod() : child instanceof CutoffNode cutoff ? cutoff.targetMethod() : null;
                        if (method != null) {
                            methods.add(method);
                        }
                    }
                    cache.setMethodProfile(exactMethodProfile(methods, oldMethods, keepNotRecorded));
                } else {
                    cache.setReceiverTypeProfile(exactReceiverProfile(profileTypes, cache.getProfile(), keepNotRecorded));
                }
                EXACT_RECEIVER_SET_REFINED.incrementAndGet();
                InliningUtil.logInliningDecision(cache.getDebug(), "exact receiver set %s refined %s to %s", types, cache, cache.children());
            }
        }

        private static JavaMethodProfile exactMethodProfile(Set<ResolvedJavaMethod> methods, JavaMethodProfile old, boolean keepNotRecorded) {
            double notRecorded = keepNotRecorded ? Math.max(old.getNotRecordedProbability(), EXTREMELY_SLOW_PATH_PROBABILITY) : 0.0;
            JavaMethodProfile.ProfiledMethod[] items = new JavaMethodProfile.ProfiledMethod[methods.size()];
            double sum = 0.0;
            int i = 0;
            for (ResolvedJavaMethod method : methods) {
                double probability = EXTREMELY_SLOW_PATH_PROBABILITY;
                for (JavaMethodProfile.ProfiledMethod item : old.getMethods()) {
                    if (item.getMethod().equals(method)) {
                        probability = Math.max(probability, item.getProbability());
                    }
                }
                items[i++] = new JavaMethodProfile.ProfiledMethod(method, probability);
                sum += probability;
            }
            double scale = (1.0 - notRecorded) / sum;
            for (int j = 0; j < items.length; j++) {
                items[j] = new JavaMethodProfile.ProfiledMethod(items[j].getMethod(), items[j].getProbability() * scale);
            }
            Arrays.sort(items);
            return new JavaMethodProfile(notRecorded, items);
        }

        /**
         * A type profile over exactly the possible receiver types, weighted by the old profile where
         * it knows them.
         */
        private static JavaTypeProfile exactReceiverProfile(Set<ResolvedJavaType> types, AbstractJavaProfile<?, ?> old, boolean keepNotRecorded) {
            double notRecorded = 0.0;
            if (keepNotRecorded) {
                notRecorded = Math.max(old == null ? 0.0 : old.getNotRecordedProbability(), EXTREMELY_SLOW_PATH_PROBABILITY);
            }
            JavaTypeProfile.ProfiledType[] items = new JavaTypeProfile.ProfiledType[types.size()];
            double sum = 0.0;
            int i = 0;
            for (ResolvedJavaType type : types) {
                double probability = EXTREMELY_SLOW_PATH_PROBABILITY;
                if (old instanceof JavaTypeProfile oldTypes) {
                    for (JavaTypeProfile.ProfiledType item : oldTypes.getTypes()) {
                        if (item.getType().equals(type)) {
                            probability = Math.max(probability, item.getProbability());
                        }
                    }
                }
                items[i++] = new JavaTypeProfile.ProfiledType(type, probability);
                sum += probability;
            }
            double scale = (1.0 - notRecorded) / sum;
            for (int j = 0; j < items.length; j++) {
                items[j] = new JavaTypeProfile.ProfiledType(items[j].getType(), items[j].getProbability() * scale);
            }
            Arrays.sort(items);
            return new JavaTypeProfile(TriState.FALSE, notRecorded, items);
        }

        private static String describeSources(ValueNode receiver) {
            StringBuilder sb = new StringBuilder();
            Set<ValueNode> seen = new HashSet<>();
            ArrayDeque<ValueNode> work = new ArrayDeque<>();
            work.add(receiver);
            while (!work.isEmpty() && sb.length() < 400) {
                ValueNode value = GraphUtil.unproxify(work.poll());
                if (!seen.add(value)) {
                    continue;
                }
                if (value instanceof ValuePhiNode phi) {
                    for (ValueNode input : phi.values()) {
                        work.add(input);
                    }
                } else {
                    sb.append(value).append(':').append(value.stamp(NodeView.DEFAULT)).append(' ');
                }
            }
            return sb.toString();
        }

        private static void removeInlineCacheChild(InlineCacheNode cache, CallTreeNode child) {
            cache.children().remove(child);
            child.safeRecursiveDelete();
        }

        /**
         * Whether {@link #refineInlineCacheChildren} keeps at least one child of {@code cache}: the
         * fallback, a case for a possible type, or a child it does not know how to refine.
         */
        private static boolean keepsAnyChild(InlineCacheNode cache, Set<ResolvedJavaType> types, Map<ResolvedJavaMethod, List<ResolvedJavaType>> typesByMethod) {
            for (CallTreeNode child : cache.children()) {
                ResolvedJavaType dispatchedType;
                ResolvedJavaMethod dispatchedMethod;
                if (child instanceof SubgraphNode subgraph) {
                    dispatchedType = subgraph.getDispatchedType();
                    dispatchedMethod = subgraph.getDispatchedMethod();
                } else if (child instanceof CutoffNode cutoff) {
                    dispatchedType = cutoff.getDispatchedType();
                    dispatchedMethod = cutoff.targetMethod();
                } else {
                    return true;
                }
                if (dispatchedType != null ? types.contains(dispatchedType) : typesByMethod.containsKey(dispatchedMethod)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * The exact types a value can have, when every source is an allocation, a non-null constant
         * or a value with an exact stamp (through merges and proxies); {@code null} when unknown.
         */
        private static Set<ResolvedJavaType> exactReceiverTypes(ValueNode receiver) {
            Set<ResolvedJavaType> types = new HashSet<>();
            Set<ValueNode> seen = new HashSet<>();
            ArrayDeque<ValueNode> work = new ArrayDeque<>();
            work.add(receiver);
            while (!work.isEmpty()) {
                ValueNode value = work.poll();
                if (!seen.add(value)) {
                    continue;
                }
                if (value.stamp(NodeView.DEFAULT) instanceof ObjectStamp stamp && stamp.nonNull() && stamp.isExactType() && stamp.type() != null) {
                    types.add(stamp.type());
                    continue;
                }
                ValueNode unproxied = GraphUtil.unproxify(value);
                if (unproxied != value) {
                    work.add(unproxied);
                } else if (value instanceof ValuePhiNode phi) {
                    if (phi.valueCount() == 0) {
                        return null;
                    }
                    for (ValueNode input : phi.values()) {
                        work.add(input);
                    }
                } else if (value instanceof NewInstanceNode newInstance) {
                    types.add(newInstance.instanceClass());
                } else if (value instanceof AllocatedObjectNode allocated) {
                    types.add(allocated.getVirtualObject().type());
                } else {
                    return null;
                }
                if (types.size() > EXACT_RECEIVER_SET_LIMIT) {
                    return null;
                }
            }
            return types;
        }

        /**
         * Receiver-type specialization of a direct call (PGO). The callee has a single target, so it
         * would be inlined with no information about the receiver type. When every virtual call on
         * {@code this} inside the callee was profiled with one dominant receiver type {@code T}, the
         * inlined body is wrapped in a type guard: for receivers of type {@code T} the body is
         * inlined with {@code this} of exact type {@code T}, so the inner calls resolve statically
         * and values derived from {@code this} keep exact types (escape analysis can then scalar
         * replace them); every other receiver takes a plain direct call of the callee. The guard
         * replaces one type switch per inner call site, each with its own fallback, by one check at
         * the entry.
         */
        private CallTreeNode specializeForDominantReceiverType(CutoffNode cutoffNode, CallTreeNode expanded) {
            if (profilesLookup == null || !(expanded instanceof SubgraphNode subgraph) || subgraph.isIntrinsic() || !ProfiledInliningProvider.Options.PGOReceiverSpecialization.getValue(root
                            .getOptions())) {
                return expanded;
            }
            Invoke invoke = cutoffNode.invoke();
            ResolvedJavaMethod target = cutoffNode.targetMethod();
            if (cutoffNode.getDispatchedType() != null || target == null || target.isStatic() || invoke == null || invoke.callTarget().arguments().isEmpty()) {
                return expanded;
            }
            if (!(invoke.callTarget() instanceof MethodCallTargetNode callTarget) || !callTarget.invokeKind().isDirect()) {
                return expanded;
            }
            if (invoke.callTarget().arguments().get(0).stamp(NodeView.DEFAULT) instanceof ObjectStamp receiverStamp && receiverStamp.isExactType()) {
                return expanded;
            }
            double minProbability = ProfiledInliningProvider.Options.PGOReceiverSpecializationMinProbability.getValue(root.getOptions());
            ResolvedJavaType dominant = null;
            double probability = 1.0;
            int sitesOnThis = 0;
            for (CallTreeNode child : subgraph.children()) {
                if (!(child instanceof InlineCacheNode inner) || inner.invoke() == null || inner.invoke().callTarget().arguments().isEmpty()) {
                    continue;
                }
                if (!(inner.invoke().callTarget() instanceof SubstrateMethodCallTargetNode innerTarget) || !innerTarget.hasDynamicTypeProfile()) {
                    continue;
                }
                if (!(GraphUtil.unproxify(inner.invoke().callTarget().arguments().get(0)) instanceof ParameterNode receiver) || receiver.index() != 0) {
                    continue;
                }
                sitesOnThis++;
                JavaTypeProfile.ProfiledType best = null;
                for (JavaTypeProfile.ProfiledType type : innerTarget.getTypeProfile().getTypes()) {
                    if (best == null || type.getProbability() > best.getProbability()) {
                        best = type;
                    }
                }
                if (best == null || best.getProbability() < minProbability) {
                    /* `this` is one object: a polymorphic inner site means no type dominates. */
                    RECEIVER_SPECIALIZATION_NO_DOMINANT.incrementAndGet();
                    InliningUtil.logInliningDecision(cutoffNode.getDebug(), "no receiver specialization of %s (%s): %s has no dominant type in %s", cutoffNode, target.format("%h.%n"),
                                    inner.invoke().callTarget().targetMethod().format("%h.%n"), innerTarget.getTypeProfile());
                    return expanded;
                }
                if (dominant == null) {
                    dominant = best.getType();
                } else if (!dominant.equals(best.getType())) {
                    RECEIVER_SPECIALIZATION_DISAGREE.incrementAndGet();
                    return expanded;
                }
                probability = Math.min(probability, best.getProbability());
            }
            if (sitesOnThis == 0) {
                return expanded;
            }
            RECEIVER_SPECIALIZATION_CANDIDATES.incrementAndGet();
            if (dominant.resolveConcreteMethod(target, invoke.getContextType()) == null) {
                return expanded;
            }
            probability = Math.min(probability, 1.0 - EXTREMELY_SLOW_PATH_PROBABILITY);
            JavaTypeProfile profile = new JavaTypeProfile(TriState.FALSE, 1.0 - probability, new JavaTypeProfile.ProfiledType[]{new JavaTypeProfile.ProfiledType(dominant, probability)});
            double frequency = cutoffNode.getFrequency();
            InlineCacheNode cache = add(new InlineCacheNode(cutoffNode.compilationRootPosition(), invoke, target, frequency, profile, 1));
            cache.markDirectCallSpecialization();
            /*
             * Reuse the parsed body and its children as the guarded case, so the specialization
             * costs no extra expansion round. The other receivers call the method.
             */
            cache.addChild(subgraph.specializeForReceiverType(dominant, probability * frequency));
            cache.addChild(createGenericChild(cutoffNode.parent(), invoke, (1.0 - probability) * frequency, DontInlineCause.Indirect));
            RECEIVER_SPECIALIZATION_CREATED.incrementAndGet();
            InliningUtil.logInliningDecision(cutoffNode.getDebug(), "receiver specialization of %s (%s) for %s (%d sites on this, p=%.6f): %s with children %s at %s", cutoffNode, target.format(
                            "%h.%n"),
                            dominant.toJavaName(false), sitesOnThis, probability, cache, cache.children(), cutoffNode.compilationRootPosition());
            return cache;
        }

        @Override
        protected EnumSet<BenefitKind> estimateBenefits(Invoke invoke) {
            /*
             * Adjust the estimation of benefits to SubstrateVM specifics, i.e., take into account
             * Static Analysis, Word types, etc.
             */
            return BenefitKind.estimateBenefit(invoke, () -> {
                Assumptions assumptions = invoke.asNode().graph().getAssumptions();
                ResolvedJavaMethod targetMethod = invoke.callTarget().targetMethod();

                /* Thanks to Static Analysis, we can trust interface types. */
                Stamp[] parameterStamps = StampFactory.createParameterStamps(assumptions, targetMethod, true);

                /* Replace stamp of parameters that are Word types. */
                for (int i = 0; i < parameterStamps.length; i++) {
                    if (parameterStamps[i].getStackKind().isObject()) {
                        ObjectStamp parameterStamp = (ObjectStamp) parameterStamps[i];
                        if (parameterStamp.type() != null && ((HostedType) parameterStamp.type()).isWordType()) {
                            assert BenefitKind.getArgumentStamps(invoke)[i].getStackKind() == SubstrateTarget.getWordKind();
                            parameterStamps[i] = SubstrateTarget.getWordStamp();
                        }
                    }
                }
                return parameterStamps;
            });
        }
    }

    /**
     * We cannot devirtualize invokes guarded with {@link CFunctionPrologueNode} since it's an
     * invariant of {@link CFunctionPrologueNode} that there is nothing but a single {@link Invoke}
     * between {@link CFunctionPrologueNode} and
     * {@link com.oracle.svm.core.nodes.CFunctionEpilogueNode}.
     *
     * @see CFunctionPrologueNode
     */
    private static boolean isCFunctionInvoke(Invoke invoke) {
        return invoke.predecessor() instanceof CFunctionPrologueNode;
    }
}
