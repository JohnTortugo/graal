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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import com.oracle.graal.pointsto.meta.AnalysisMethod;
import com.oracle.graal.pointsto.meta.AnalysisType;
import com.oracle.svm.hosted.cai.PrefixTree;
import com.oracle.svm.hosted.meta.HostedMethod;

import jdk.graal.compiler.graph.NodeSourcePosition;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import jdk.vm.ci.code.BytecodePosition;
import jdk.vm.ci.meta.JavaType;

/**
 * Method-rooted view of sampled call stacks.
 *
 * {@link PrefixTree} organises samples as a calling-context tree whose entry points are the outermost
 * frames, and hands the inliner a {@link PrefixTree.Cursor} for each compilation root. Selecting
 * compilation roots per calling context is not available here, so every method that appears in a
 * sampled stack becomes its own entry point: for each stack and each frame in it, the suffix starting
 * at that frame is added to the tree rooted at that frame's method. The resulting per-method tree
 * merges all contexts the method was seen in, which is exactly what a context-insensitive compilation
 * unit needs (callee hotness relative to the compilation root, and which callees were observed at an
 * indirect call) while remaining context-sensitive below the root.
 *
 * Self time is the share of samples whose innermost frame is in the method; a method is a hot caller
 * if it appears anywhere in a sampled stack.
 */
public final class SamplingHotness {

    private final PrefixTree methodRootedTree;
    private final Map<AnalysisMethod, Long> selfSamples = new HashMap<>();
    private final long totalSamples;
    private final long idleSamples;
    private final AtomicLong hotCompilationUnits = new AtomicLong();
    private final AtomicLong coldCompilationUnits = new AtomicLong();

    /**
     * Leaf frames that consume no processor time. A sampler that walks every thread also samples
     * threads parked in these, and on the reference workload a single idle monitoring thread
     * accounted for half of all samples. Leaving them in the denominator halves every method's
     * reported self time, which is the signal the compiler uses to decide what counts as globally
     * hot code, so they are excluded. The blocking primitives are matched by declaring class as well
     * as name, so an application method that happens to be called {@code sleep} still counts.
     */
    private static final Set<String> IDLE_LEAF_CLASSES = Set.of(
                    "com.oracle.svm.core.thread.PlatformThreads",
                    "com.oracle.svm.core.thread.JavaThreads",
                    "java.lang.Thread",
                    "jdk.internal.misc.Unsafe",
                    "sun.misc.Unsafe",
                    "java.util.concurrent.locks.LockSupport");

    private static final Set<String> IDLE_LEAF_METHODS = Set.of("sleep", "sleep0", "sleepNanos", "sleepNanos0", "beforeSleep", "afterSleep",
                    "park", "park0", "parkNanos", "parkUntil", "parkCurrentPlatformOrCarrierThread", "wait", "wait0", "onSpinWait", "yield", "yield0");

    public SamplingHotness(Map<NodeSourcePosition, Long> stackSamples) {
        Map<NodeSourcePosition, Long> suffixes = new HashMap<>();
        long total = 0;
        long idle = 0;
        for (Map.Entry<NodeSourcePosition, Long> entry : stackSamples.entrySet()) {
            long count = entry.getValue();
            List<NodeSourcePosition> outermostFirst = new ArrayList<>();
            for (NodeSourcePosition position : entry.getKey()) {
                outermostFirst.add(position);
            }
            NodeSourcePosition leaf = outermostFirst.getLast();
            if (isIdle(leaf)) {
                idle += count;
                continue;
            }
            total += count;
            selfSamples.merge((AnalysisMethod) leaf.getMethod(), count, Long::sum);
            /* Every frame's suffix (that frame down to the leaf) is a stack rooted at that frame. */
            for (int start = 0; start < outermostFirst.size(); start++) {
                NodeSourcePosition suffix = null;
                for (int i = outermostFirst.size() - 1; i >= start; i--) {
                    NodeSourcePosition frame = outermostFirst.get(i);
                    suffix = new NodeSourcePosition(suffix, frame.getMethod(), frame.getBCI());
                }
                suffixes.merge(suffix, count, Long::sum);
            }
        }
        this.totalSamples = total;
        this.idleSamples = idle;
        this.methodRootedTree = new PrefixTree(new SampleSource(suffixes));
    }

    /** Cursor for a compilation unit rooted at {@code method}, or null if the method was never sampled. */
    public PrefixTree.Cursor cursorFor(HostedMethod method) {
        PrefixTree.Node node = methodRootedTree.entryPoints().get(method.getWrapped());
        if (node == null) {
            coldCompilationUnits.incrementAndGet();
        } else {
            hotCompilationUnits.incrementAndGet();
        }
        return node;
    }

    public boolean isSampled(HostedMethod method) {
        return methodRootedTree.hasMethod(method);
    }

    /** Fraction in [0, 1] of all samples whose innermost frame lies in {@code method}. */
    public double selfTimeShare(HostedMethod method) {
        if (totalSamples == 0) {
            return 0.0;
        }
        return (double) selfSamples.getOrDefault(method.getWrapped(), 0L) / totalSamples;
    }

    private static boolean isIdle(NodeSourcePosition leaf) {
        ResolvedJavaMethod method = leaf.getMethod();
        return method != null && IDLE_LEAF_METHODS.contains(method.getName()) &&
                        IDLE_LEAF_CLASSES.contains(method.getDeclaringClass().toJavaName());
    }

    /** Samples discarded because the sampled thread was parked in a method that uses no processor time. */
    public long idleSamples() {
        return idleSamples;
    }

    public long totalSamples() {
        return totalSamples;
    }

    public int sampledMethodCount() {
        return methodRootedTree.entryPoints().size();
    }

    public long hotCompilationUnits() {
        return hotCompilationUnits.get();
    }

    public long coldCompilationUnits() {
        return coldCompilationUnits.get();
    }

    /** Per-graph provider consulted by the inliner, duplication, and hot-callee devirtualization. */
    public StructuredGraph.GlobalProfileProvider providerFor(HostedMethod method) {
        double selfTime = SamplingInliningProvider.Options.PGOSamplingSelfTime.getValue() ? selfTimeShare(method) : StructuredGraph.GlobalProfileProvider.GLOBAL_PROFILE_PROVIDER_DISABLED;
        boolean hot = SamplingInliningProvider.Options.PGOSamplingHotCaller.getValue() && isSampled(method);
        return new StructuredGraph.GlobalProfileProvider() {
            @Override
            public double getGlobalSelfTimePercent() {
                return selfTime;
            }

            @Override
            public boolean hotCaller() {
                return hot;
            }
        };
    }

    /** Minimal lookup that only carries the expanded sample map into {@link PrefixTree}. */
    private static final class SampleSource implements PGOProfilesLookup {
        private final Map<NodeSourcePosition, Long> samples;

        SampleSource(Map<NodeSourcePosition, Long> samples) {
            this.samples = samples;
        }

        @Override
        public Optional<Map<NodeSourcePosition, Long>> getSampleCounts() {
            return Optional.of(samples);
        }

        @Override
        public boolean profileCategoryRecorded(String category) {
            return SimpleConditionalProfilesLookup.SAMPLING_PROFILES_CATEGORY.equals(category);
        }

        @Override
        public Optional<ProfiledValue<Long>> getCallCountProfile(HostedMethod method) {
            return Optional.empty();
        }

        @Override
        public long getCallCountOrZero(HostedMethod method) {
            return 0;
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
        public Optional<ProfiledValue<long[]>> getConditionalProfile(BytecodePosition callingContext) {
            return Optional.empty();
        }

        @Override
        public Optional<ProfiledValue<Long>> getTotalConditionalProfileValue(HostedMethod method) {
            return Optional.empty();
        }

        @Override
        public long getTotalConditionalProfileValueOrZero(HostedMethod method) {
            return 0;
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
}
