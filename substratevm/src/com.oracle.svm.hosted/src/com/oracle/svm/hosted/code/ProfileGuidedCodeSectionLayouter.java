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

import java.util.Comparator;
import java.util.List;
import java.util.Map;

import com.oracle.svm.hosted.meta.HostedMethod;
import com.oracle.svm.hosted.pgo.PGOConditionalProfilesFeature;
import com.oracle.svm.hosted.pgo.profiles.PGOProfilesLookup;
import com.oracle.svm.hosted.pgo.profiles.SamplingHotness;
import com.oracle.svm.hosted.pgo.profiles.SimpleConditionalProfilesLookup;
import com.oracle.svm.shared.singletons.traits.BuiltinTraits.BuildtimeAccessOnly;
import com.oracle.svm.shared.singletons.traits.BuiltinTraits.NoLayeredCallbacks;
import com.oracle.svm.shared.singletons.traits.SingletonTraits;

import jdk.graal.compiler.code.CompilationResult;

/**
 * Profile-guided method order for the code section.
 *
 * Methods are ordered by decreasing hotness. Hotness is sampled self time when the profile carries
 * samples, with call counts as a tie-breaker, and call counts alone otherwise.
 */
@SingletonTraits(access = BuildtimeAccessOnly.class, layeredCallbacks = NoLayeredCallbacks.class)
public final class ProfileGuidedCodeSectionLayouter implements CodeSectionLayouter {

    private static String name(HostedMethod method) {
        return method.format("%H.%n(%P):%R");
    }

    @Override
    public List<HostedMethod> layout(Map<HostedMethod, CompilationResult> compilations) {
        PGOProfilesLookup profiles = PGOConditionalProfilesFeature.codeLayoutProfiles();
        SamplingHotness hotness = PGOConditionalProfilesFeature.codeLayoutHotness();
        boolean callCounts = profiles != null && profiles.profileCategoryRecorded(SimpleConditionalProfilesLookup.CALL_COUNT_PROFILES_CATEGORY);
        if (hotness == null && !callCounts) {
            return compilations.keySet().stream().sorted(Comparator.comparing(ProfileGuidedCodeSectionLayouter::name)).toList();
        }

        Comparator<HostedMethod> byHotness = Comparator.comparingDouble((HostedMethod method) -> hotness == null ? 0.0 : hotness.selfTimeShare(method)).reversed()
                        .thenComparing(Comparator.comparingLong((HostedMethod method) -> callCounts ? profiles.getCallCountOrZero(method) : 0L).reversed())
                        .thenComparing(ProfileGuidedCodeSectionLayouter::name);
        List<HostedMethod> ordered = compilations.keySet().stream().sorted(byHotness).toList();
        if (PGOConditionalProfilesFeature.Options.PGOTestDiagnostics.getValue()) {
            reportLayout(compilations, ordered, hotness != null, callCounts);
        }
        return ordered;
    }

    /** Reports the actual final code-section order rather than only the ranking decision. */
    private static void reportLayout(Map<HostedMethod, CompilationResult> compilations, List<HostedMethod> result, boolean samples, boolean callCounts) {
        List<HostedMethod> alphabetical = compilations.keySet().stream().sorted(Comparator.comparing(ProfileGuidedCodeSectionLayouter::name)).toList();
        int moved = 0;
        for (int i = 0; i < result.size(); i++) {
            if (result.get(i) != alphabetical.get(i)) {
                moved++;
            }
        }
        // Checkstyle: stop
        System.out.printf("[PGO:layout] methods=%d, samples=%s, call-counts=%s, positions changed=%d%n",
                        result.size(), samples, callCounts, moved);
        // Checkstyle: resume
    }
}
