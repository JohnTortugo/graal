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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import com.oracle.graal.pointsto.meta.AnalysisMethod;
import com.oracle.graal.pointsto.meta.AnalysisType;
import com.oracle.svm.hosted.meta.HostedMethod;
import com.oracle.svm.hosted.pgo.ProfilingUtilities;
import com.oracle.svm.hosted.pgo.phases.PGOApplyProfilesPhase;
import com.oracle.svm.shared.singletons.traits.BuiltinTraits.BuildtimeAccessOnly;
import com.oracle.svm.shared.singletons.traits.BuiltinTraits.DisallowLayered;
import com.oracle.svm.shared.singletons.traits.BuiltinTraits.NoLayeredCallbacks;
import com.oracle.svm.shared.singletons.traits.SingletonTraits;

import jdk.graal.compiler.nodes.ProfileData.ProfileSource;
import jdk.graal.compiler.graph.NodeSourcePosition;
import jdk.vm.ci.code.BytecodePosition;

/**
 * The {@link PGOProfilesLookup} that serves a parsed iprof profile to the compiler.
 * <p>
 * It answers four recorded categories: {@code conditionalProfiles} (branch and switch successor
 * counts), {@code callCountProfiles}, {@code virtualInvokeProfiles} (receiver types) and
 * {@code samplingProfiles} (stack samples). Each is looked up by a canonical calling context. The
 * iprof file records no instanceof or image-heap profiles, so those queries answer as for an absent
 * profile.
 * <p>
 * The tables are built by {@code PGOProfileUseFeature} once the {@code HostedUniverse} is
 * available; see {@link IprofProfileResolver} for the resolution algorithm. The tables do not
 * change after construction; the counters only record how the compiler used them.
 */
@SingletonTraits(access = BuildtimeAccessOnly.class, layeredCallbacks = NoLayeredCallbacks.class, other = DisallowLayered.class)
public final class IprofProfilesLookup implements PGOProfilesLookup {

    /** Section names of the iprof file, as written by {@code IprofWriter}. */
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

    private final Map<List<FrameKey>, long[]> conditionalData;
    /**
     * Sum of the conditional records of every context that starts with the key, innermost frame
     * first: {@code [foo:12]} is all executions of that branch, {@code [foo:12, a:5]} all
     * executions inlined into {@code a} at bci 5, whatever the outer callers.
     */
    private final Map<List<FrameKey>, long[]> prefixSums;
    private final Map<NodeSourcePosition, Long> sampleCounts;
    private final Map<String, Long> callCountsByMethod;
    private final Map<List<FrameKey>, Long> callCountsByContext;
    private final IprofProfileResolver.CallCountDiagnostics callCountDiagnostics;
    private final IprofProfileResolver.SamplingDiagnostics samplingDiagnostics;
    private final Map<List<FrameKey>, Map<AnalysisType, Long>> virtualInvokeData;
    private final IprofProfileResolver.VirtualInvokeDiagnostics virtualInvokeDiagnostics;
    private final AtomicLong virtualInvokeHitCount = new AtomicLong();
    private final AtomicLong virtualInvokeMissCount = new AtomicLong();
    private final Set<List<FrameKey>> matchedVirtualInvokeContexts = ConcurrentHashMap.newKeySet();
    /** Receiver-profile hits obtained by dropping outermost frames of the query context. */
    private final AtomicLong virtualInvokeFallbackCount = new AtomicLong();
    private final AtomicLong virtualInvokeFallbackDroppedFrames = new AtomicLong();
    private volatile boolean receiverContextFallback = true;
    private final AtomicLong impossibleReceiverRecords = new AtomicLong();
    private final AtomicLong impossibleReceiverEvents = new AtomicLong();
    private final ConditionalProfileDiagnostics diagnostics;
    private volatile boolean useCallCounts = true;
    /** Whether consumers see the stack samples; when not, the lookup answers as for a profile without them. */
    private volatile boolean useStackSamples = true;
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
    private final AtomicLong contextFallbackCount = new AtomicLong();
    private final AtomicLong contextFallbackDroppedFrames = new AtomicLong();
    private volatile boolean contextFallback = true;
    /** Exact profile contexts that matched at least one compiler query. */
    private final Set<Object> matchedContexts = ConcurrentHashMap.newKeySet();
    /** Best successor-record coverage observed for every matched context. */
    private final Map<Object, ApplicationCoverage> applicationCoverage = new ConcurrentHashMap<>();

    /**
     * How many successor records of a matched context the profile has, and how many of them the
     * phase could match to successors in the graph.
     */
    private record ApplicationCoverage(int profiledSuccessors, int appliedSuccessors) {
    }

    public IprofProfilesLookup(Map<List<FrameKey>, long[]> conditionalData, ConditionalProfileDiagnostics diagnostics) {
        this(conditionalData, diagnostics, Map.of(), null, Map.of(), null, Map.of(), Map.of(), null);
    }

    public IprofProfilesLookup(Map<List<FrameKey>, long[]> conditionalData,
                    ConditionalProfileDiagnostics diagnostics, Map<List<FrameKey>, Map<AnalysisType, Long>> virtualInvokeData,
                    IprofProfileResolver.VirtualInvokeDiagnostics virtualInvokeDiagnostics) {
        this(conditionalData, diagnostics, virtualInvokeData, virtualInvokeDiagnostics, Map.of(), null, Map.of(), Map.of(), null);
    }

    public IprofProfilesLookup(Map<List<FrameKey>, long[]> conditionalData,
                    ConditionalProfileDiagnostics diagnostics, Map<List<FrameKey>, Map<AnalysisType, Long>> virtualInvokeData,
                    IprofProfileResolver.VirtualInvokeDiagnostics virtualInvokeDiagnostics,
                    Map<NodeSourcePosition, Long> sampleCounts, IprofProfileResolver.SamplingDiagnostics samplingDiagnostics,
                    Map<String, Long> callCountsByMethod, Map<List<FrameKey>, Long> callCountsByContext,
                    IprofProfileResolver.CallCountDiagnostics callCountDiagnostics) {
        this.sampleCounts = Map.copyOf(sampleCounts);
        this.callCountsByMethod = Map.copyOf(callCountsByMethod);
        this.callCountsByContext = Map.copyOf(callCountsByContext);
        this.callCountDiagnostics = callCountDiagnostics;
        this.samplingDiagnostics = samplingDiagnostics;
        this.virtualInvokeData = Map.copyOf(virtualInvokeData);
        this.virtualInvokeDiagnostics = virtualInvokeDiagnostics;
        this.conditionalData = Map.copyOf(conditionalData);
        this.prefixSums = prefixSums(conditionalData);
        this.diagnostics = diagnostics;
    }

    /** A branch or switch arm of a conditional record: its successor bci and successor index. */
    private record Arm(long successorBci, long successorIndex) {
    }

    /**
     * Adds every entry's records to each prefix of its context, including the full context. A
     * record is {@link PGOApplyProfilesPhase#CONDITIONAL_RECORD_SIZE} longs: successor bci,
     * successor index and count. The counts of one arm are summed; arms keep the order of their
     * first record.
     */
    static Map<List<FrameKey>, long[]> prefixSums(Map<List<FrameKey>, long[]> conditionalData) {
        Map<List<FrameKey>, Map<Arm, Long>> sums = new HashMap<>();
        for (Map.Entry<List<FrameKey>, long[]> entry : conditionalData.entrySet()) {
            List<FrameKey> context = entry.getKey();
            long[] records = entry.getValue();
            for (int depth = 1; depth <= context.size(); depth++) {
                Map<Arm, Long> counts = sums.computeIfAbsent(List.copyOf(context.subList(0, depth)), _ -> new LinkedHashMap<>());
                for (int i = 0; i + 2 < records.length; i += PGOApplyProfilesPhase.CONDITIONAL_RECORD_SIZE) {
                    counts.merge(new Arm(records[i], records[i + 1]), records[i + 2], Long::sum);
                }
            }
        }
        Map<List<FrameKey>, long[]> result = new HashMap<>(sums.size());
        sums.forEach((context, counts) -> {
            long[] records = new long[counts.size() * PGOApplyProfilesPhase.CONDITIONAL_RECORD_SIZE];
            int i = 0;
            for (Map.Entry<Arm, Long> arm : counts.entrySet()) {
                records[i++] = arm.getKey().successorBci();
                records[i++] = arm.getKey().successorIndex();
                records[i++] = arm.getValue();
            }
            result.put(context, records);
        });
        return Map.copyOf(result);
    }

    public void setUseCallCounts(boolean enabled) {
        this.useCallCounts = enabled;
    }

    public void setUseStackSamples(boolean enabled) {
        this.useStackSamples = enabled;
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

    /** Deterministic quality/summary information gathered while building the table. */
    public ConditionalProfileDiagnostics conditionalDiagnostics() {
        return diagnostics;
    }

    /** Returns deterministic context-key samples for profile compatibility diagnostics. */
    public List<String> contextKeySamples(int limit) {
        return conditionalData.keySet().stream().map(Object::toString).sorted().limit(limit).toList();
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
            return useStackSamples && !sampleCounts.isEmpty();
        }
        return CONDITIONAL_PROFILES_CATEGORY.equals(category) && !conditionalData.isEmpty();
    }

    @Override
    public Optional<ProfiledValue<long[]>> getConditionalProfile(BytecodePosition callingContext) {
        if (cleared || callingContext == null) {
            return Optional.empty();
        }
        Object key = selectProfileKey(canonicalize(callingContext));
        long[] records;
        if (key instanceof ShortenedKey shortenedKey) {
            records = prefixSums.get(shortenedKey.context());
            key = new PrefixContext(shortenedKey.context());
            contextFallbackCount.incrementAndGet();
            contextFallbackDroppedFrames.addAndGet(shortenedKey.droppedFrames());
        } else {
            records = conditionalData.get(key);
        }
        if (records == null) {
            missCount.incrementAndGet();
            return Optional.empty();
        }
        if (!(key instanceof PrefixContext)) {
            /* Context statistics are about the profile's own entries; fallback hits are counted apart. */
            matchedContexts.add(key);
        }
        hitCount.incrementAndGet();
        return Optional.of(new ProfiledValue<>(ProfileSource.PROFILED, records));
    }

    @Override
    public void recordConditionalProfileApplication(BytecodePosition callingContext, int profiledSuccessors, int appliedSuccessors) {
        if (cleared || callingContext == null) {
            return;
        }
        Object key = selectProfileKey(canonicalize(callingContext));
        if (key instanceof ShortenedKey || !conditionalData.containsKey(key)) {
            /* Coverage is tracked for the profile's own entries only, like matchedContexts. */
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
     * Selects the entry for the full context, then, when enabled, the sum over all profiled
     * contexts that start with the longest matching prefix of the query (outermost callers
     * dropped). A prefix sum is the same bytecode branch observed under a different or absent
     * inlining chain, e.g. a callee the profile saw inlined into other callers that the profiled
     * build now inlines into a new one.
     */
    private Object selectProfileKey(List<FrameKey> context) {
        if (!conditionalData.containsKey(context)) {
            Object shortened = longestProfiledPrefix(context);
            return shortened == null ? context : shortened;
        }
        return context;
    }

    private Object longestProfiledPrefix(List<FrameKey> context) {
        if (!contextFallback || context.isEmpty()) {
            return null;
        }
        /* The full length first: the context itself may only have been seen under more callers. */
        for (int depth = context.size(); depth >= 1; depth--) {
            List<FrameKey> shorter = context.subList(0, depth);
            if (prefixSums.containsKey(shorter)) {
                return new ShortenedKey(List.copyOf(shorter), context.size() - depth);
            }
        }
        return null;
    }

    /** Marker for a hit obtained by dropping {@code droppedFrames} outermost frames. */
    private record ShortenedKey(List<FrameKey> context, int droppedFrames) {
    }

    /** Statistics key of a prefix sum, kept apart from the full-context entry with the same frames. */
    private record PrefixContext(List<FrameKey> context) {
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
            frames.add(new FrameKey(ProfilingUtilities.methodDescriptor(current.getMethod()), current.getBCI()));
        }
        return frames;
    }

    @Override
    public void clear() {
        cleared = true;
    }

    // --- Call counts ---------------------------------------------------------------------------

    public Optional<Long> getContextCallCount(BytecodePosition callingContext) {
        if (cleared || !useCallCounts || callingContext == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(callCountsByContext.get(canonicalize(callingContext)));
    }

    public IprofProfileResolver.CallCountDiagnostics callCountDiagnostics() {
        return callCountDiagnostics;
    }

    @Override
    public Optional<ProfiledValue<Long>> getCallCountProfile(HostedMethod method) {
        if (method == null) {
            return Optional.empty();
        }
        return methodCallCount(ProfilingUtilities.methodDescriptor(method));
    }

    /** The call count of the method with the given canonical descriptor, summed over all contexts. */
    Optional<ProfiledValue<Long>> methodCallCount(String methodDescriptor) {
        if (cleared || !useCallCounts) {
            return Optional.empty();
        }
        Long count = callCountsByMethod.get(methodDescriptor);
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
        return cleared || !useStackSamples || sampleCounts.isEmpty() ? Optional.empty() : Optional.of(sampleCounts);
    }

    public IprofProfileResolver.SamplingDiagnostics samplingDiagnostics() {
        return samplingDiagnostics;
    }

    public IprofProfileResolver.VirtualInvokeDiagnostics virtualInvokeDiagnostics() {
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

    // --- Categories the iprof file does not record: answered as absent ----------------------

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
}
