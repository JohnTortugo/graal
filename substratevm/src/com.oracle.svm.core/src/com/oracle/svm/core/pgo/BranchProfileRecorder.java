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
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.oracle.svm.guest.staging.jdk.RuntimeSupport;
import com.oracle.svm.guest.staging.log.Log;
import com.oracle.svm.guest.staging.option.RuntimeOptionKey;

import jdk.graal.compiler.options.Option;

/** Build-time registry and relocation-safe runtime storage for conditional branch counters. */
public final class BranchProfileRecorder {

    public static final class Options {
        @Option(help = "File path for the conditional branch profile produced by a --pgo-instrument image.")//
        public static final RuntimeOptionKey<String> ProfilesDumpFile = new RuntimeOptionKey<>("default.iprof");
    }

    private static final int MAX_BRANCHES = 1_000_000;
    private static final int COUNTERS_PER_BRANCH = 2;
    private static final int TRUE_OFFSET = 0;
    private static final int FALSE_OFFSET = 1;

    /** Populated concurrently while hosted compilation processes graphs. */
    private static final ConcurrentMap<BranchKey, BranchProfileCounter> counters = new ConcurrentHashMap<>();
    private static final AtomicInteger nextCounterIndex = new AtomicInteger();

    /*
     * Generated code embeds only an integer index into this analysis-visible array. This avoids
     * embedding hosted-created counter objects as unrelocated image-heap constants.
     */
    private static final long[] runtimeCounts = new long[MAX_BRANCHES * COUNTERS_PER_BRANCH];

    /*
     * Real metadata objects are created only during compilation, after static analysis. This
     * placeholder ensures all registry, key, counter, String-array, and int-array types are
     * reachable. It also makes the shared array writes runtime-reachable from the teardown hook.
     */
    private static final BranchProfileCounter unusedCounter = lookup(
                    new String[]{"Lcom/oracle/svm/core/pgo/BranchProfileRecorder;.__unused__()V"},
                    new int[]{-1}, -1, -1);

    private BranchProfileRecorder() {
    }

    public static BranchProfileCounter lookup(String[] methodDescriptors, int[] contextBcis, int trueSuccessorBci, int falseSuccessorBci) {
        BranchKey key = new BranchKey(methodDescriptors, contextBcis, trueSuccessorBci, falseSuccessorBci);
        return counters.computeIfAbsent(key, BranchProfileRecorder::createCounter);
    }

    private static BranchProfileCounter createCounter(BranchKey key) {
        int index = nextCounterIndex.getAndIncrement();
        if (index >= MAX_BRANCHES) {
            throw new IllegalStateException("Conditional branch instrumentation exceeds the " + MAX_BRANCHES + "-site capacity");
        }
        return new BranchProfileCounter(key.methodDescriptors, key.contextBcis, key.trueSuccessorBci, key.falseSuccessorBci, index);
    }

    /** Called by generated code. Counts are intentionally relaxed: profiles are approximate. */
    public static void increment(int counterIndex, boolean trueSuccessor) {
        int offset = trueSuccessor ? TRUE_OFFSET : FALSE_OFFSET;
        runtimeCounts[counterIndex * COUNTERS_PER_BRANCH + offset]++;
    }

    static long getTrueCount(int counterIndex) {
        return runtimeCounts[counterIndex * COUNTERS_PER_BRANCH + TRUE_OFFSET];
    }

    static long getFalseCount(int counterIndex) {
        return runtimeCounts[counterIndex * COUNTERS_PER_BRANCH + FALSE_OFFSET];
    }

    /** Deterministic snapshot used first by the smoke summary and later by the iprof serializer. */
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

    /** Writes the iprof file and reports a concise deterministic summary. */
    public static void dumpProfile() {
        /* Keep the shared array write runtime-reachable; this placeholder is excluded below. */
        increment(unusedCounter.getCounterIndex(), true);
        increment(unusedCounter.getCounterIndex(), false);

        String fileName = Options.ProfilesDumpFile.getValue();
        if (fileName == null || fileName.isEmpty()) {
            fileName = "default.iprof";
        }
        try {
            BranchProfileIprofWriter.DumpStatistics statistics = BranchProfileIprofWriter.write(Path.of(fileName), getCounters());
            Log.log().string("[PGO] wrote conditional profile '").string(fileName).string("': sites=").signed(statistics.conditionalProfiles())
                            .string(", methods=").signed(statistics.methods())
                            .string(", types=").signed(statistics.types())
                            .string(", events=").signed(statistics.recordedEvents()).newline();
        } catch (IOException | RuntimeException exception) {
            Log.log().string("[PGO] could not write conditional profile '").string(fileName).string("': ").string(exception.getMessage()).newline();
        }
    }

    /** Diagnostic helper retained for targeted runtime checks. */
    public static void dumpSummary() {
        long trueCount = 0;
        long falseCount = 0;
        List<BranchProfileCounter> snapshot = getCounters();
        for (BranchProfileCounter counter : snapshot) {
            trueCount += counter.getTrueCount();
            falseCount += counter.getFalseCount();
        }
        Log.log().string("[PGO] recorded conditional branches: sites=").signed(snapshot.size())
                        .string(", true=").signed(trueCount)
                        .string(", false=").signed(falseCount)
                        .string(", total=").signed(trueCount + falseCount).newline();
    }

    private static final Comparator<BranchProfileCounter> COUNTER_COMPARATOR = (left, right) -> {
        int result = compare(left.getMethodDescriptors(), right.getMethodDescriptors());
        if (result == 0) {
            result = compare(left.getContextBcis(), right.getContextBcis());
        }
        if (result == 0) {
            result = Integer.compare(left.getTrueSuccessorBci(), right.getTrueSuccessorBci());
        }
        if (result == 0) {
            result = Integer.compare(left.getFalseSuccessorBci(), right.getFalseSuccessorBci());
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

    private static final class BranchKey {
        private final String[] methodDescriptors;
        private final int[] contextBcis;
        private final int trueSuccessorBci;
        private final int falseSuccessorBci;
        private final int hashCode;

        private BranchKey(String[] methodDescriptors, int[] contextBcis, int trueSuccessorBci, int falseSuccessorBci) {
            if (methodDescriptors.length == 0 || methodDescriptors.length != contextBcis.length) {
                throw new IllegalArgumentException("Branch profile context must contain matching non-empty method and BCI arrays");
            }
            this.methodDescriptors = methodDescriptors.clone();
            this.contextBcis = contextBcis.clone();
            this.trueSuccessorBci = trueSuccessorBci;
            this.falseSuccessorBci = falseSuccessorBci;
            int hash = Arrays.hashCode(this.methodDescriptors);
            this.hashCode = 31 * hash + Arrays.hashCode(this.contextBcis);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof BranchKey key)) {
                return false;
            }
            return Arrays.equals(methodDescriptors, key.methodDescriptors) && Arrays.equals(contextBcis, key.contextBcis);
        }

        @Override
        public int hashCode() {
            return hashCode;
        }
    }
}
