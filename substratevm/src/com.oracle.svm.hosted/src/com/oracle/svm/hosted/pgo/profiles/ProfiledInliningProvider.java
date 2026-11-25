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

    private final IprofProfilesLookup profilesLookup;

    public static final class Options {
        // @formatter:off
        @Option(help = "Local-benefit multiplier, scaled by root-relative hotness, applied to hot call-tree nodes while inlining. 0 disables.")//
        public static final HostedOptionKey<Integer> PGOHotInliningBonus = new HostedOptionKey<>(1);

        @Option(help = "Apply profiles to callee graphs expanded under a hot compilation root.")//
        public static final HostedOptionKey<Boolean> PGOApplyProfilesWhileExpanding = new HostedOptionKey<>(true);

        @Option(help = "Let the inliner prefer sampled callee method profiles at indirect calls over analysis type profiles.")//
        public static final HostedOptionKey<Boolean> PGOSamplingMethodProfiles = new HostedOptionKey<>(true);

        @Option(help = "Report sampled compilation roots as hot callers (enables hot-callee devirtualization and hot-caller profile application).")//
        public static final HostedOptionKey<Boolean> PGOSamplingHotCaller = new HostedOptionKey<>(true);

        @Option(help = "Expose sampled self time to the compiler, which gives hot code larger budgets in duplication and loop unrolling.")//
        public static final HostedOptionKey<Boolean> PGOSamplingSelfTime = new HostedOptionKey<>(true);
        // @formatter:on
    }

    public ProfiledInliningProvider(HostedUniverse universe, SamplingHotness hotness, IprofProfilesLookup profilesLookup) {
        super(universe, hotness::cursorFor);
        this.profilesLookup = profilesLookup;
    }

    @Override
    public double compilationRootRelativeHotness(HostedMethod compilationRoot, NodeSourcePosition callPosition, ResolvedJavaMethod dispatchedMethod) {
        double sampled = super.compilationRootRelativeHotness(compilationRoot, callPosition, dispatchedMethod);
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
        return Math.max(sampled, callRatio);
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
    protected int hotBonusWhileInlining(OptionValues options) {
        return Options.PGOHotInliningBonus.getValue(options);
    }
}
