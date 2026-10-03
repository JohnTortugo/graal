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
package com.oracle.svm.core.pgo;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.graalvm.word.LocationIdentity;
import org.graalvm.word.Pointer;

import com.oracle.svm.guest.staging.c.CGlobalData;
import com.oracle.svm.guest.staging.c.CGlobalDataFactory;
import com.oracle.svm.guest.staging.core.heap.UnknownPrimitiveField;
import com.oracle.svm.guest.staging.jdk.RuntimeSupport;
import com.oracle.svm.guest.staging.log.Log;
import com.oracle.svm.guest.staging.option.RuntimeOptionKey;
import com.oracle.svm.shared.BuildPhaseProvider.AfterCompilation;
import com.oracle.svm.shared.Uninterruptible;
import com.oracle.svm.shared.util.SubstrateUtil;

import jdk.graal.compiler.nodes.NamedLocationIdentity;
import jdk.graal.compiler.options.Option;
import jdk.internal.misc.Unsafe;

/** Build-time physical-site registry and relocation-safe runtime conditional counter storage. */
public final class BranchProfileRecorder {

    public static final class Options {
        @Option(help = "File path for the profile produced by a --pgo-instrument image. %p is replaced by the process id and %t by the start time in " +
                       "milliseconds, so that several training runs can write into one directory without overwriting each other; pass that directory to --pgo.")//
        public static final RuntimeOptionKey<String> ProfilesDumpFile = new RuntimeOptionKey<>("default.iprof");
    }

    private static final int MAX_BRANCHES = 1_000_000;
    private static final int COUNTERS_PER_BRANCH = 2;
    private static final int TRUE_OFFSET = 0;
    private static final int FALSE_OFFSET = 1;

    private static final Unsafe UNSAFE = Unsafe.getUnsafe();
    /** Counter memory is never aliased with Java heap memory, so base loads can be hoisted. */
    private static final LocationIdentity COUNTER_LOCATION = NamedLocationIdentity.mutable("PGOBranchCounters");

    /** Every selected physical graph site owns a counter; identity aggregation happens at dump. */
    private static final ConcurrentMap<Integer, BranchProfileCounter> counters = new ConcurrentHashMap<>();
    private static final AtomicInteger nextCounterIndex = new AtomicInteger();

    /**
     * Shared totals: zero-initialized global data sized after compilation. Exited threads fold their
     * private counts into it, and a thread whose private block could not be allocated counts here
     * directly.
     */
    private static final CGlobalData<Pointer> SHARED_COUNTS = CGlobalDataFactory.createBytes(
                    BranchProfileRecorder::sharedCountsBytes, "__svm_pgo_branch_counters");

    /** Hosted-only storage for unit tests; never reachable in an image. */
    private static final long[] hostedCounts = SubstrateUtil.HOSTED ? new long[MAX_BRANCHES * COUNTERS_PER_BRANCH] : null;

    /** Number of long slots per counter block; final once compilation has registered all sites. */
    @UnknownPrimitiveField(availability = AfterCompilation.class) //
    private static int registeredSlots;

    /** Merged view (shared + live threads) that the serializer reads; null outside a dump. */
    private static long[] snapshot;

    /* Makes all late-created metadata types and the counter write reachable to analysis. */
    private static final BranchProfileCounter unusedCounter = create(
                    "POST_HIGH_TIER", new String[]{"Lcom/oracle/svm/core/pgo/BranchProfileRecorder;.__unused__()V"},
                    new int[]{-1}, -1, -1, "unused", 0L, 0);

    private BranchProfileRecorder() {
    }

    /** Creates a distinct counter for one physical selected graph site. */
    public static BranchProfileCounter create(String stage, String[] methodDescriptors, int[] contextBcis,
                    int trueSuccessorBci, int falseSuccessorBci, String conditionKind, long conditionFingerprint, int occurrence) {
        if (methodDescriptors.length == 0 || methodDescriptors.length != contextBcis.length) {
            throw new IllegalArgumentException("Branch profile context must contain matching non-empty method and BCI arrays");
        }
        int index = nextCounterIndex.getAndIncrement();
        if (index >= MAX_BRANCHES) {
            throw new IllegalStateException("Conditional branch instrumentation exceeds the " + MAX_BRANCHES + "-site capacity");
        }
        BranchProfileCounter counter = new BranchProfileCounter(stage, methodDescriptors.clone(), contextBcis.clone(), trueSuccessorBci, falseSuccessorBci,
                        conditionKind, conditionFingerprint, occurrence, index);
        counters.put(index, counter);
        return counter;
    }

    /** Compatibility helper for unit tests and callers without a v2 site descriptor. */
    public static BranchProfileCounter lookup(String[] methodDescriptors, int[] contextBcis, int trueSuccessorBci, int falseSuccessorBci) {
        return create("POST_HIGH_TIER", methodDescriptors, contextBcis, trueSuccessorBci, falseSuccessorBci, "legacy", 0L, 0);
    }

    /**
     * Called by generated code. Within one thread's private block the increment is a plain
     * load/add/store, so per-thread counts are exact; only the exit-time merge needs atomics.
     *
     * There is deliberately no null check: {@link BranchProfileThreadCounters} points the
     * thread-local at a private block (or at the shared counters if that allocation failed) when the
     * thread attaches, before it can execute any Java code. A null-check diamond at every site cost
     * 5.4% and a displacement add 4.2% on a single-worker training run.
     */
    public static void increment(int counterIndex, boolean trueSuccessor) {
        int slot = counterIndex * COUNTERS_PER_BRANCH + (trueSuccessor ? TRUE_OFFSET : FALSE_OFFSET);
        if (SubstrateUtil.HOSTED) {
            hostedCounts[slot]++;
            return;
        }
        Pointer block = BranchProfileThreadCounters.BLOCK.get();
        int offset = slot * Long.BYTES;
        block.writeLong(offset, block.readLong(offset, COUNTER_LOCATION) + 1, COUNTER_LOCATION);
    }

    /** Address of shared slot 0; a link-time constant. */
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    static Pointer sharedCounts() {
        return SHARED_COUNTS.get();
    }

    /** Evaluated during image layout, after {@link #sealRegistry()}. */
    private static int sharedCountsBytes() {
        return Math.max(registeredSlots, COUNTERS_PER_BRANCH) * Long.BYTES;
    }

    /** Seals the site registry once compilation has created every counter. */
    public static void sealRegistry() {
        registeredSlots = nextCounterIndex.get() * COUNTERS_PER_BRANCH;
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    static int registeredSlots() {
        return registeredSlots;
    }

    /** Folds an exiting thread's private block into the shared totals. */
    @Uninterruptible(reason = "Called after Thread.exit; touches only global and native memory.")
    static void mergeIntoShared(Pointer block) {
        int slots = registeredSlots;
        Pointer shared = sharedCounts();
        for (int slot = 0; slot < slots; slot++) {
            long value = block.readLong(slot * Long.BYTES);
            if (value != 0) {
                UNSAFE.getAndAddLong(null, shared.add(slot * Long.BYTES).rawValue(), value);
            }
        }
    }

    /** Builds the merged view used while serializing; live threads are read at a safepoint. */
    private static void takeSnapshot() {
        if (SubstrateUtil.HOSTED) {
            snapshot = hostedCounts;
            return;
        }
        int slots = registeredSlots;
        long[] merged = new long[slots];
        Pointer shared = sharedCounts();
        for (int slot = 0; slot < slots; slot++) {
            merged[slot] = shared.readLong(slot * Long.BYTES);
        }
        BranchProfileThreadCounters.collectLiveThreads(merged);
        snapshot = merged;
    }

    private static long[] readableCounts() {
        if (SubstrateUtil.HOSTED) {
            return hostedCounts;
        }
        if (snapshot == null) {
            takeSnapshot();
        }
        return snapshot;
    }

    static long getTrueCount(int counterIndex) {
        return readableCounts()[counterIndex * COUNTERS_PER_BRANCH + TRUE_OFFSET];
    }

    static long getFalseCount(int counterIndex) {
        return readableCounts()[counterIndex * COUNTERS_PER_BRANCH + FALSE_OFFSET];
    }

    /** Deterministic physical-site snapshot used by the iprof serializer. */
    public static List<BranchProfileCounter> getCounters() {
        List<BranchProfileCounter> result = new ArrayList<>();
        for (BranchProfileCounter counter : counters.values()) {
            if (counter != unusedCounter) {
                result.add(counter);
            }
        }
        result.sort(COUNTER_COMPARATOR);
        return result;
    }

    public static RuntimeSupport.Hook getTeardownHook() {
        return _ -> dumpProfile();
    }

    /** Replaces {@code %p} with the process id and {@code %t} with the current time in milliseconds. */
    static String expandDumpFilePattern(String pattern) {
        if (pattern.indexOf('%') < 0) {
            return pattern;
        }
        return pattern.replace("%p", Long.toString(ProcessHandle.current().pid())).replace("%t", Long.toString(System.currentTimeMillis()));
    }

    public static void dumpProfile() {
        /* The placeholder site is excluded from output; touching it keeps the path analysis-visible. */
        increment(unusedCounter.getCounterIndex(), true);
        increment(unusedCounter.getCounterIndex(), false);
        takeSnapshot();

        String fileName = Options.ProfilesDumpFile.getValue();
        if (fileName == null || fileName.isEmpty()) {
            fileName = "default.iprof";
        }
        fileName = expandDumpFilePattern(fileName);
        try {
            List<StackSampleRecorder.DecodedSample> stackSamples = StackSampleRecorder.hasSamples() ? StackSampleRecorder.decodeSamples() : List.of();
            List<ReceiverProfileRecorder.DecodedReceiverProfile> receiverProfiles = ReceiverProfileRecorder.isEnabled() ? ReceiverProfileRecorder.decodeProfiles() : List.of();
            if (CallCountProfileRecorder.isEnabled()) {
                CallCountProfileRecorder.prepareSnapshot();
            }
            List<CallCountProfileCounter> callCountProfiles = CallCountProfileRecorder.isEnabled() ? CallCountProfileRecorder.getCounters() : List.of();
            List<SwitchProfileCounter> switchProfiles = SwitchProfileRecorder.isEnabled() ? SwitchProfileRecorder.profiles() : List.of();
            BranchProfileIprofWriter.DumpStatistics statistics = BranchProfileIprofWriter.write(Path.of(fileName), getCounters(), stackSamples, receiverProfiles, callCountProfiles, switchProfiles);
            Log.log().string("[PGO] wrote profile '").string(fileName).string("': legacy contexts=").signed(statistics.conditionalProfiles())
                            .string(", v2 sites=").signed(statistics.preciseConditionalProfiles())
                            .string(", receiver contexts=").signed(statistics.receiverProfiles())
                            .string(", call-count contexts=").signed(statistics.callCountProfiles())
                            .string(", switch contexts=").signed(statistics.switchProfiles())
                            .string(", methods=").signed(statistics.methods())
                            .string(", types=").signed(statistics.types())
                            .string(", events=").signed(statistics.recordedEvents()).newline();
            if (StackSampleRecorder.hasSamples()) {
                StackSampleRecorder.Statistics sampling = StackSampleRecorder.statistics(stackSamples.size());
                Log.log().string("[PGO] stack samples=").signed(sampling.samples()).string(", threads=").signed(sampling.tables())
                                .string(", decoded stacks=").signed(sampling.decodedStacks()).string(", truncated=").signed(sampling.truncated())
                                .string(", dropped=").signed(sampling.dropped()).string(", unresolved addresses=").signed(sampling.unresolvedAddresses()).newline();
            }
            if (ReceiverProfileRecorder.isEnabled()) {
                ReceiverProfileRecorder.Statistics receivers = ReceiverProfileRecorder.statistics();
                Log.log().string("[PGO] receiver profiles: physical sites=").signed(receivers.physicalSites()).string(", events=").signed(receivers.events())
                                .string(", dropped=").signed(receivers.dropped()).string(", unknown=").signed(receivers.unknown()).newline();
            }
            if (CallCountProfileRecorder.isEnabled()) {
                CallCountProfileRecorder.Statistics calls = CallCountProfileRecorder.statistics();
                Log.log().string("[PGO] call counts: physical sites=").signed(calls.physicalSites()).string(", events=").signed(calls.events())
                                .string(", attributed=").signed(calls.attributedEvents()).string(", keys=").signed(calls.keys()).string(", mapped keys=").signed(calls.mappedKeys())
                                .string(", dropped=").signed(calls.dropped()).newline();
            }
            SplitHistogramRecorder.dumpSummary();
        } catch (IOException | RuntimeException exception) {
            Log.log().string("[PGO] could not write conditional profile '").string(fileName).string("': ").exception(exception).newline();
        }
    }

    public static void dumpSummary() {
        takeSnapshot();
        long trueCount = 0;
        long falseCount = 0;
        List<BranchProfileCounter> snapshot = getCounters();
        for (BranchProfileCounter counter : snapshot) {
            trueCount += counter.getTrueCount();
            falseCount += counter.getFalseCount();
        }
        Log.log().string("[PGO] recorded conditional branches: physical sites=").signed(snapshot.size())
                        .string(", true=").signed(trueCount)
                        .string(", false=").signed(falseCount)
                        .string(", total=").signed(trueCount + falseCount).newline();
    }

    private static final Comparator<BranchProfileCounter> COUNTER_COMPARATOR = (left, right) -> {
        int result = left.getStage().compareTo(right.getStage());
        if (result == 0) {
            result = compare(left.getMethodDescriptors(), right.getMethodDescriptors());
        }
        if (result == 0) {
            result = compare(left.getContextBcis(), right.getContextBcis());
        }
        if (result == 0) {
            result = Integer.compare(left.getTrueSuccessorBci(), right.getTrueSuccessorBci());
        }
        if (result == 0) {
            result = Integer.compare(left.getFalseSuccessorBci(), right.getFalseSuccessorBci());
        }
        if (result == 0) {
            result = left.getConditionKind().compareTo(right.getConditionKind());
        }
        if (result == 0) {
            result = Long.compareUnsigned(left.getConditionFingerprint(), right.getConditionFingerprint());
        }
        if (result == 0) {
            result = Integer.compare(left.getOccurrence(), right.getOccurrence());
        }
        return result;
    };

    private static int compare(String[] left, String[] right) {
        int length = Math.min(left.length, right.length);
        for (int i = 0; i < length; i++) {
            int result = left[i].compareTo(right[i]);
            if (result != 0) {
                return result;
            }
        }
        return Integer.compare(left.length, right.length);
    }

    private static int compare(int[] left, int[] right) {
        int length = Math.min(left.length, right.length);
        for (int i = 0; i < length; i++) {
            int result = Integer.compare(left[i], right[i]);
            if (result != 0) {
                return result;
            }
        }
        return Integer.compare(left.length, right.length);
    }
}
