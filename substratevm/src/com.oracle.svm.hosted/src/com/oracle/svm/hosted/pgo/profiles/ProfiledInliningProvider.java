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
package com.oracle.svm.hosted.pgo.profiles;

import java.util.Map;

import com.oracle.svm.core.util.UserError;
import com.oracle.svm.hosted.cai.PrefixTree;
import com.oracle.svm.hosted.meta.HostedMethod;
import com.oracle.svm.hosted.meta.HostedUniverse;
import com.oracle.svm.hosted.phases.priorityinline.SubstrateInliningProvider;
import com.oracle.svm.shared.option.HostedOptionKey;

import jdk.graal.compiler.graph.NodeSourcePosition;
import jdk.graal.compiler.nodes.CallTargetNode;
import jdk.graal.compiler.nodes.Invoke;
import jdk.graal.compiler.options.Option;
import jdk.graal.compiler.options.OptionValues;
import jdk.graal.compiler.phases.common.priorityinline.nodes.CallTreeNode;
import jdk.vm.ci.meta.JavaMethodProfile;
import jdk.vm.ci.meta.ResolvedJavaMethod;

/**
 * Inlining provider that gives the priority inliner profile-derived hotness: a calling-context
 * cursor per compilation root (so cutoff hotness and sampled callee profiles become available), the
 * larger of the sampled and the call-count hotness of a call edge, and the hot bonus while
 * inlining, which is otherwise zero.
 */
public final class ProfiledInliningProvider extends SubstrateInliningProvider {

    /** The non-idle samples of the profile, for {@code PGOHotContextMinProfileSamples}. */
    private final long profileSamples;
    /** The resolved profile, for its {@code callCountProfiles}. */
    private final IprofProfilesLookup profilesLookup;
    /** The method-rooted tree of sampled stacks. */
    private final SamplingHotness hotness;

    /** Options that turn single uses of the profile in the inliner and later phases on or off. */
    public static final class Options {
        // @formatter:off
        /** What {@link ProfiledInliningProvider#hotRootInliningBoost} returns. */
        @Option(help = "Multiplier on the inlining benefit of every call inside a compilation root whose inclusive sample share reaches " +
                       "PGOHotRootMinInclusiveShare. The inliner values a call by its frequency relative to the root's entry, so a method " +
                       "that runs millions of times gets the same budget as one that runs once; the profile tells them apart. 1 disables.")//
        static final HostedOptionKey<Double> PGOHotRootInliningBoost = new HostedOptionKey<>(4.0, option -> requireAtLeastOne(option));

        /** What {@link ProfiledInliningProvider#hotRootMaxColdCodeDiscount} returns. */
        @Option(help = "In hot roots, price an expanded callee by its hot blocks only: its inlining benefit is multiplied by (fixed nodes) / (fixed nodes in blocks " +
                       "whose relative frequency reaches PGOHotRootColdBlockFrequency), up to this value. A large callee whose profile shows a small hot path " +
                       "(exception and slow paths cold) is then inlined like the small method it is at run time. 1 disables.")//
        static final HostedOptionKey<Double> PGOHotRootMaxColdCodeDiscount = new HostedOptionKey<>(4.0, option -> requireAtLeastOne(option));

        /** What {@link ProfiledInliningProvider#hotRootColdBlockFrequency} returns. */
        @Option(help = "Relative block frequency below which a callee block counts as cold for PGOHotRootMaxColdCodeDiscount.")//
        static final HostedOptionKey<Double> PGOHotRootColdBlockFrequency = new HostedOptionKey<>(0.01, option -> requireProbability(option));

        /** What {@link ProfiledInliningProvider#hotRootMinInclusiveShare} returns. */
        @Option(help = "Fraction of all non-idle samples that must have a compilation root on their stack for PGOHotRootInliningBoost to apply. The share is of the whole " +
                       "profile, so a profile merged from several workloads divides every root's share by roughly the number of workloads; the default is low " +
                       "enough that a root carrying 1% of one of five merged workloads still qualifies.")//
        public static final HostedOptionKey<Double> PGOHotRootMinInclusiveShare = new HostedOptionKey<>(0.002, option -> requireProbability(option));

        /** The factor that {@link ProfiledInliningProvider#hotBonusWhileInlining} returns. */
        @Option(help = "Local-benefit multiplier, scaled by root-relative hotness, applied to hot call-tree nodes while inlining. 0 disables.")//
        static final HostedOptionKey<Integer> PGOHotInliningBonus = new HostedOptionKey<>(1);

        /** A threshold of {@link ProfiledInliningProvider#isHotContext}. */
        @Option(help = "Minimum total samples in the profile before discrete hot-context bonuses may apply.")//
        static final HostedOptionKey<Integer> PGOHotContextMinProfileSamples = new HostedOptionKey<>(5000, option -> requirePositive(option));

        /** A threshold of {@link ProfiledInliningProvider#isHotContext}. */
        @Option(help = "Minimum samples below a call context before discrete hot-context bonuses may apply.")//
        static final HostedOptionKey<Integer> PGOHotContextMinSamples = new HostedOptionKey<>(50, option -> requirePositive(option));

        /** A threshold of {@link ProfiledInliningProvider#isHotContext}. */
        @Option(help = "Minimum share of a compilation root's samples below a call context before discrete hot-context bonuses may apply.")//
        static final HostedOptionKey<Double> PGOHotContextMinRatio = new HostedOptionKey<>(0.05, option -> requireProbability(option));

        /** What {@link ProfiledInliningProvider#selectedContextBonusWhileInlining} returns. */
        @Option(help = "Additional local-benefit multiplier for call contexts meeting all hot-context thresholds. 0 disables.")//
        static final HostedOptionKey<Integer> PGOHotContextInliningBonus = new HostedOptionKey<>(1, option -> requireNonNegative(option));

        /** What {@link ProfiledInliningProvider#selectedContextBonusWhileExpanding} returns. */
        @Option(help = "Additional expansion priority for call contexts meeting all hot-context thresholds. 0 disables.")//
        static final HostedOptionKey<Integer> PGOHotContextExpansionBonus = new HostedOptionKey<>(5, option -> requireNonNegative(option));

        /** What {@link ProfiledInliningProvider#shouldApplyProfilesWhileExpanding} returns. */
        @Option(help = "Apply profiles to callee graphs expanded under a hot compilation root.")//
        static final HostedOptionKey<Boolean> PGOApplyProfilesWhileExpanding = new HostedOptionKey<>(true);

        /** Off to measure sampled callee profiles, as the pgo-receivers-disabled gate run does. */
        @Option(help = "Let the inliner prefer sampled callee method profiles at indirect calls over analysis type profiles.")//
        static final HostedOptionKey<Boolean> PGOSamplingMethodProfiles = new HostedOptionKey<>(true);

        /** Read by {@code SubstratePriorityInliningPhase} when it expands a direct call. */
        @Option(help = "Guard an inlined direct call on the one receiver type its body was profiled with, so calls on `this` inside the body resolve statically.")//
        public static final HostedOptionKey<Boolean> PGOReceiverSpecialization = new HostedOptionKey<>(true);

        /** Read by {@code SubstratePriorityInliningPhase} with {@link #PGOReceiverSpecialization}. */
        @Option(help = "Smallest receiver-type probability, at every virtual call on `this` inside the callee, that lets PGOReceiverSpecialization guard the inlined body.")//
        public static final HostedOptionKey<Double> PGOReceiverSpecializationMinProbability = new HostedOptionKey<>(0.999);

        /** What {@link ProfiledInliningProvider#escapeInlineMaxCodeSize} returns. */
        @Option(help = "Largest callee bytecode size that is forced inline when the call takes a caller allocation as argument inside a loop, so the allocation can stay out of memory. 0 disables.")//
        static final HostedOptionKey<Integer> PGOEscapeInlineMaxCodeSize = new HostedOptionKey<>(200);

        /** Read by {@code SubstratePriorityInliningPhase} when it refines an inline cache. */
        @Option(help = "Drop inline-cache cases a receiver cannot have when all its sources are allocations or constants with known types, and the fallback call when every possible type is covered.")//
        public static final HostedOptionKey<Boolean> PGOExactReceiverSets = new HostedOptionKey<>(true);

        /** Read by {@link SamplingHotness#providerFor}. */
        @Option(help = "Report sampled compilation roots as hot callers (enables hot-callee devirtualization and hot-caller profile application).")//
        static final HostedOptionKey<Boolean> PGOSamplingHotCaller = new HostedOptionKey<>(true);

        /** Read by {@link SamplingHotness#providerFor}. */
        @Option(help = "Expose sampled self time to the compiler, which gives hot code larger budgets in duplication and loop unrolling.")//
        static final HostedOptionKey<Boolean> PGOSamplingSelfTime = new HostedOptionKey<>(true);
        // @formatter:on

        private static void requirePositive(HostedOptionKey<Integer> option) {
            if (option.getValue() <= 0) {
                throw UserError.invalidOptionValue(option, option.getValue(), "The value must be greater than zero.");
            }
        }

        private static void requireNonNegative(HostedOptionKey<Integer> option) {
            if (option.getValue() < 0) {
                throw UserError.invalidOptionValue(option, option.getValue(), "The value must not be negative.");
            }
        }

        private static void requireAtLeastOne(HostedOptionKey<Double> option) {
            double value = option.getValue();
            if (!(value >= 1.0)) {
                throw UserError.invalidOptionValue(option, value, "The value must be at least one.");
            }
        }

        private static void requireProbability(HostedOptionKey<Double> option) {
            double value = option.getValue();
            if (!(value > 0.0 && value <= 1.0)) {
                throw UserError.invalidOptionValue(option, value, "The value must be greater than zero and at most one.");
            }
        }
    }

    /**
     * Creates a provider that finds each compilation root's calling contexts in {@code hotness}.
     *
     * @param universe the image's universe
     * @param hotness the method-rooted tree of sampled stacks
     * @param profilesLookup the resolved profile, for its {@code callCountProfiles}
     */
    public ProfiledInliningProvider(HostedUniverse universe, SamplingHotness hotness, IprofProfilesLookup profilesLookup) {
        super(universe, hotness::cursorFor);
        this.hotness = hotness;
        this.profileSamples = hotness.nonIdleSamples();
        this.profilesLookup = profilesLookup;
    }

    @Override
    public double hotRootInliningBoost(OptionValues options) {
        return Options.PGOHotRootInliningBoost.getValue(options);
    }

    @Override
    public double hotRootColdBlockFrequency(OptionValues options) {
        return Options.PGOHotRootColdBlockFrequency.getValue(options);
    }

    @Override
    public double hotRootMaxColdCodeDiscount(OptionValues options) {
        return Options.PGOHotRootMaxColdCodeDiscount.getValue(options);
    }

    @Override
    public double hotRootMinInclusiveShare(OptionValues options) {
        return Options.PGOHotRootMinInclusiveShare.getValue(options);
    }

    @Override
    public int escapeInlineMaxCodeSize(OptionValues options) {
        return Options.PGOEscapeInlineMaxCodeSize.getValue(options);
    }

    @Override
    public double inclusiveTimeShare(HostedMethod root) {
        return hotness.inclusiveTimeShare(root);
    }

    /**
     * The sampled context, with its hotness raised to the share of the root's calls that went
     * through this call edge according to {@code callCountProfiles}, capped at 1, if that share is
     * larger.
     */
    @Override
    public SamplingContext compilationRootSamplingContext(HostedMethod compilationRoot, NodeSourcePosition callPosition, ResolvedJavaMethod dispatchedMethod) {
        SamplingContext sampled = super.compilationRootSamplingContext(compilationRoot, callPosition, dispatchedMethod);
        long rootCalls = profilesLookup.getCallCountOrZero(compilationRoot);
        if (rootCalls <= 0) {
            return sampled;
        }
        NodeSourcePosition edgeContext = new NodeSourcePosition(callPosition, dispatchedMethod, 0);
        long edgeCalls = profilesLookup.getContextCallCount(edgeContext).orElse(0L);
        if (edgeCalls <= 0) {
            return sampled;
        }
        double callRatio = Math.min(1.0, (double) edgeCalls / rootCalls);
        return new SamplingContext(Math.max(sampled.rootRelativeHotness(), callRatio), sampled.samples());
    }

    /** The sampled callee profile, or {@code null} if {@code PGOSamplingMethodProfiles} is off. */
    @Override
    public JavaMethodProfile samplingMethodProfiles(Map<CallTreeNode, PrefixTree.Cursor> nodeContextMap, HostedMethod root, CallTreeNode caller, CallTargetNode callee) {
        return Options.PGOSamplingMethodProfiles.getValue() ? super.samplingMethodProfiles(nodeContextMap, root, caller, callee) : null;
    }

    /** The sampled callee profile, or {@code null} if {@code PGOSamplingMethodProfiles} is off. */
    @Override
    public JavaMethodProfile samplingMethodProfiles(HostedMethod compilationRoot, Invoke calleeInvoke) {
        return Options.PGOSamplingMethodProfiles.getValue() ? super.samplingMethodProfiles(compilationRoot, calleeInvoke) : null;
    }

    /** On by default; the base provider never applies profiles while expanding. */
    @Override
    protected boolean shouldApplyProfilesWhileExpanding(OptionValues options) {
        return Options.PGOApplyProfilesWhileExpanding.getValue(options);
    }

    /** {@code PGOHotInliningBonus}; the base provider gives no bonus. */
    @Override
    protected int hotBonusWhileInlining(OptionValues options) {
        return Options.PGOHotInliningBonus.getValue(options);
    }

    @Override
    protected int selectedContextBonusWhileInlining(OptionValues options, double contextHotness, long samples) {
        return selectedContextBonus(options, contextHotness, samples, Options.PGOHotContextInliningBonus);
    }

    @Override
    protected int selectedContextBonusWhileExpanding(OptionValues options, double contextHotness, long samples) {
        return selectedContextBonus(options, contextHotness, samples, Options.PGOHotContextExpansionBonus);
    }

    private int selectedContextBonus(OptionValues options, double contextHotness, long samples, HostedOptionKey<Integer> bonusOption) {
        int bonus = bonusOption.getValue(options);
        return bonus != 0 && isHotContext(contextHotness, samples, profileSamples, Options.PGOHotContextMinRatio.getValue(options), Options.PGOHotContextMinSamples.getValue(options),
                        Options.PGOHotContextMinProfileSamples.getValue(options)) ? bonus : 0;
    }

    static boolean isHotContext(double hotness, long samples, long profileSamples, double minimumRatio, int minimumSamples, int minimumProfileSamples) {
        return hotness > 0.0 && samples > 0 && profileSamples >= minimumProfileSamples && samples >= minimumSamples && hotness >= minimumRatio;
    }
}
