/*
 * Copyright (c) 2026, Oracle and/or its affiliates. All rights reserved.
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
package jdk.graal.compiler.loop.phases;

import static jdk.graal.compiler.core.common.calc.Condition.EQ;
import static jdk.graal.compiler.core.common.calc.Condition.NE;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.graalvm.collections.EconomicMap;
import org.graalvm.collections.EconomicSet;

import jdk.graal.compiler.core.common.calc.Condition;
import jdk.graal.compiler.core.common.cfg.AbstractControlFlowGraph;
import jdk.graal.compiler.core.common.type.IntegerStamp;
import jdk.graal.compiler.core.common.type.ObjectStamp;
import jdk.graal.compiler.core.common.type.Stamp;
import jdk.graal.compiler.core.common.type.StampFactory;
import jdk.graal.compiler.debug.CounterKey;
import jdk.graal.compiler.debug.DebugContext;
import jdk.graal.compiler.graph.Node;
import jdk.graal.compiler.nodes.AbstractBeginNode;
import jdk.graal.compiler.nodes.BeginNode;
import jdk.graal.compiler.nodes.EndNode;
import jdk.graal.compiler.nodes.FixedNode;
import jdk.graal.compiler.nodes.FrameState;
import jdk.graal.compiler.nodes.GraphState;
import jdk.graal.compiler.nodes.IfNode;
import jdk.graal.compiler.nodes.LogicConstantNode;
import jdk.graal.compiler.nodes.LogicNegationNode;
import jdk.graal.compiler.nodes.LogicNode;
import jdk.graal.compiler.nodes.LoopBeginNode;
import jdk.graal.compiler.nodes.MergeNode;
import jdk.graal.compiler.nodes.NamedLocationIdentity;
import jdk.graal.compiler.nodes.NodeView;
import jdk.graal.compiler.nodes.PhiNode;
import jdk.graal.compiler.nodes.PiNode;
import jdk.graal.compiler.nodes.ProfileData;
import jdk.graal.compiler.nodes.ProfileData.BranchProbabilityData;
import jdk.graal.compiler.nodes.ShortCircuitOrNode;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.calc.BinaryArithmeticNode;
import jdk.graal.compiler.nodes.calc.CompareNode;
import jdk.graal.compiler.nodes.calc.IntegerBelowNode;
import jdk.graal.compiler.nodes.calc.IntegerConvertNode;
import jdk.graal.compiler.nodes.calc.IntegerEqualsNode;
import jdk.graal.compiler.nodes.calc.IsNullNode;
import jdk.graal.compiler.nodes.cfg.ControlFlowGraph;
import jdk.graal.compiler.nodes.cfg.HIRBlock;
import jdk.graal.compiler.nodes.extended.BranchProbabilityNode;
import jdk.graal.compiler.nodes.loop.CountedLoopInfo;
import jdk.graal.compiler.nodes.loop.InductionVariable;
import jdk.graal.compiler.nodes.loop.InductionVariableHelper;
import jdk.graal.compiler.nodes.loop.Loop;
import jdk.graal.compiler.nodes.loop.LoopFragmentWhole;
import jdk.graal.compiler.nodes.loop.LoopsData;
import jdk.graal.compiler.nodes.memory.FloatingReadNode;
import jdk.graal.compiler.nodes.memory.address.AddressNode;
import jdk.graal.compiler.nodes.memory.address.OffsetAddressNode;
import jdk.graal.compiler.nodes.util.GraphUtil;
import jdk.graal.compiler.options.Option;
import jdk.graal.compiler.options.OptionKey;
import jdk.graal.compiler.options.OptionType;
import jdk.graal.compiler.phases.common.CanonicalizerPhase;
import jdk.graal.compiler.phases.common.PostRunCanonicalizationPhase;
import jdk.graal.compiler.phases.common.util.LoopUtility;
import jdk.graal.compiler.phases.tiers.MidTierContext;
import jdk.graal.compiler.replacements.nodes.arithmetic.IntegerAddExactOverflowNode;
import jdk.graal.compiler.replacements.nodes.arithmetic.IntegerExactOverflowNode;
import jdk.graal.compiler.replacements.nodes.arithmetic.IntegerMulExactOverflowNode;
import jdk.graal.compiler.replacements.nodes.arithmetic.IntegerSubExactOverflowNode;
import jdk.vm.ci.meta.JavaKind;

/**
 * Removes array bounds checks from hot counted loops without deoptimization, by versioning the
 * loop. For a loop
 *
 * <pre>
 * for (int i = init; i &lt; limit; i++) {
 *     ... if (!(f(i) |&lt;| bound)) throw ...   // bounds check of a[f(i)]
 * }
 * </pre>
 *
 * where {@code bound} is loop invariant and {@code f(i)} is an induction variable, the check is
 * true in every iteration exactly when it is true for the first and the last value of {@code f},
 * provided the induction arithmetic does not overflow. The phase computes that condition once
 * before the loop and branches to a copy of the loop without the checks when it holds, and to the
 * unchanged loop otherwise:
 *
 * <pre>
 * if (f(init) |&lt;| bound &amp;&amp; f(last) |&lt;| bound &amp;&amp; no overflow) {
 *     for (...) { ... a[f(i)] without check ... }
 * } else {
 *     for (...) { ... original ... }
 * }
 * </pre>
 *
 * {@link LoopPredicationPhase} and {@link SpeculativeGuardMovementPhase} achieve the same with
 * floating guards that deoptimize when the hoisted condition fails. This phase is for compilations
 * that cannot deoptimize, such as ahead-of-time compiled code, where bounds checks are fixed
 * {@link IfNode}s whose failing branch leaves the loop to throw. It pays for the removed checks with
 * a second copy of the loop and is therefore restricted to small loops with a high profiled trip
 * count.
 */
public class LoopRangeCheckVersioningPhase extends PostRunCanonicalizationPhase<MidTierContext> {

    public static class Options {
        // @formatter:off
        @Option(help = "Version hot counted loops so that array bounds checks that depend only on the loop bounds are tested once " +
                       "before the loop instead of in every iteration. Needs no deoptimization, so it also applies to ahead-of-time compiled code.", type = OptionType.Expert)
        public static final OptionKey<Boolean> LoopRangeCheckVersioning = new OptionKey<>(true);
        @Option(help = "Maximum number of nodes of a loop that is versioned to remove its range checks.", type = OptionType.Expert)
        public static final OptionKey<Integer> LoopRangeCheckVersioningMaxLoopSize = new OptionKey<>(400);
        @Option(help = "Minimum local loop frequency (iterations per entry) of a loop that is versioned to remove its range checks.", type = OptionType.Expert)
        public static final OptionKey<Double> LoopRangeCheckVersioningMinFrequency = new OptionKey<>(8.0);
        @Option(help = "Only version loops whose frequency was profiled on the compiled program. Frequencies from other sources (injected defaults, adopted or " +
                       "inferred profiles) routinely claim billions of iterations and would version every loop with a bounds check.", type = OptionType.Expert)
        public static final OptionKey<Boolean> LoopRangeCheckVersioningRequireProfile = new OptionKey<>(true);
        // @formatter:on
    }

    private static final CounterKey VERSIONED_LOOPS = DebugContext.counter("LoopRangeCheckVersioning_Loops");
    private static final CounterKey REMOVED_CHECKS = DebugContext.counter("LoopRangeCheckVersioning_Checks");

    public LoopRangeCheckVersioningPhase(CanonicalizerPhase canonicalizer) {
        super(canonicalizer);
    }

    @Override
    public Optional<NotApplicable> notApplicableTo(GraphState graphState) {
        return NotApplicable.ifAny(
                        super.notApplicableTo(graphState),
                        NotApplicable.unlessRunAfter(this, GraphState.StageFlag.HIGH_TIER_LOWERING, graphState),
                        NotApplicable.unlessRunBefore(this, GraphState.StageFlag.STRIP_MINING, graphState),
                        NotApplicable.unlessRunBefore(this, GraphState.StageFlag.EXPAND_LOGIC, graphState));
    }

    @Override
    public boolean shouldApply(StructuredGraph graph) {
        return graph.hasLoops();
    }

    @Override
    protected void run(StructuredGraph graph, MidTierContext context) {
        EconomicSet<LoopBeginNode> versioned = EconomicSet.create();
        boolean changed;
        do {
            changed = false;
            LoopsData data = context.getLoopsDataProvider().getLoopsData(graph);
            for (Loop loop : data.innerFirst()) {
                if (!versioned.contains(loop.loopBegin()) && tryVersion(loop, data, versioned)) {
                    changed = true;
                    // the loop structure changed, recompute the loops data before continuing
                    break;
                }
            }
        } while (changed);
    }

    /**
     * A bounds check {@code if (iv |<| bound)} inside the loop body whose failing branch leaves the
     * loop. When the bound is an array length that is only read behind a null check inside the loop,
     * {@code arrayBase} is the (loop invariant) array whose length has to be re-read before the
     * loop; otherwise it is {@code null} and {@code bound} itself is available before the loop.
     */
    private record RangeCheck(IfNode ifNode, InductionVariable iv, ValueNode bound, ValueNode arrayBase) {
    }

    private boolean tryVersion(Loop loop, LoopsData data, EconomicSet<LoopBeginNode> versioned) {
        StructuredGraph graph = loop.loopBegin().graph();
        if (!loop.getCFGLoop().getChildren().isEmpty() || LoopUtility.excludeLoopFromOptimizer(loop) || !loop.canDuplicateLoop()) {
            return false;
        }
        if (!loop.detectCounted()) {
            return false;
        }
        CountedLoopInfo counted = loop.counted();
        if (counted.isInverted() || counted.isUnsignedCheck()) {
            return false;
        }
        InductionVariable counter = counted.getLimitCheckedIV();
        if (((IntegerStamp) counter.valueNode().stamp(NodeView.DEFAULT)).getBits() != 32) {
            return false;
        }
        Condition limitCondition = ((CompareNode) counted.getLimitTest().condition()).condition().asCondition();
        if ((limitCondition == NE || limitCondition == EQ) && !(counter.isConstantStride() && Loop.absStrideIsOne(counter))) {
            return false;
        }
        if (loop.size() > Options.LoopRangeCheckVersioningMaxLoopSize.getValue(graph.getOptions())) {
            return false;
        }
        // the second loop copy is only worth its code when the loop is known to be hot
        if (Options.LoopRangeCheckVersioningRequireProfile.getValue(graph.getOptions()) && loop.localFrequencySource() != ProfileData.ProfileSource.PROFILED) {
            return false;
        }
        if (loop.localLoopFrequency() < Options.LoopRangeCheckVersioningMinFrequency.getValue(graph.getOptions())) {
            return false;
        }

        List<RangeCheck> checks = findRangeChecks(loop, data.getCFG());
        if (checks.isEmpty()) {
            return false;
        }

        List<ValueNode> nullableBases = new ArrayList<>();
        for (RangeCheck check : checks) {
            if (check.arrayBase() != null && !nullableBases.contains(check.arrayBase())) {
                nullableBases.add(check.arrayBase());
            }
        }
        FrameState stateBeforeLoop = null;
        if (!nullableBases.isEmpty()) {
            // the slow loop then has two entries that need to merge in front of it
            stateBeforeLoop = stateBeforeLoop(loop.loopBegin());
            if (stateBeforeLoop == null) {
                return false;
            }
        }

        graph.getDebug().dump(DebugContext.DETAILED_LEVEL, graph, "Before range check versioning of %s", loop);
        boolean done = version(loop, counted, checks, nullableBases, stateBeforeLoop, versioned);
        graph.getDebug().dump(DebugContext.DETAILED_LEVEL, graph, "After range check versioning of %s", loop);
        return done;
    }

    /**
     * The frame state in front of the loop: the loop header state with every loop phi replaced by
     * its entry value. Returns {@code null} if the header state refers to loop phis in places that
     * cannot be rewritten this way (virtual object states or outer frames).
     */
    private static FrameState stateBeforeLoop(LoopBeginNode loopBegin) {
        FrameState header = loopBegin.stateAfter();
        if (header == null) {
            return null;
        }
        for (FrameState outer = header.outerFrameState(); outer != null; outer = outer.outerFrameState()) {
            for (Node input : outer.inputs()) {
                if (isPhiOf(input, loopBegin)) {
                    return null;
                }
            }
        }
        for (int i = 0; i < header.virtualObjectMappingCount(); i++) {
            for (Node input : header.virtualObjectMappingAt(i).inputs()) {
                if (isPhiOf(input, loopBegin)) {
                    return null;
                }
            }
        }
        EndNode forwardEnd = loopBegin.forwardEnd();
        return loopBegin.graph().add(header.duplicate((index, value) -> isPhiOf(value, loopBegin) ? ((PhiNode) value).valueAt(forwardEnd) : value));
    }

    private static boolean isPhiOf(Node node, LoopBeginNode loopBegin) {
        return node instanceof PhiNode phi && phi.merge() == loopBegin;
    }

    /**
     * Collects the {@link IfNode}s in the loop body that test an induction variable against a loop
     * invariant, non-negative bound and whose passing branch is extremely likely, which is the shape
     * of an explicit array bounds check (its failing branch throws). Replacing such a check by its
     * passing branch is correct whenever the entry condition built from it holds, whatever the
     * failing branch does; the probability only selects the checks worth a second loop copy.
     */
    private static List<RangeCheck> findRangeChecks(Loop loop, ControlFlowGraph cfg) {
        List<RangeCheck> checks = new ArrayList<>();
        CountedLoopInfo counted = loop.counted();
        HIRBlock bodyBlock = cfg.getNodeToBlock().get(counted.getBody());
        for (IfNode ifNode : loop.whole().nodes().filter(IfNode.class)) {
            if (ifNode == counted.getLimitTest() || !(ifNode.condition() instanceof IntegerBelowNode below)) {
                continue;
            }
            HIRBlock ifBlock = cfg.getNodeToBlock().get(ifNode);
            if (ifBlock == null || !AbstractControlFlowGraph.dominates(bodyBlock, ifBlock)) {
                // only checks in the body are evaluated for the body values of the counter
                continue;
            }
            if (ifNode.getTrueSuccessorProbability() < BranchProbabilityNode.VERY_FAST_PATH_PROBABILITY) {
                // bounds checks are parsed with an extremely likely passing branch
                continue;
            }
            ValueNode bound = below.getY();
            if (!(bound.stamp(NodeView.DEFAULT) instanceof IntegerStamp boundStamp) || !boundStamp.isPositive() || boundStamp.getBits() != 32) {
                continue;
            }
            ValueNode arrayBase = null;
            if (!loop.isOutsideLoop(bound)) {
                arrayBase = invariantArrayOfLength(loop, bound);
                if (arrayBase == null) {
                    continue;
                }
            }
            InductionVariable iv = loop.getInductionVariables().get(below.getX());
            if (iv == null) {
                continue;
            }
            checks.add(new RangeCheck(ifNode, iv, bound, arrayBase));
        }
        return checks;
    }

    /**
     * If {@code bound} is the length of a loop invariant array that is pinned inside the loop only
     * by the guard of the array's null check, returns that array; the length can then be re-read in
     * front of the loop behind a hoisted null check.
     */
    private static ValueNode invariantArrayOfLength(Loop loop, ValueNode bound) {
        if (bound instanceof FloatingReadNode read && read.getLocationIdentity().equals(NamedLocationIdentity.ARRAY_LENGTH_LOCATION) &&
                        read.getAddress() instanceof OffsetAddressNode address && address.getOffset().isConstant()) {
            ValueNode array = GraphUtil.skipPi(address.getBase());
            if (loop.isOutsideLoop(array) && array.stamp(NodeView.DEFAULT) instanceof ObjectStamp) {
                return array;
            }
        }
        return null;
    }

    /**
     * Builds the condition under which at least one of the checks may fail in some iteration, or
     * the induction arithmetic the condition relies on may overflow. Its negation is the entry
     * condition of the check-free loop copy.
     */
    private static LogicNode buildFailureCondition(Loop loop, CountedLoopInfo counted, List<RangeCheck> checks, EconomicMap<ValueNode, ValueNode> hoistedBounds) {
        StructuredGraph graph = loop.loopBegin().graph();
        Stamp longStamp = StampFactory.forKind(JavaKind.Long);
        if (!counted.counterNeverOverflows()) {
            // without deoptimization there is no overflow guard, and trip counts of a loop whose
            // counter may wrap cannot be reasoned about
            return null;
        }
        List<LogicNode> failures = new ArrayList<>();

        InductionVariable limitCheckedIV = counted.getLimitCheckedIV();
        InductionVariable bodyIV = counted.getBodyIVEqualsLimitCheckedIV() ? limitCheckedIV : InductionVariableHelper.previousIteration(limitCheckedIV);
        ValueNode maxTripCount = counted.maxTripCountNode(true);
        for (RangeCheck check : checks) {
            InductionVariable.Endpoints endpoints = check.iv().computeEndpoints(true, maxTripCount, longStamp, bodyIV, limitCheckedIV);
            // any overflow in the derived induction arithmetic invalidates the endpoints
            for (LogicNode overflow : endpoints.overflowConditions()) {
                LogicNode plain = plainCondition(graph, overflow);
                if (plain == null) {
                    return null;
                }
                failures.add(plain);
            }
            /*
             * Without overflow the induction variable moves monotonically from its initial value to
             * its extremum, so it stays within [0, bound) exactly when both endpoints do. Both
             * endpoints are compared in 64 bits: a negative endpoint is a huge unsigned value and
             * fails the test just like the 32-bit check it stands for.
             */
            ValueNode bound = check.arrayBase() == null ? check.bound() : hoistedBounds.get(check.arrayBase());
            ValueNode bound64 = IntegerConvertNode.convert(bound, longStamp, true, graph, NodeView.DEFAULT);
            ValueNode init64 = IntegerConvertNode.convert(endpoints.init(), longStamp, false, graph, NodeView.DEFAULT);
            ValueNode extremum64 = IntegerConvertNode.convert(endpoints.extremum(), longStamp, false, graph, NodeView.DEFAULT);
            LogicNode initInBounds = graph.addOrUniqueWithInputs(IntegerBelowNode.create(init64, bound64, NodeView.DEFAULT));
            LogicNode extremumInBounds = graph.addOrUniqueWithInputs(IntegerBelowNode.create(extremum64, bound64, NodeView.DEFAULT));
            failures.add(graph.addOrUniqueWithInputs(LogicNegationNode.create(initInBounds)));
            failures.add(graph.addOrUniqueWithInputs(LogicNegationNode.create(extremumInBounds)));
        }
        LogicNode anyFailure = null;
        for (LogicNode failure : failures) {
            if (failure.isContradiction()) {
                continue;
            }
            if (anyFailure == null) {
                anyFailure = failure;
            } else {
                anyFailure = graph.addOrUniqueWithInputs(ShortCircuitOrNode.create(anyFailure, false, failure, false, BranchProbabilityNode.NOT_LIKELY_PROFILE));
            }
        }
        return anyFailure == null ? LogicConstantNode.contradiction(graph) : anyFailure;
    }

    /**
     * Exact-overflow conditions may only feed guards or ifs directly. Since the failure condition is
     * a disjunction evaluated by a single if, such a condition is rewritten into an ordinary
     * comparison: the operation is performed in 64 bits and overflows exactly when the result does
     * not survive a round trip through the original width. Returns {@code null} for widths that
     * cannot be widened.
     */
    private static LogicNode plainCondition(StructuredGraph graph, LogicNode condition) {
        if (!(condition instanceof IntegerExactOverflowNode exact)) {
            return condition;
        }
        IntegerStamp narrowStamp = (IntegerStamp) exact.getX().stamp(NodeView.DEFAULT);
        if (narrowStamp.getBits() > 32) {
            return null;
        }
        Stamp longStamp = StampFactory.forKind(JavaKind.Long);
        ValueNode x = IntegerConvertNode.convert(exact.getX(), longStamp, false, graph, NodeView.DEFAULT);
        ValueNode y = IntegerConvertNode.convert(exact.getY(), longStamp, false, graph, NodeView.DEFAULT);
        ValueNode wide;
        if (exact instanceof IntegerAddExactOverflowNode) {
            wide = BinaryArithmeticNode.add(graph, x, y, NodeView.DEFAULT);
        } else if (exact instanceof IntegerSubExactOverflowNode) {
            wide = BinaryArithmeticNode.sub(graph, x, y, NodeView.DEFAULT);
        } else if (exact instanceof IntegerMulExactOverflowNode) {
            wide = BinaryArithmeticNode.mul(graph, x, y, NodeView.DEFAULT);
        } else {
            return null;
        }
        ValueNode narrowed = IntegerConvertNode.convert(wide, narrowStamp.unrestricted(), false, graph, NodeView.DEFAULT);
        ValueNode roundTrip = IntegerConvertNode.convert(narrowed, longStamp, false, graph, NodeView.DEFAULT);
        LogicNode fits = graph.addOrUniqueWithInputs(IntegerEqualsNode.create(wide, roundTrip, NodeView.DEFAULT));
        return graph.addOrUniqueWithInputs(LogicNegationNode.create(fits));
    }

    /**
     * Duplicates the loop, enters the duplicate when a check may fail, and removes the checks from
     * the original loop, which becomes the fast copy. If some bounds are array lengths that are
     * only available behind a null check inside the loop, a null check of those arrays is placed in
     * front of the version test, the lengths are re-read behind it, and a null array also enters
     * the duplicate (which throws exactly like the original loop did).
     *
     * <pre>
     *     if (a == null || b == null) goto slow;      // only with nullable bases
     *     if (anyCheckMayFail) goto slow;
     *     fast: original loop without the checks
     *     slow: duplicate of the original loop
     * </pre>
     */
    private static boolean version(Loop loop, CountedLoopInfo counted, List<RangeCheck> checks, List<ValueNode> nullableBases, FrameState stateBeforeLoop, EconomicSet<LoopBeginNode> versioned) {
        StructuredGraph graph = loop.loopBegin().graph();
        LoopFragmentWhole fastLoop = loop.whole();
        FixedNode entry = fastLoop.entryPoint();

        // the guarded length reads must exist before the version test is built
        AbstractBeginNode nonNullBegin = null;
        LogicNode anyNull = null;
        EconomicMap<ValueNode, ValueNode> hoistedBounds = EconomicMap.create();
        if (!nullableBases.isEmpty()) {
            nonNullBegin = graph.add(new BeginNode());
            for (ValueNode array : nullableBases) {
                LogicNode isNull = graph.addOrUniqueWithInputs(IsNullNode.create(array));
                anyNull = anyNull == null ? isNull : graph.addOrUniqueWithInputs(ShortCircuitOrNode.create(anyNull, false, isNull, false, BranchProbabilityNode.NOT_LIKELY_PROFILE));
                ValueNode nonNullArray = graph.addOrUnique(PiNode.create(array, ((ObjectStamp) array.stamp(NodeView.DEFAULT)).asNonNull(), nonNullBegin));
                FloatingReadNode original = null;
                for (RangeCheck check : checks) {
                    if (check.arrayBase() == array) {
                        original = (FloatingReadNode) check.bound();
                        break;
                    }
                }
                OffsetAddressNode originalAddress = (OffsetAddressNode) original.getAddress();
                AddressNode address = graph.unique(new OffsetAddressNode(nonNullArray, originalAddress.getOffset()));
                hoistedBounds.put(array, FloatingReadNode.create(graph, address, NamedLocationIdentity.ARRAY_LENGTH_LOCATION, null, original.stamp(NodeView.DEFAULT), nonNullBegin, original.getBarrierType()));
            }
        }

        LogicNode anyCheckMayFail = buildFailureCondition(loop, counted, checks, hoistedBounds);
        if (anyCheckMayFail == null || anyCheckMayFail.isTautology()) {
            // the fast loop could never be entered; the unused floating nodes are canonicalized away
            if (nonNullBegin != null) {
                for (ValueNode hoisted : hoistedBounds.getValues()) {
                    GraphUtil.killWithUnusedFloatingInputs(hoisted);
                }
                nonNullBegin.safeDelete();
            }
            return false;
        }

        IfNode nullIf = null;
        if (nonNullBegin != null) {
            nullIf = graph.add(new IfNode(anyNull, (AbstractBeginNode) null, nonNullBegin, BranchProbabilityData.injected(BranchProbabilityNode.VERY_SLOW_PATH_PROBABILITY)));
        }

        LoopFragmentWhole slowLoop = fastLoop.duplicate();
        versioned.add(loop.loopBegin());
        versioned.add(slowLoop.getDuplicatedNode(loop.loopBegin()));

        IfNode versionIf = graph.add(new IfNode(anyCheckMayFail, (AbstractBeginNode) null, (AbstractBeginNode) null, BranchProbabilityData.injected(BranchProbabilityNode.VERY_SLOW_PATH_PROBABILITY)));
        FixedNode first = nullIf == null ? versionIf : nullIf;
        entry.replaceAtPredecessor(first);
        AbstractBeginNode fastBegin = BeginNode.begin(entry);
        versionIf.setFalseSuccessor(fastBegin);
        if (nullIf == null) {
            versionIf.setTrueSuccessor(BeginNode.begin(slowLoop.entryPoint()));
        } else {
            nonNullBegin.setNext(versionIf);
            MergeNode slowMerge = graph.add(new MergeNode());
            EndNode fromNull = graph.add(new EndNode());
            EndNode fromFailure = graph.add(new EndNode());
            nullIf.setTrueSuccessor(BeginNode.begin(fromNull));
            versionIf.setTrueSuccessor(BeginNode.begin(fromFailure));
            slowMerge.addForwardEnd(fromNull);
            slowMerge.addForwardEnd(fromFailure);
            slowMerge.setStateAfter(stateBeforeLoop);
            slowMerge.setNext(slowLoop.entryPoint());
        }
        for (Node created : new Node[]{versionIf, fastBegin, nullIf, nonNullBegin}) {
            if (created != null) {
                created.setNodeSourcePosition(loop.loopBegin().getNodeSourcePosition());
            }
        }

        for (RangeCheck check : checks) {
            // the entry condition guarantees this check in every iteration of the fast copy
            check.ifNode().setCondition(LogicConstantNode.tautology(graph));
            REMOVED_CHECKS.increment(graph.getDebug());
        }
        VERSIONED_LOOPS.increment(graph.getDebug());
        graph.getDebug().log("Versioned %s (size %d, frequency %.1f) for %d range checks, %d nullable arrays", loop.loopBegin(), loop.size(), loop.localLoopFrequency(), checks.size(),
                        nullableBases.size());
        graph.getOptimizationLog().withProperty("checks", checks.size()).report(LoopRangeCheckVersioningPhase.class, "LoopRangeCheckVersioning", loop.loopBegin());
        return true;
    }

    @Override
    public float codeSizeIncrease() {
        return 2.0f;
    }
}
