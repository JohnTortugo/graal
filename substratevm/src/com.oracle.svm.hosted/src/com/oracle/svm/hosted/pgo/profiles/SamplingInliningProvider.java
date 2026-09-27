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

import jdk.graal.compiler.nodes.CallTargetNode;
import jdk.graal.compiler.nodes.Invoke;
import jdk.graal.compiler.options.Option;
import jdk.graal.compiler.options.OptionValues;
import jdk.graal.compiler.phases.common.priorityinline.nodes.CallTreeNode;
import jdk.vm.ci.meta.JavaMethodProfile;

/**
 * Inlining provider that gives the priority inliner sampling-derived hotness: a calling-context
 * cursor per compilation root (so cutoff hotness and sampled callee profiles become available) and
 * the hotness bonuses that are otherwise zero.
 */
public final class SamplingInliningProvider extends SubstrateInliningProvider {

    public static final class Options {
        // @formatter:off
        @Option(help = "Priority bonus, scaled by root-relative hotness, added to hot cutoff nodes while expanding the call tree. 0 disables.")//
        public static final HostedOptionKey<Integer> PGOHotExpansionBonus = new HostedOptionKey<>(0);

        @Option(help = "Local-benefit multiplier, scaled by root-relative hotness, applied to hot call-tree nodes while inlining. 0 disables.")//
        public static final HostedOptionKey<Integer> PGOHotInliningBonus = new HostedOptionKey<>(0);

        @Option(help = "Apply profiles to callee graphs expanded under a hot compilation root.")//
        public static final HostedOptionKey<Boolean> PGOApplyProfilesWhileExpanding = new HostedOptionKey<>(true);

        @Option(help = "Let the inliner prefer sampled callee method profiles at indirect calls over analysis type profiles.")//
        public static final HostedOptionKey<Boolean> PGOSamplingMethodProfiles = new HostedOptionKey<>(true);

        @Option(help = "Report sampled compilation roots as hot callers (enables hot-callee devirtualization and hot-caller profile application).")//
        public static final HostedOptionKey<Boolean> PGOSamplingHotCaller = new HostedOptionKey<>(true);

        @Option(help = "Expose sampled self time to the compiler (enables hot-code duplication budgets).")//
        public static final HostedOptionKey<Boolean> PGOSamplingSelfTime = new HostedOptionKey<>(true);
        // @formatter:on
    }

    public SamplingInliningProvider(HostedUniverse universe, SamplingHotness hotness) {
        super(universe, hotness::cursorFor);
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
}
