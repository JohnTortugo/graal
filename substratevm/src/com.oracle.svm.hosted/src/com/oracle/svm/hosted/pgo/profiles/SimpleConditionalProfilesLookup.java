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
import jdk.graal.compiler.graph.NodeSourcePosition;
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
    public static final String CALL_COUNT_PROFILES_CATEGORY = "callCountProfiles";
    public static final String VIRTUAL_INVOKE_PROFILES_CATEGORY = "virtualInvokeProfiles";
    public static final String SAMPLING_PROFILES_CATEGORY = "samplingProfiles";

    /**
     * A single canonical frame of a calling context: a JVM-descriptor method identity and a
     * bytecode index. Using a descriptor string (rather than a resolved method reference) lets the
     * same key be produced from the profile method table and from a query
     * {@link BytecodePosition}, independent of {@code NodeSourcePosition} identity or its
     * {@code sourceLanguagePosition}.
     */
    public record FrameKey(String methodDescriptor, int bci) {
    }

    public record PreciseKey(List<FrameKey> context, ConditionalProfileSiteDescriptor.Stage stage, List<Integer> successorBcis,
                    String conditionKind, int occurrence) {
        public PreciseKey {
            context = List.copyOf(context);
            successorBcis = List.copyOf(successorBcis);
        }

        public static PreciseKey from(List<FrameKey> context, ConditionalProfileSiteDescriptor site) {
            return new PreciseKey(context, site.stage(), site.successorBcis(), site.conditionKind(), site.occurrence());
        }
    }

    public record PreciseProfile(long conditionFingerprint, long[] records) {
    }

    private final Map<List<FrameKey>, long[]> conditionalData;
    private final Map<PreciseKey, PreciseProfile> preciseConditionalData;
    private final Map<List<FrameKey>, List<PreciseKey>> preciseSitesByContext;
    private final Map<NodeSourcePosition, Long> sampleCounts;
    private final Map<String, Long> callCountsByMethod;
    private final Map<List<FrameKey>, Long> callCountsByContext;
    private final ConditionalProfileContextResolver.CallCountDiagnostics callCountDiagnostics;
    private final ConditionalProfileContextResolver.SamplingDiagnostics samplingDiagnostics;
    private final Map<List<FrameKey>, Map<AnalysisType, Long>> virtualInvokeData;
    private final ConditionalProfileContextResolver.VirtualInvokeDiagnostics virtualInvokeDiagnostics;
    private final AtomicLong virtualInvokeHitCount = new AtomicLong();
    private final AtomicLong virtualInvokeMissCount = new AtomicLong();
    private final Set<List<FrameKey>> matchedVirtualInvokeContexts = ConcurrentHashMap.newKeySet();
    /** Receiver-profile hits obtained by dropping outermost frames of the query context. */
    private final AtomicLong virtualInvokeFallbackCount = new AtomicLong();
    private final AtomicLong virtualInvokeFallbackDroppedFrames = new AtomicLong();
    private volatile boolean receiverContextFallback;
    private final AtomicLong impossibleReceiverRecords = new AtomicLong();
    private final AtomicLong impossibleReceiverEvents = new AtomicLong();
    private final boolean preferPrecise;
    private final ConditionalProfileDiagnostics diagnostics;
    private volatile ConditionalProfileFilter filter = ConditionalProfileFilter.NONE;
    private volatile boolean useCallCounts = true;
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
    private final AtomicLong preciseContextMissCount = new AtomicLong();
    private final AtomicLong preciseStageMissCount = new AtomicLong();
    private final AtomicLong preciseSuccessorMissCount = new AtomicLong();
    private final AtomicLong preciseConditionKindMissCount = new AtomicLong();
    private final AtomicLong preciseOccurrenceMissCount = new AtomicLong();
    private final AtomicLong preciseFingerprintDriftCount = new AtomicLong();
    private final AtomicLong preciseUnambiguousFallbackCount = new AtomicLong();
    private final AtomicLong contextFallbackCount = new AtomicLong();
    private final AtomicLong contextFallbackDroppedFrames = new AtomicLong();
    private volatile boolean contextFallback = true;
    private final AtomicLong filteredFewEventsCount = new AtomicLong();
    private final AtomicLong filteredEvenCount = new AtomicLong();
    /** Distinct matched sites withheld by the usefulness filter. */
    private final Set<Object> filteredContexts = ConcurrentHashMap.newKeySet();
    /** Applied sites whose profiled dominant successor contradicts the prior, by log10(events) bucket. */
    private final AtomicLong[] priorFlipsByDecade = newDecadeCounters();
    private final AtomicLong[] priorAgreementsByDecade = newDecadeCounters();
    private final AtomicLong[] injectedPriorFlipsByDecade = newDecadeCounters();

    private static final int DECADES = 12;

    private static AtomicLong[] newDecadeCounters() {
        AtomicLong[] counters = new AtomicLong[DECADES];
        for (int i = 0; i < DECADES; i++) {
            counters[i] = new AtomicLong();
        }
        return counters;
    }
    /** Exact profile contexts that matched at least one compiler query. */
    private final Set<Object> matchedContexts = ConcurrentHashMap.newKeySet();
    /** Best successor-record coverage observed for every matched context. */
    private final Map<Object, ApplicationCoverage> applicationCoverage = new ConcurrentHashMap<>();

    private record ApplicationCoverage(int profiledSuccessors, int appliedSuccessors) {
    }

    public SimpleConditionalProfilesLookup(Map<List<FrameKey>, long[]> conditionalData, ConditionalProfileDiagnostics diagnostics) {
        this(conditionalData, Map.of(), diagnostics);
    }

    public SimpleConditionalProfilesLookup(Map<List<FrameKey>, long[]> conditionalData, Map<PreciseKey, PreciseProfile> preciseConditionalData,
                    ConditionalProfileDiagnostics diagnostics) {
        this(conditionalData, preciseConditionalData, diagnostics, Map.of(), null, Map.of(), null, Map.of(), Map.of(), null);
    }

    public SimpleConditionalProfilesLookup(Map<List<FrameKey>, long[]> conditionalData, Map<PreciseKey, PreciseProfile> preciseConditionalData,
                    ConditionalProfileDiagnostics diagnostics, Map<List<FrameKey>, Map<AnalysisType, Long>> virtualInvokeData,
                    ConditionalProfileContextResolver.VirtualInvokeDiagnostics virtualInvokeDiagnostics) {
        this(conditionalData, preciseConditionalData, diagnostics, virtualInvokeData, virtualInvokeDiagnostics, Map.of(), null, Map.of(), Map.of(), null);
    }

    public SimpleConditionalProfilesLookup(Map<List<FrameKey>, long[]> conditionalData, Map<PreciseKey, PreciseProfile> preciseConditionalData,
                    ConditionalProfileDiagnostics diagnostics, Map<List<FrameKey>, Map<AnalysisType, Long>> virtualInvokeData,
                    ConditionalProfileContextResolver.VirtualInvokeDiagnostics virtualInvokeDiagnostics,
                    Map<NodeSourcePosition, Long> sampleCounts, ConditionalProfileContextResolver.SamplingDiagnostics samplingDiagnostics,
                    Map<String, Long> callCountsByMethod, Map<List<FrameKey>, Long> callCountsByContext,
                    ConditionalProfileContextResolver.CallCountDiagnostics callCountDiagnostics) {
        this.sampleCounts = Map.copyOf(sampleCounts);
        this.callCountsByMethod = Map.copyOf(callCountsByMethod);
        this.callCountsByContext = Map.copyOf(callCountsByContext);
        this.callCountDiagnostics = callCountDiagnostics;
        this.samplingDiagnostics = samplingDiagnostics;
        this.virtualInvokeData = Map.copyOf(virtualInvokeData);
        this.virtualInvokeDiagnostics = virtualInvokeDiagnostics;
        this.conditionalData = Map.copyOf(conditionalData);
        this.preciseConditionalData = Map.copyOf(preciseConditionalData);
        Map<List<FrameKey>, List<PreciseKey>> byContext = new java.util.HashMap<>();
        for (PreciseKey key : preciseConditionalData.keySet()) {
            byContext.computeIfAbsent(key.context(), _ -> new ArrayList<>()).add(key);
        }
        byContext.replaceAll((_, sites) -> List.copyOf(sites));
        this.preciseSitesByContext = Map.copyOf(byContext);
        this.preferPrecise = !preciseConditionalData.isEmpty();
        this.diagnostics = diagnostics;
    }

    public void setUseCallCounts(boolean enabled) {
        this.useCallCounts = enabled;
    }

    public void setContextFallback(boolean enabled) {
        this.contextFallback = enabled;
    }

    public void setReceiverContextFallback(boolean enabled) {
        this.receiverContextFallback = enabled;
    }

    public long virtualInvokeFallbackCount() {
        return virtualInvokeFallbackCount.get();
    }

    public long virtualInvokeFallbackDroppedFrames() {
        return virtualInvokeFallbackDroppedFrames.get();
    }

    public long contextFallbackCount() {
        return contextFallbackCount.get();
    }

    public long contextFallbackDroppedFrames() {
        return contextFallbackDroppedFrames.get();
    }

    public void setFilter(ConditionalProfileFilter newFilter) {
        this.filter = newFilter == null ? ConditionalProfileFilter.NONE : newFilter;
    }

    public ConditionalProfileFilter filter() {
        return filter;
    }

    public long filteredFewEventsCount() {
        return filteredFewEventsCount.get();
    }

    public long filteredEvenCount() {
        return filteredEvenCount.get();
    }

    public int filteredContextCount() {
        return filteredContexts.size();
    }

    /**
     * Records how an applied profile related to the probability already on the node. {@code flipped}
     * means the profiled dominant successor was not the prior's dominant successor.
     */
    public void recordPriorComparison(long events, boolean flipped, boolean priorInjected) {
        int decade = events <= 0 ? 0 : Math.min(DECADES - 1, (int) Math.floor(Math.log10(events)) + 1);
        if (flipped) {
            priorFlipsByDecade[decade].incrementAndGet();
            if (priorInjected) {
                injectedPriorFlipsByDecade[decade].incrementAndGet();
            }
        } else {
            priorAgreementsByDecade[decade].incrementAndGet();
        }
    }

    /** Human-readable "decade: agree/flip(injected)" table for the summary line. */
    public String priorComparisonSummary() {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < DECADES; i++) {
            long agree = priorAgreementsByDecade[i].get();
            long flip = priorFlipsByDecade[i].get();
            if (agree == 0 && flip == 0) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(", ");
            }
            String range = i == 0 ? "0" : i == DECADES - 1 ? ">=1e" + (i - 1) : "1e" + (i - 1) + "-1e" + i;
            builder.append(range).append(": ").append(agree).append('/').append(flip).append('(').append(injectedPriorFlipsByDecade[i].get()).append(')');
        }
        return builder.isEmpty() ? "none" : builder.toString();
    }

    /** Deterministic quality/summary information gathered while building the table. */
    public ConditionalProfileDiagnostics diagnostics() {
        return diagnostics;
    }

    /** Returns deterministic context-key samples for profile compatibility diagnostics. */
    public List<String> contextKeySamples(int limit) {
        if (preferPrecise) {
            return preciseConditionalData.keySet().stream().map(Object::toString).sorted().limit(limit).toList();
        }
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
        return preferPrecise ? preciseConditionalData.size() : conditionalData.size();
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
        return availableContextCount() - matchedContexts.size();
    }

    /** Number of applied-lookup queries that did not match any installed conditional context. */
    public long missCount() {
        return missCount.get();
    }

    @Override
    public boolean profileCategoryRecorded(String category) {
        if (cleared) {
            return false;
        }
        if (CALL_COUNT_PROFILES_CATEGORY.equals(category)) {
            return useCallCounts && !callCountsByMethod.isEmpty();
        }
        if (VIRTUAL_INVOKE_PROFILES_CATEGORY.equals(category)) {
            return !virtualInvokeData.isEmpty();
        }
        if (SAMPLING_PROFILES_CATEGORY.equals(category)) {
            return !sampleCounts.isEmpty();
        }
        return CONDITIONAL_PROFILES_CATEGORY.equals(category) && (!conditionalData.isEmpty() || !preciseConditionalData.isEmpty());
    }

    @Override
    public Optional<ProfiledValue<long[]>> getConditionalProfile(BytecodePosition callingContext) {
        return getConditionalProfile(callingContext, null);
    }

    @Override
    public Optional<ProfiledValue<long[]>> getConditionalProfile(BytecodePosition callingContext, ConditionalProfileSiteDescriptor site) {
        if (cleared || callingContext == null) {
            return Optional.empty();
        }
        Object queryKey = rawProfileKey(callingContext, site);
        Object key = selectProfileKey(queryKey);
        boolean shortened = false;
        if (key instanceof ShortenedKey shortenedKey) {
            key = shortenedKey.context();
            shortened = true;
            contextFallbackCount.incrementAndGet();
            contextFallbackDroppedFrames.addAndGet(shortenedKey.droppedFrames());
        }
        PreciseProfile preciseProfile = key instanceof PreciseKey preciseKey ? preciseConditionalData.get(preciseKey) : null;
        long[] records = preciseProfile != null ? preciseProfile.records() : conditionalData.get(key);
        if (records == null) {
            missCount.incrementAndGet();
            if (queryKey instanceof PreciseKey preciseKey) {
                classifyPreciseMiss(preciseKey);
            }
            return Optional.empty();
        }
        boolean exactPreciseMatch = queryKey.equals(key);
        if (!exactPreciseMatch && !shortened && queryKey instanceof PreciseKey) {
            preciseUnambiguousFallbackCount.incrementAndGet();
        }
        if (exactPreciseMatch && preciseProfile != null && site != null && preciseProfile.conditionFingerprint() != site.conditionFingerprint()) {
            preciseFingerprintDriftCount.incrementAndGet();
        }
        switch (filter.classify(records)) {
            case TOO_FEW_EVENTS -> {
                filteredFewEventsCount.incrementAndGet();
                filteredContexts.add(key);
                return Optional.empty();
            }
            case TOO_EVEN -> {
                filteredEvenCount.incrementAndGet();
                filteredContexts.add(key);
                return Optional.empty();
            }
            default -> {
            }
        }
        matchedContexts.add(key);
        hitCount.incrementAndGet();
        return Optional.of(new ProfiledValue<>(ProfileSource.PROFILED, records));
    }

    private void classifyPreciseMiss(PreciseKey query) {
        List<PreciseKey> candidates = preciseSitesByContext.get(query.context());
        if (candidates == null) {
            preciseContextMissCount.incrementAndGet();
            return;
        }
        candidates = candidates.stream().filter(candidate -> candidate.stage() == query.stage()).toList();
        if (candidates.isEmpty()) {
            preciseStageMissCount.incrementAndGet();
            return;
        }
        candidates = candidates.stream().filter(candidate -> candidate.successorBcis().equals(query.successorBcis())).toList();
        if (candidates.isEmpty()) {
            preciseSuccessorMissCount.incrementAndGet();
            return;
        }
        candidates = candidates.stream().filter(candidate -> candidate.conditionKind().equals(query.conditionKind())).toList();
        if (candidates.isEmpty()) {
            preciseConditionKindMissCount.incrementAndGet();
            return;
        }
        preciseOccurrenceMissCount.incrementAndGet();
    }

    public boolean usesPreciseProfiles() {
        return preferPrecise;
    }

    public PreciseMissDiagnostics preciseMissDiagnostics() {
        return new PreciseMissDiagnostics(preciseContextMissCount.get(), preciseStageMissCount.get(), preciseSuccessorMissCount.get(),
                        preciseConditionKindMissCount.get(), preciseOccurrenceMissCount.get(), preciseFingerprintDriftCount.get(), preciseUnambiguousFallbackCount.get());
    }

    public record PreciseMissDiagnostics(long context, long stage, long successors, long conditionKind, long occurrence, long fingerprintDrift,
                    long unambiguousFallback) {
    }

    @Override
    public void recordConditionalProfileApplication(BytecodePosition callingContext, int profiledSuccessors, int appliedSuccessors) {
        recordConditionalProfileApplication(callingContext, null, profiledSuccessors, appliedSuccessors);
    }

    @Override
    public void recordConditionalProfileApplication(BytecodePosition callingContext, ConditionalProfileSiteDescriptor site,
                    int profiledSuccessors, int appliedSuccessors) {
        if (cleared || callingContext == null) {
            return;
        }
        Object key = selectProfileKey(rawProfileKey(callingContext, site));
        if (key instanceof ShortenedKey shortenedKey) {
            key = shortenedKey.context();
        }
        boolean present = key instanceof PreciseKey preciseKey ? preciseConditionalData.containsKey(preciseKey) : conditionalData.containsKey(key);
        if (!present) {
            return;
        }
        applicationCoverage.compute(key, (_, previous) -> {
            if (previous == null) {
                return new ApplicationCoverage(profiledSuccessors, appliedSuccessors);
            }
            return new ApplicationCoverage(Math.max(previous.profiledSuccessors(), profiledSuccessors), Math.max(previous.appliedSuccessors(), appliedSuccessors));
        });
    }

    private Object rawProfileKey(BytecodePosition callingContext, ConditionalProfileSiteDescriptor site) {
        List<FrameKey> context = canonicalize(callingContext);
        if (preferPrecise) {
            return site == null ? PreciseKey.from(context, MISSING_SITE) : PreciseKey.from(context, site);
        }
        return context;
    }

    /**
     * Selects exact v2 first, then the legacy entry for the full context, then legacy entries for
     * progressively shorter contexts (outermost callers dropped) when enabled. A shortened context
     * is the same bytecode branch observed under a different or absent inlining chain, e.g. a callee
     * the profile saw compiled standalone that the profiled build now inlines into a new caller.
     */
    private Object selectProfileKey(Object queryKey) {
        if (!(queryKey instanceof PreciseKey preciseQuery) || preciseConditionalData.containsKey(preciseQuery)) {
            if (queryKey instanceof List<?> context && !conditionalData.containsKey(context)) {
                Object shortened = shortenedLegacyKey(asFrames(context));
                return shortened == null ? queryKey : shortened;
            }
            return queryKey;
        }
        if (conditionalData.containsKey(preciseQuery.context())) {
            /* The legacy entry is the producer's sum over all same-branch physical copies at this context. */
            return preciseQuery.context();
        }
        Object shortened = shortenedLegacyKey(preciseQuery.context());
        return shortened == null ? queryKey : shortened;
    }

    @SuppressWarnings("unchecked")
    private static List<FrameKey> asFrames(List<?> context) {
        return (List<FrameKey>) context;
    }

    private Object shortenedLegacyKey(List<FrameKey> context) {
        if (!contextFallback || context.size() < 2) {
            return null;
        }
        for (int depth = context.size() - 1; depth >= 1; depth--) {
            List<FrameKey> shorter = context.subList(0, depth);
            if (conditionalData.containsKey(shorter)) {
                return new ShortenedKey(List.copyOf(shorter), context.size() - depth);
            }
        }
        return null;
    }

    /** Marker for a legacy hit obtained by dropping {@code droppedFrames} outermost frames. */
    private record ShortenedKey(List<FrameKey> context, int droppedFrames) {
    }

    private static final ConditionalProfileSiteDescriptor MISSING_SITE = new ConditionalProfileSiteDescriptor(
                    ConditionalProfileSiteDescriptor.Stage.ROOT_PRE_INLINE, List.of(), "missing", 0L, -1);

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

    public Optional<Long> getContextCallCount(BytecodePosition callingContext) {
        if (cleared || !useCallCounts || callingContext == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(callCountsByContext.get(canonicalize(callingContext)));
    }

    @Override
    public long getEntryCountOrZero(HostedMethod method, BytecodePosition callingContext) {
        if (cleared || !useCallCounts) {
            return 0;
        }
        if (callingContext != null) {
            List<FrameKey> key = canonicalize(callingContext);
            for (int depth = key.size(); depth >= 1; depth--) {
                Long count = callCountsByContext.get(depth == key.size() ? key : key.subList(0, depth));
                if (count != null) {
                    return count;
                }
            }
        }
        return method == null ? 0 : getCallCountOrZero(method);
    }

    long getMethodCallCount(String methodDescriptor) {
        return callCountsByMethod.getOrDefault(methodDescriptor, 0L);
    }

    public ConditionalProfileContextResolver.CallCountDiagnostics callCountDiagnostics() {
        return callCountDiagnostics;
    }

    @Override
    public Optional<ProfiledValue<Long>> getCallCountProfile(HostedMethod method) {
        if (cleared || !useCallCounts || method == null) {
            return Optional.empty();
        }
        Long count = callCountsByMethod.get(ConditionalProfileContextResolver.methodDescriptor(method));
        return count == null ? Optional.empty() : Optional.of(new ProfiledValue<>(ProfileSource.PROFILED, count));
    }

    @Override
    public long getCallCountOrZero(HostedMethod method) {
        return getCallCountProfile(method).map(ProfiledValue::value).orElse(0L);
    }

    @Override
    public boolean isExecuted(HostedMethod method) {
        return getCallCountOrZero(method) > 0;
    }

    @Override
    public Optional<Map<AnalysisType, Long>> getVirtualInvokeProfile(BytecodePosition callingContext) {
        if (cleared || callingContext == null || virtualInvokeData.isEmpty()) {
            return Optional.empty();
        }
        List<FrameKey> key = canonicalize(callingContext);
        Map<AnalysisType, Long> receivers = virtualInvokeData.get(key);
        if (receivers == null && receiverContextFallback) {
            /*
             * The same indirect call recorded under a shorter inlining chain: the instrumented image
             * compiled the callee standalone (or inlined it less deeply) while this image inlines
             * it into a new caller. The receiver distribution is a property of the call site and
             * its data flow, which the extra callers do not change for a site whose recorded
             * receivers agree, so the shorter context is the best available estimate. A type guard
             * with a fallback keeps a wrong estimate a performance question, not a correctness one.
             */
            for (int depth = key.size() - 1; depth >= 1 && receivers == null; depth--) {
                List<FrameKey> shorter = key.subList(0, depth);
                receivers = virtualInvokeData.get(shorter);
                if (receivers != null) {
                    key = List.copyOf(shorter);
                    virtualInvokeFallbackCount.incrementAndGet();
                    virtualInvokeFallbackDroppedFrames.addAndGet(callingContextDepth(callingContext) - depth);
                }
            }
        }
        if (receivers == null) {
            virtualInvokeMissCount.incrementAndGet();
            return Optional.empty();
        }
        virtualInvokeHitCount.incrementAndGet();
        matchedVirtualInvokeContexts.add(key);
        return Optional.of(receivers);
    }

    private static int callingContextDepth(BytecodePosition position) {
        int depth = 0;
        for (BytecodePosition current = position; current != null; current = current.getCaller()) {
            depth++;
        }
        return depth;
    }

    @Override
    public Optional<Map<NodeSourcePosition, Long>> getSampleCounts() {
        return cleared || sampleCounts.isEmpty() ? Optional.empty() : Optional.of(sampleCounts);
    }

    public ConditionalProfileContextResolver.SamplingDiagnostics samplingDiagnostics() {
        return samplingDiagnostics;
    }

    public ConditionalProfileContextResolver.VirtualInvokeDiagnostics virtualInvokeDiagnostics() {
        return virtualInvokeDiagnostics;
    }

    public long virtualInvokeHitCount() {
        return virtualInvokeHitCount.get();
    }

    public long virtualInvokeMissCount() {
        return virtualInvokeMissCount.get();
    }

    public int matchedVirtualInvokeContextCount() {
        return matchedVirtualInvokeContexts.size();
    }

    public int availableVirtualInvokeContextCount() {
        return virtualInvokeData.size();
    }

    @Override
    public void recordImpossibleReceiver(long events) {
        impossibleReceiverRecords.incrementAndGet();
        impossibleReceiverEvents.addAndGet(events);
    }

    public long impossibleReceiverRecords() {
        return impossibleReceiverRecords.get();
    }

    public long impossibleReceiverEvents() {
        return impossibleReceiverEvents.get();
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
