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
package com.oracle.svm.hosted.code;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.oracle.svm.core.pgo.IprofFormat;
import com.oracle.svm.hosted.meta.HostedMethod;
import com.oracle.svm.hosted.pgo.PGOProfileUseFeature;
import com.oracle.svm.hosted.pgo.profiles.PGOProfilesLookup;
import com.oracle.svm.hosted.pgo.profiles.SamplingHotness;
import com.oracle.svm.shared.option.HostedOptionKey;
import com.oracle.svm.shared.singletons.traits.BuiltinTraits.BuildtimeAccessOnly;
import com.oracle.svm.shared.singletons.traits.BuiltinTraits.NoLayeredCallbacks;
import com.oracle.svm.shared.singletons.traits.SingletonTraits;

import jdk.graal.compiler.code.CompilationResult;
import jdk.graal.compiler.options.Option;
import jdk.vm.ci.code.site.Call;
import jdk.vm.ci.code.site.Infopoint;

/**
 * Profile-guided method order for the code section.
 * <p>
 * Methods are visited in decreasing hotness and, as each hot method is placed (one that was
 * sampled, or one with a call count above zero when the profile has no samples), its small direct
 * callees that are not yet placed are laid out immediately after it. Hotness is sampled self time
 * when the profile carries samples, with call counts as a tie-breaker, and call counts alone
 * otherwise. The callee pull is what keeps the hot working set dense: intrinsic stubs, runtime
 * helpers, and library methods that were inlined in the training build have no call-count record
 * of their own and would otherwise be sorted among a million cold methods by name, each costing an
 * instruction page of its own.
 */
@SingletonTraits(access = BuildtimeAccessOnly.class, layeredCallbacks = NoLayeredCallbacks.class)
final class ProfileGuidedCodeSectionLayouter implements CodeSectionLayouter {

    /** Options of the profile-guided code layout. */
    static final class Options {
        /** The size limit of {@link #directCallees}; 0 orders methods by hotness alone. */
        @Option(help = "Place a hot method's direct callees next to it in the code section when they are at most this many bytes. 0 disables callee affinity.")//
        static final HostedOptionKey<Integer> PGOLayoutAffinityMaxCalleeBytes = new HostedOptionKey<>(4096);
    }

    /** The sort key of {@link SortByMethodNameCodeSectionLayouter}, for ties and cold methods. */
    private static String name(HostedMethod method) {
        return method.format("%H.%n(%P):%R");
    }

    @Override
    public List<HostedMethod> layout(Map<HostedMethod, CompilationResult> compilations) {
        PGOProfilesLookup profiles = PGOProfileUseFeature.codeLayoutProfiles();
        SamplingHotness hotness = PGOProfileUseFeature.codeLayoutHotness();
        boolean callCounts = profiles != null && profiles.profileCategoryRecorded(IprofFormat.CALL_COUNT_PROFILES);
        if (hotness == null && !callCounts) {
            return compilations.keySet().stream().sorted(Comparator.comparing(ProfileGuidedCodeSectionLayouter::name)).toList();
        }

        Comparator<HostedMethod> byHotness = Comparator.comparingDouble((HostedMethod method) -> hotness == null ? 0.0 : hotness.selfTimeShare(method)).reversed()
                        .thenComparing(Comparator.comparingLong((HostedMethod method) -> callCounts ? profiles.getCallCountOrZero(method) : 0L).reversed())
                        .thenComparing(ProfileGuidedCodeSectionLayouter::name);
        List<HostedMethod> ordered = compilations.keySet().stream().sorted(byHotness).toList();

        int maxCalleeBytes = Options.PGOLayoutAffinityMaxCalleeBytes.getValue();
        if (maxCalleeBytes <= 0) {
            return ordered;
        }

        Map<HostedMethod, List<HostedMethod>> callees = new HashMap<>();
        for (Map.Entry<HostedMethod, CompilationResult> entry : compilations.entrySet()) {
            callees.put(entry.getKey(), directCallees(entry.getValue(), compilations, maxCalleeBytes));
        }

        List<HostedMethod> result = new ArrayList<>(ordered.size());
        Set<HostedMethod> placed = new HashSet<>();
        for (HostedMethod method : ordered) {
            if (!placed.add(method)) {
                continue;
            }
            result.add(method);
            boolean hot = hotness != null ? hotness.isSampled(method) : profiles.getCallCountOrZero(method) > 0;
            if (hot) {
                for (HostedMethod callee : callees.get(method)) {
                    if (placed.add(callee)) {
                        result.add(callee);
                    }
                }
            }
        }
        return result;
    }

    /** Direct call targets of one compilation, in code order, that are small enough to pull in. */
    private static List<HostedMethod> directCallees(CompilationResult result, Map<HostedMethod, CompilationResult> compilations, int maxCalleeBytes) {
        List<HostedMethod> targets = new ArrayList<>();
        Set<HostedMethod> seen = new HashSet<>();
        for (Infopoint infopoint : result.getInfopoints()) {
            if (infopoint instanceof Call call && call.direct && call.target instanceof HostedMethod target && seen.add(target)) {
                CompilationResult calleeResult = compilations.get(target);
                if (calleeResult != null && calleeResult.getTargetCodeSize() <= maxCalleeBytes) {
                    targets.add(target);
                }
            }
        }
        return targets;
    }
}
