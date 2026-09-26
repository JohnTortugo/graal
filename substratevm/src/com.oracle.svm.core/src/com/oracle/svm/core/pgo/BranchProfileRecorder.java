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
        @Option(help = "File path for the conditional branch profile produced by a --pgo-instrument image.")//
        public static final RuntimeOptionKey<String> ProfilesDumpFile = new RuntimeOptionKey<>("default.iprof");
    }

    private static final int MAX_BRANCHES = 1_000_000;
    private static final int COUNTERS_PER_BRANCH = 2;
    private static final int TRUE_OFFSET = 0;
    private static final int FALSE_OFFSET = 1;

    private static final Unsafe UNSAFE = Unsafe.getUnsafe();
    /** Private counter blocks are never aliased with Java heap memory, so reads can be hoisted. */
    private static final LocationIdentity COUNTER_LOCATION = NamedLocationIdentity.mutable("PGOBranchCounters");

    /** Every selected physical graph site owns a counter; identity aggregation happens at dump. */
    private static final ConcurrentMap<Integer, BranchProfileCounter> counters = new ConcurrentHashMap<>();
    private static final AtomicInteger nextCounterIndex = new AtomicInteger();

    /**
     * Shared totals: fallback target for code running on a thread without a private block, and the
     * accumulator that exited threads fold their private counts into.
     */
    private static final long[] runtimeCounts = new long[MAX_BRANCHES * COUNTERS_PER_BRANCH];

    /** Number of long slots in a per-thread block; final once compilation has registered all sites. */
    @UnknownPrimitiveField(availability = AfterCompilation.class) //
    private static int registeredSlots;

    /** Merged view (shared + live threads) that the serializer reads; null outside a dump. */
    private static long[] snapshot;

    /* Makes all late-created metadata types and the runtime array write reachable to analysis. */
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

    /** Called by generated code. Counts are intentionally relaxed: profiles are approximate. */
    public static void increment(int counterIndex, boolean trueSuccessor) {
        int slot = counterIndex * COUNTERS_PER_BRANCH + (trueSuccessor ? TRUE_OFFSET : FALSE_OFFSET);
        if (!SubstrateUtil.HOSTED) {
            Pointer block = BranchProfileThreadCounters.COUNTERS.get();
            if (block.isNonNull()) {
                int offset = slot * Long.BYTES;
                block.writeLong(offset, block.readLong(offset, COUNTER_LOCATION) + 1, COUNTER_LOCATION);
                return;
            }
        }
        runtimeCounts[slot]++;
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
    @Uninterruptible(reason = "Called after Thread.exit; touches only image-heap and native memory.")
    static void mergeIntoShared(Pointer block) {
        int slots = registeredSlots;
        for (int slot = 0; slot < slots; slot++) {
            long value = block.readLong(slot * Long.BYTES);
            if (value != 0) {
                UNSAFE.getAndAddLong(runtimeCounts, Unsafe.ARRAY_LONG_BASE_OFFSET + (long) slot * Long.BYTES, value);
            }
        }
    }

    /** Builds the merged view used while serializing; live threads are read at a safepoint. */
    private static void takeSnapshot() {
        int slots = SubstrateUtil.HOSTED ? nextCounterIndex.get() * COUNTERS_PER_BRANCH : registeredSlots;
        long[] merged = new long[slots];
        System.arraycopy(runtimeCounts, 0, merged, 0, slots);
        if (!SubstrateUtil.HOSTED) {
            BranchProfileThreadCounters.collectLiveThreads(merged);
        }
        snapshot = merged;
    }

    private static long[] readableCounts() {
        return snapshot != null ? snapshot : runtimeCounts;
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

    public static void dumpProfile() {
        /* Keep the shared array write runtime-reachable; this placeholder is excluded from output. */
        increment(unusedCounter.getCounterIndex(), true);
        increment(unusedCounter.getCounterIndex(), false);
        takeSnapshot();

        String fileName = Options.ProfilesDumpFile.getValue();
        if (fileName == null || fileName.isEmpty()) {
            fileName = "default.iprof";
        }
        try {
            BranchProfileIprofWriter.DumpStatistics statistics = BranchProfileIprofWriter.write(Path.of(fileName), getCounters());
            Log.log().string("[PGO] wrote conditional profile '").string(fileName).string("': legacy contexts=").signed(statistics.conditionalProfiles())
                            .string(", v2 sites=").signed(statistics.preciseConditionalProfiles())
                            .string(", methods=").signed(statistics.methods())
                            .string(", types=").signed(statistics.types())
                            .string(", events=").signed(statistics.recordedEvents()).newline();
        } catch (IOException | RuntimeException exception) {
            Log.log().string("[PGO] could not write conditional profile '").string(fileName).string("': ").string(exception.getMessage()).newline();
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
