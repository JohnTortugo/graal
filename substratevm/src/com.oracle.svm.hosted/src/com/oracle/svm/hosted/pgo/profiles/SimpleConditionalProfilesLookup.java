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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import com.oracle.graal.pointsto.meta.AnalysisMethod;
import com.oracle.graal.pointsto.meta.AnalysisType;
import com.oracle.svm.hosted.meta.HostedMethod;
import com.oracle.svm.shared.singletons.traits.BuiltinTraits.BuildtimeAccessOnly;
import com.oracle.svm.shared.singletons.traits.BuiltinTraits.DisallowLayered;
import com.oracle.svm.shared.singletons.traits.BuiltinTraits.NoLayeredCallbacks;
import com.oracle.svm.shared.singletons.traits.SingletonTraits;

import jdk.graal.compiler.nodes.ProfileData.ProfileSource;
import jdk.vm.ci.code.BytecodePosition;
import jdk.vm.ci.meta.JavaType;

/**
 * A conditional-only {@link PGOProfilesLookup}. It answers
 * {@link #getConditionalProfile(BytecodePosition)} from an immutable table keyed by a canonical
 * calling context, and reports exactly one recorded category: {@code conditionalProfiles}. Every
 * other profile category (call counts, virtual invokes, instanceof, monitors, sampling, image
 * heap) is intentionally absent so this consumer applies one and only one optimization -- rewriting
 * conditional branch probabilities.
 *
 * <p>
 * The table is built by {@code PGOConditionalProfilesFeature} once the {@code HostedUniverse} is
 * available; see {@link ConditionalProfileContextResolver} for the resolution algorithm. Instances
 * are immutable after construction.
 */
@SingletonTraits(access = BuildtimeAccessOnly.class, layeredCallbacks = NoLayeredCallbacks.class, other = DisallowLayered.class)
public final class SimpleConditionalProfilesLookup implements PGOProfilesLookup {

    /** The profile category this consumer supports; must match the phase's constant. */
    public static final String CONDITIONAL_PROFILES_CATEGORY = "conditionalProfiles";

    /**
     * A single canonical frame of a calling context: a JVM-descriptor method identity and a
     * bytecode index. Using a descriptor string (rather than a resolved method reference) lets the
     * same key be produced from the profile method table and from a query
     * {@link BytecodePosition}, independent of {@code NodeSourcePosition} identity or its
     * {@code sourceLanguagePosition}.
     */
    public record FrameKey(String methodDescriptor, int bci) {
    }

    private final Map<List<FrameKey>, long[]> conditionalData;
    private final ConditionalProfileDiagnostics diagnostics;
    private volatile boolean cleared;

    /**
     * Number of {@link #getConditionalProfile(BytecodePosition)} calls that matched an installed
     * context ({@code hits}) and that did not ({@code misses}). These count how the profile is
     * actually applied while compiling image graphs, independent of the phase-level
     * {@code PGOApplyProfilesPhase.ProfileQuality} counters (which are only updated when the
     * PGOPrintProfileQuality option is set). They are always maintained so a deterministic
     * afterCompilation summary can be produced without enabling any option.
     */
    private final AtomicLong hitCount = new AtomicLong();
    private final AtomicLong missCount = new AtomicLong();
    /** Exact profile contexts that matched at least one compiler query. */
    private final Set<List<FrameKey>> matchedContexts = ConcurrentHashMap.newKeySet();
    /** Best successor-record coverage observed for every matched context. */
    private final Map<List<FrameKey>, ApplicationCoverage> applicationCoverage = new ConcurrentHashMap<>();

    private record ApplicationCoverage(int profiledSuccessors, int appliedSuccessors) {
    }

    public SimpleConditionalProfilesLookup(Map<List<FrameKey>, long[]> conditionalData, ConditionalProfileDiagnostics diagnostics) {
        this.conditionalData = Map.copyOf(conditionalData);
        this.diagnostics = diagnostics;
    }

    /** Deterministic quality/summary information gathered while building the table. */
    public ConditionalProfileDiagnostics diagnostics() {
        return diagnostics;
    }

    /** Returns deterministic context-key samples for profile compatibility diagnostics. */
    public List<String> contextKeySamples(int limit) {
        return conditionalData.keySet().stream().map(Object::toString).sorted().limit(limit).toList();
    }


    /** Returns the exact canonical key used for a profile query. */
    public String canonicalKeyForDiagnostics(BytecodePosition position) {
        return canonicalize(position).toString();
    }
    /** Number of applied-lookup queries that matched an installed conditional context. */
    public long hitCount() {
        return hitCount.get();
    }

    /** Number of distinct profile contexts used by at least one compiler query. */
    public int matchedContextCount() {
        return matchedContexts.size();
    }

    /** Number of resolved conditional contexts available to the compiler. */
    public int availableContextCount() {
        return conditionalData.size();
    }

    /** Number of resolved contexts whose profile matched every successor at least once. */
    public long fullyAppliedContextCount() {
        return applicationCoverage.values().stream().filter(coverage -> coverage.profiledSuccessors() > 0 && coverage.appliedSuccessors() >= coverage.profiledSuccessors()).count();
    }

    /** Number of resolved contexts for which only some profiled successors matched. */
    public long partiallyAppliedContextCount() {
        return applicationCoverage.values().stream().filter(coverage -> coverage.appliedSuccessors() > 0 && coverage.appliedSuccessors() < coverage.profiledSuccessors()).count();
    }

    /** Number of matched contexts for which no profiled successor was applied. */
    public long unappliedMatchedContextCount() {
        return matchedContexts.size() - fullyAppliedContextCount() - partiallyAppliedContextCount();
    }

    /** Number of resolved contexts never requested by this stage's compiler queries. */
    public long unusedResolvedContextCount() {
        return conditionalData.size() - matchedContexts.size();
    }

    /** Number of applied-lookup queries that did not match any installed conditional context. */
    public long missCount() {
        return missCount.get();
    }

    @Override
    public boolean profileCategoryRecorded(String category) {
        return CONDITIONAL_PROFILES_CATEGORY.equals(category) && !cleared && !conditionalData.isEmpty();
    }

    @Override
    public Optional<ProfiledValue<long[]>> getConditionalProfile(BytecodePosition callingContext) {
        if (cleared || callingContext == null) {
            return Optional.empty();
        }
        List<FrameKey> key = canonicalize(callingContext);
        long[] records = conditionalData.get(key);
        if (records == null) {
            missCount.incrementAndGet();
            return Optional.empty();
        }
        matchedContexts.add(key);
        hitCount.incrementAndGet();
        return Optional.of(new ProfiledValue<>(ProfileSource.PROFILED, records));
    }

    @Override
    public void recordConditionalProfileApplication(BytecodePosition callingContext, int profiledSuccessors, int appliedSuccessors) {
        if (cleared || callingContext == null) {
            return;
        }
        List<FrameKey> key = canonicalize(callingContext);
        if (!conditionalData.containsKey(key)) {
            return;
        }
        applicationCoverage.compute(key, (_, previous) -> {
            if (previous == null) {
                return new ApplicationCoverage(profiledSuccessors, appliedSuccessors);
            }
            return new ApplicationCoverage(Math.max(previous.profiledSuccessors(), profiledSuccessors), Math.max(previous.appliedSuccessors(), appliedSuccessors));
        });
    }

    /**
     * Builds the canonical, innermost-first {@link FrameKey} chain for a query context. This mirrors
     * how the profile stores {@code methodId:bci} frames and how
     * {@code PGOApplyProfilesPhase.createPointContext} assembles the query (leaf frame first,
     * followed by its callers).
     */
    static List<FrameKey> canonicalize(BytecodePosition position) {
        List<FrameKey> frames = new ArrayList<>();
        for (BytecodePosition current = position; current != null; current = current.getCaller()) {
            frames.add(new FrameKey(ConditionalProfileContextResolver.methodDescriptor(current.getMethod()), current.getBCI()));
        }
        return frames;
    }

    @Override
    public void clear() {
        cleared = true;
    }

    // --- Out-of-scope categories: intentionally empty for a conditional-only consumer -----------

    @Override
    public Optional<ProfiledValue<Long>> getCallCountProfile(HostedMethod method) {
        return Optional.empty();
    }

    @Override
    public long getCallCountOrZero(HostedMethod method) {
        return 0L;
    }

    @Override
    public boolean isExecuted(HostedMethod method) {
        return false;
    }

    @Override
    public Optional<Map<AnalysisType, Long>> getVirtualInvokeProfile(BytecodePosition callingContext) {
        return Optional.empty();
    }

    @Override
    public Optional<Map<AnalysisMethod, Long>> getVirtualInvokeMethodProfile(BytecodePosition callingContext) {
        return Optional.empty();
    }

    @Override
    public Optional<ProfiledValue<Long>> getTotalConditionalProfileValue(HostedMethod method) {
        return Optional.empty();
    }

    @Override
    public long getTotalConditionalProfileValueOrZero(HostedMethod method) {
        return 0L;
    }

    @Override
    public Optional<Map<AnalysisType, Long>> getMonitorProfiles() {
        return Optional.empty();
    }

    @Override
    public Optional<Map<JavaType, Long>> getInstanceofProfile(BytecodePosition callingContext) {
        return Optional.empty();
    }
}
