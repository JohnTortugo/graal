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
 * Inlining provider that gives the priority inliner sampling-derived hotness: a calling-context
 * cursor per compilation root (so cutoff hotness and sampled callee profiles become available) and
 * the hotness bonuses that are otherwise zero.
 */
public final class SamplingInliningProvider extends SubstrateInliningProvider {

    private final long profileSamples;
    private final SimpleConditionalProfilesLookup profiles;

    public static final class Options {
        // @formatter:off
        @Option(help = "Priority bonus, scaled by root-relative hotness, added to hot cutoff nodes while expanding the call tree. 0 disables.")//
        public static final HostedOptionKey<Integer> PGOHotExpansionBonus = new HostedOptionKey<>(0);

        @Option(help = "Local-benefit multiplier, scaled by root-relative hotness, applied to hot call-tree nodes while inlining. 0 disables.")//
        public static final HostedOptionKey<Integer> PGOHotInliningBonus = new HostedOptionKey<>(1);

        @Option(help = "Minimum total samples in the profile before discrete hot-context bonuses may apply.")//
        public static final HostedOptionKey<Integer> PGOHotContextMinProfileSamples = new HostedOptionKey<>(5000, option -> requirePositive(option));

        @Option(help = "Minimum samples below a call context before discrete hot-context bonuses may apply.")//
        public static final HostedOptionKey<Integer> PGOHotContextMinSamples = new HostedOptionKey<>(50, option -> requirePositive(option));

        @Option(help = "Minimum share of a compilation root's samples below a call context before discrete hot-context bonuses may apply.")//
        public static final HostedOptionKey<Double> PGOHotContextMinRatio = new HostedOptionKey<>(0.05, option -> requireProbability(option));

        @Option(help = "Additional local-benefit multiplier for call contexts meeting all hot-context thresholds. 0 disables.")//
        public static final HostedOptionKey<Integer> PGOHotContextInliningBonus = new HostedOptionKey<>(1, option -> requireNonNegative(option));

        @Option(help = "Additional expansion priority for call contexts meeting all hot-context thresholds. 0 disables.")//
        public static final HostedOptionKey<Integer> PGOHotContextExpansionBonus = new HostedOptionKey<>(5, option -> requireNonNegative(option));

        @Option(help = "Apply profiles to callee graphs expanded under a hot compilation root.")//
        public static final HostedOptionKey<Boolean> PGOApplyProfilesWhileExpanding = new HostedOptionKey<>(true);

        @Option(help = "Let the inliner prefer sampled callee method profiles at indirect calls over analysis type profiles.")//
        public static final HostedOptionKey<Boolean> PGOSamplingMethodProfiles = new HostedOptionKey<>(true);

        @Option(help = "Report sampled compilation roots as hot callers (enables hot-callee devirtualization and hot-caller profile application).")//
        public static final HostedOptionKey<Boolean> PGOSamplingHotCaller = new HostedOptionKey<>(true);

        @Option(help = "Expose sampled self time to the compiler (enables hot-code duplication budgets).")//
        public static final HostedOptionKey<Boolean> PGOSamplingSelfTime = new HostedOptionKey<>(true);

        @Option(help = "Largest callee bytecode size that is force-inlined purely because the profile shows the call edge runs often, so leaf calls can be absorbed into hot callers. " +
                        "Measured neutral on its own (see PGO documentation), so it is disabled by default; 0 disables.")//
        public static final HostedOptionKey<Integer> PGOHotLeafMaxCodeSize = new HostedOptionKey<>(0, option -> requireNonNegative(option));

        @Option(help = "Minimum measured root-relative hotness a call edge needs before hot-leaf absorption applies.")//
        public static final HostedOptionKey<Double> PGOHotLeafMinHotness = new HostedOptionKey<>(0.1, option -> requireProbability(option));

        @Option(help = "Minimum profile-corrected call-site frequency, in executions per root invocation, before hot-leaf absorption applies.")//
        public static final HostedOptionKey<Double> PGOHotLeafMinFrequency = new HostedOptionKey<>(1.0);

        @Option(help = "Restrict hot-leaf absorption to compilation roots that appear in the sampling profile.")//
        public static final HostedOptionKey<Boolean> PGOHotLeafSampledRootsOnly = new HostedOptionKey<>(false);
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

        private static void requireProbability(HostedOptionKey<Double> option) {
            double value = option.getValue();
            if (!(value > 0.0 && value <= 1.0)) {
                throw UserError.invalidOptionValue(option, value, "The value must be greater than zero and at most one.");
            }
        }
    }

    public SamplingInliningProvider(HostedUniverse universe, SamplingHotness hotness, SimpleConditionalProfilesLookup profiles) {
        super(universe, hotness::cursorFor);
        this.profileSamples = hotness.totalSamples();
        this.profiles = profiles;
    }

    @Override
    public SamplingContext compilationRootSamplingContext(HostedMethod compilationRoot, NodeSourcePosition callPosition, ResolvedJavaMethod dispatchedMethod) {
        SamplingContext sampled = super.compilationRootSamplingContext(compilationRoot, callPosition, dispatchedMethod);
        long rootCalls = profiles.getCallCountOrZero(compilationRoot);
        if (rootCalls <= 0) {
            return sampled;
        }
        NodeSourcePosition edgeContext = new NodeSourcePosition(callPosition, dispatchedMethod, 0);
        long edgeCalls = profiles.getContextCallCount(edgeContext).orElse(0L);
        if (edgeCalls <= 0) {
            return sampled;
        }
        double callRatio = Math.min(1.0, (double) edgeCalls / rootCalls);
        return new SamplingContext(Math.max(sampled.rootRelativeHotness(), callRatio), sampled.samples());
    }

    @Override
    public JavaMethodProfile samplingMethodProfiles(Map<CallTreeNode, PrefixTree.Cursor> nodeContextMap, HostedMethod root, CallTreeNode caller, CallTargetNode callee) {
        return Options.PGOSamplingMethodProfiles.getValue() ? super.samplingMethodProfiles(nodeContextMap, root, caller, callee) : null;
    }

    @Override
    public JavaMethodProfile samplingMethodProfiles(HostedMethod compilationRoot, Invoke calleeInvoke) {
        return Options.PGOSamplingMethodProfiles.getValue() ? super.samplingMethodProfiles(compilationRoot, calleeInvoke) : null;
    }

    @Override
    protected boolean shouldApplyProfilesWhileExpanding(OptionValues options) {
        return Options.PGOApplyProfilesWhileExpanding.getValue(options);
    }

    @Override
    protected int hotBonusWhileExpanding(OptionValues options) {
        return Options.PGOHotExpansionBonus.getValue(options);
    }

    @Override
    protected int hotBonusWhileInlining(OptionValues options) {
        return Options.PGOHotInliningBonus.getValue(options);
    }

    @Override
    protected int selectedContextBonusWhileInlining(OptionValues options, double hotness, long samples) {
        return selectedContextBonus(options, hotness, samples, Options.PGOHotContextInliningBonus);
    }

    @Override
    protected int selectedContextBonusWhileExpanding(OptionValues options, double hotness, long samples) {
        return selectedContextBonus(options, hotness, samples, Options.PGOHotContextExpansionBonus);
    }

    @Override
    protected int hotLeafMaxCodeSize(OptionValues options) {
        return Options.PGOHotLeafMaxCodeSize.getValue(options);
    }

    @Override
    protected double hotLeafMinHotness(OptionValues options) {
        return Options.PGOHotLeafMinHotness.getValue(options);
    }

    @Override
    protected double hotLeafMinFrequency(OptionValues options) {
        return Options.PGOHotLeafMinFrequency.getValue(options);
    }

    @Override
    protected boolean hotLeafRequiresSampledRoot(OptionValues options) {
        return Options.PGOHotLeafSampledRootsOnly.getValue(options);
    }

    private int selectedContextBonus(OptionValues options, double hotness, long samples, HostedOptionKey<Integer> bonusOption) {
        int bonus = bonusOption.getValue(options);
        return bonus != 0 && isHotContext(hotness, samples, profileSamples, Options.PGOHotContextMinRatio.getValue(options), Options.PGOHotContextMinSamples.getValue(options),
                        Options.PGOHotContextMinProfileSamples.getValue(options)) ? bonus : 0;
    }

    static boolean isHotContext(double hotness, long samples, long profileSamples, double minimumRatio, int minimumSamples, int minimumProfileSamples) {
        return hotness > 0.0 && samples > 0 && profileSamples >= minimumProfileSamples && samples >= minimumSamples && hotness >= minimumRatio;
    }
}
