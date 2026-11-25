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

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import com.oracle.graal.pointsto.meta.AnalysisMethod;
import com.oracle.graal.pointsto.meta.AnalysisType;
import com.oracle.svm.core.pgo.IprofFormat;
import com.oracle.svm.hosted.meta.HostedMethod;
import com.oracle.svm.hosted.pgo.ProfilingUtilities;
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
 * It answers four recorded categories, which are named as the iprof sections
 * ({@link IprofFormat}): {@code conditionalProfiles} (branch and switch successor counts),
 * {@code callCountProfiles}, {@code virtualInvokeProfiles} (receiver types) and
 * {@code samplingProfiles} (stack samples). Conditional and receiver profiles, and call counts per
 * call edge, are looked up by {@link PGOContext}; call counts per method by method descriptor; the
 * stack samples are handed out as one map. All other queries, such as instanceof, image-heap and
 * monitor profiles, answer as for an absent profile.
 * <p>
 * {@link IprofProfileResolver} builds the tables and the {@link PGODiagnostics} once the
 * {@code HostedUniverse} is available. The tables do not change after construction; the counters
 * only record how the compiler used them.
 */
@SingletonTraits(access = BuildtimeAccessOnly.class, layeredCallbacks = NoLayeredCallbacks.class, other = DisallowLayered.class)
public final class IprofProfilesLookup implements PGOProfilesLookup {

    private final Map<PGOContext, long[]> conditionalData;
    /**
     * Sum of the conditional records of every context that starts with the key, innermost frame
     * first: {@code foo:12} is all executions of that branch, {@code foo:12 < a:5} all executions
     * inlined into {@code a} at bci 5, whatever the outer callers (see
     * {@link IprofProfileResolver#prefixSums}).
     */
    private final Map<PGOContext, long[]> prefixSums;
    private final Map<NodeSourcePosition, Long> sampleCounts;
    private final Map<String, Long> callCountsByMethod;
    private final Map<PGOContext, Long> callCountsByContext;
    private final Map<PGOContext, Map<AnalysisType, Long>> virtualInvokeData;
    private final PGODiagnostics diagnostics;

    private volatile boolean contextFallback = true;
    private volatile boolean receiverContextFallback = true;
    private volatile boolean useCallCounts = true;
    /** Whether consumers see the stack samples; when not, the lookup answers as for a profile without them. */
    private volatile boolean useStackSamples = true;
    private volatile boolean cleared;

    /**
     * Number of {@link #getConditionalProfile(BytecodePosition)} calls that found a record
     * ({@code hits}), for the full context or, with context fallback, as a prefix sum, and that
     * found none ({@code misses}). These count how the profile is actually applied while compiling
     * image graphs, independent of the phase-level {@code PGOApplyProfilesPhase.ProfileQuality}
     * counters (which are only updated when PGOPrintProfileQuality or
     * PGOPrintProfileQualityDetails is set). They are always maintained so a deterministic
     * afterCompilation summary can be produced without enabling any option.
     */
    private final AtomicLong hitCount = new AtomicLong();
    private final AtomicLong missCount = new AtomicLong();
    private final AtomicLong contextFallbackCount = new AtomicLong();
    private final AtomicLong contextFallbackDroppedFrames = new AtomicLong();
    /** Exact profile contexts that matched at least one compiler query. */
    private final Set<Object> matchedContexts = ConcurrentHashMap.newKeySet();
    /** Best successor-record coverage observed for every matched context. */
    private final Map<Object, ApplicationCoverage> applicationCoverage = new ConcurrentHashMap<>();

    private final AtomicLong virtualInvokeHitCount = new AtomicLong();
    private final AtomicLong virtualInvokeMissCount = new AtomicLong();
    private final Set<PGOContext> matchedVirtualInvokeContexts = ConcurrentHashMap.newKeySet();
    /** Receiver-profile hits obtained by dropping outermost frames of the query context. */
    private final AtomicLong virtualInvokeFallbackCount = new AtomicLong();
    private final AtomicLong virtualInvokeFallbackDroppedFrames = new AtomicLong();
    private final AtomicLong impossibleReceiverRecords = new AtomicLong();
    private final AtomicLong impossibleReceiverEvents = new AtomicLong();

    /** Call-count lookups per call edge and per method, and how many of them found a count. */
    private final AtomicLong contextCallCountQueries = new AtomicLong();
    private final AtomicLong contextCallCountHits = new AtomicLong();
    private final AtomicLong methodCallCountQueries = new AtomicLong();
    private final AtomicLong methodCallCountHits = new AtomicLong();

    /**
     * How many successor records of a matched context the profile has, and how many of them the
     * phase could match to successors in the graph.
     */
    private record ApplicationCoverage(int profiledSuccessors, int appliedSuccessors) {
    }

    /** Marker for a hit obtained by dropping {@code droppedFrames} outermost frames. */
    private record ShortenedKey(PGOContext context, int droppedFrames) {
    }

    /** Statistics key of a prefix sum, kept apart from the full-context entry with the same frames. */
    private record PrefixContext(PGOContext context) {
    }

    public IprofProfilesLookup(Map<PGOContext, long[]> conditionalData, Map<PGOContext, long[]> prefixSums, Map<PGOContext, Map<AnalysisType, Long>> virtualInvokeData,
                    Map<NodeSourcePosition, Long> sampleCounts, Map<String, Long> callCountsByMethod, Map<PGOContext, Long> callCountsByContext, PGODiagnostics diagnostics) {
        this.conditionalData = Map.copyOf(conditionalData);
        this.prefixSums = Map.copyOf(prefixSums);
        this.virtualInvokeData = Map.copyOf(virtualInvokeData);
        this.sampleCounts = Map.copyOf(sampleCounts);
        this.callCountsByMethod = Map.copyOf(callCountsByMethod);
        this.callCountsByContext = Map.copyOf(callCountsByContext);
        this.diagnostics = diagnostics;
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

    /** What the resolver found when it matched the profile to the image. */
    public PGODiagnostics diagnostics() {
        return diagnostics;
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

    /**
     * Up to {@code limit} resolved conditional contexts in sorted order, such as
     * {@code Ljava/lang/String;.charAt(I)C:12 < Lcom/example/Foo;.bar()V:5}.
     */
    public List<String> contextKeySamples(int limit) {
        return conditionalData.keySet().stream().map(Object::toString).sorted().limit(limit).toList();
    }

    /** Number of {@link #getConditionalProfile} calls that found a record, for the full context or as a prefix sum. */
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

    /** Number of resolved conditional contexts that no {@link #getConditionalProfile} call has matched exactly so far. */
    public long unusedResolvedContextCount() {
        return availableContextCount() - matchedContexts.size();
    }

    /** Number of {@link #getConditionalProfile} calls that found no record. */
    public long missCount() {
        return missCount.get();
    }

    @Override
    public boolean profileCategoryRecorded(String category) {
        if (cleared) {
            return false;
        }
        if (IprofFormat.CALL_COUNT_PROFILES.equals(category)) {
            return useCallCounts && !callCountsByMethod.isEmpty();
        }
        if (IprofFormat.VIRTUAL_INVOKE_PROFILES.equals(category)) {
            return !virtualInvokeData.isEmpty();
        }
        if (IprofFormat.SAMPLING_PROFILES.equals(category)) {
            return useStackSamples && !sampleCounts.isEmpty();
        }
        return IprofFormat.CONDITIONAL_PROFILES.equals(category) && !conditionalData.isEmpty();
    }

    @Override
    public Optional<ProfiledValue<long[]>> getConditionalProfile(BytecodePosition callingContext) {
        if (cleared || callingContext == null) {
            return Optional.empty();
        }
        Object key = selectProfileKey(PGOContext.of(callingContext));
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
        Object key = selectProfileKey(PGOContext.of(callingContext));
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
    private Object selectProfileKey(PGOContext context) {
        if (!conditionalData.containsKey(context)) {
            Object shortened = longestProfiledPrefix(context);
            return shortened == null ? context : shortened;
        }
        return context;
    }

    private Object longestProfiledPrefix(PGOContext context) {
        if (!contextFallback || context.depth() == 0) {
            return null;
        }
        /* The full length first: the context itself may only have been seen under more callers. */
        for (int depth = context.depth(); depth >= 1; depth--) {
            PGOContext shorter = context.prefix(depth);
            if (prefixSums.containsKey(shorter)) {
                return new ShortenedKey(shorter, context.depth() - depth);
            }
        }
        return null;
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
        contextCallCountQueries.incrementAndGet();
        Long count = callCountsByContext.get(PGOContext.of(callingContext));
        if (count != null) {
            contextCallCountHits.incrementAndGet();
        }
        return Optional.ofNullable(count);
    }

    /** Number of call-edge lookups ({@link #getContextCallCount}) and how many found a count. */
    public long contextCallCountQueries() {
        return contextCallCountQueries.get();
    }

    public long contextCallCountHits() {
        return contextCallCountHits.get();
    }

    /** Number of method call-count lookups and how many found a count. */
    public long methodCallCountQueries() {
        return methodCallCountQueries.get();
    }

    public long methodCallCountHits() {
        return methodCallCountHits.get();
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
        methodCallCountQueries.incrementAndGet();
        Long count = callCountsByMethod.get(methodDescriptor);
        if (count == null) {
            return Optional.empty();
        }
        methodCallCountHits.incrementAndGet();
        return Optional.of(new ProfiledValue<>(ProfileSource.PROFILED, count));
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
        PGOContext key = PGOContext.of(callingContext);
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
            int queryDepth = key.depth();
            for (int depth = queryDepth - 1; depth >= 1 && receivers == null; depth--) {
                PGOContext shorter = key.prefix(depth);
                receivers = virtualInvokeData.get(shorter);
                if (receivers != null) {
                    key = shorter;
                    virtualInvokeFallbackCount.incrementAndGet();
                    virtualInvokeFallbackDroppedFrames.addAndGet(queryDepth - depth);
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

    @Override
    public Optional<Map<NodeSourcePosition, Long>> getSampleCounts() {
        return cleared || !useStackSamples || sampleCounts.isEmpty() ? Optional.empty() : Optional.of(sampleCounts);
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

    // --- Profiles this lookup does not serve: answered as absent ------------------------------

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
