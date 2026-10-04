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
package com.oracle.svm.hosted.pgo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.oracle.svm.hosted.pgo.IprofConditionalParser.CallCountEntry;
import com.oracle.svm.hosted.pgo.IprofConditionalParser.ConditionalEntry;
import com.oracle.svm.hosted.pgo.IprofConditionalParser.ContextFrame;
import com.oracle.svm.hosted.pgo.IprofConditionalParser.IprofFormatException;
import com.oracle.svm.hosted.pgo.IprofConditionalParser.MethodDescriptor;
import com.oracle.svm.hosted.pgo.IprofConditionalParser.ParsedProfile;
import com.oracle.svm.hosted.pgo.IprofConditionalParser.PreciseConditionalEntry;
import com.oracle.svm.hosted.pgo.IprofConditionalParser.SamplingEntry;
import com.oracle.svm.hosted.pgo.IprofConditionalParser.VirtualInvokeEntry;

/**
 * Merges several parsed iprof files into one, the way {@code llvm-profdata merge} and
 * {@code gcov-tool merge} combine training runs: type and method ids of every input are remapped
 * into one namespace by their canonical identity, and the counts of entries with the same identity
 * are added, each input scaled by its weight.
 *
 * Counts add correctly only across runs of the same instrumented image (or images whose contexts
 * still resolve); entries that no longer resolve are dropped by the resolver as for a single file.
 * The relative importance of each input is proportional to its counts, so a longer training run
 * weighs more; weights let the caller rebalance (a workload run for a quarter of the time of
 * another gets weight 4 to count the same).
 */
public final class IprofProfileMerger {

    /** One input of a merge: a parsed profile and the factor applied to all of its counts. */
    public record WeightedProfile(ParsedProfile profile, double weight) {
        public WeightedProfile {
            if (!(weight > 0.0)) {
                throw new IllegalArgumentException("Profile weight must be positive: " + weight);
            }
        }
    }

    private record MethodIdentity(String declaringType, String name, String returnType, List<String> parameterTypes) {
    }

    private record PreciseKey(String stage, List<ContextFrame> context, List<Integer> successors, String conditionKind, int occurrence) {
    }

    private final Map<String, Integer> typeIds = new LinkedHashMap<>();
    private final Map<MethodIdentity, Integer> methodIds = new LinkedHashMap<>();
    private final Map<Integer, MethodDescriptor> methodsById = new LinkedHashMap<>();
    private final Map<List<ContextFrame>, Map<Long, Long>> conditionals = new LinkedHashMap<>();
    private final Map<PreciseKey, long[]> preciseFingerprints = new HashMap<>();
    private final Map<PreciseKey, Map<Long, Long>> precise = new LinkedHashMap<>();
    private final Map<List<ContextFrame>, Long> callCounts = new LinkedHashMap<>();
    private final Map<List<ContextFrame>, Map<Integer, Long>> receivers = new LinkedHashMap<>();
    private final Map<List<ContextFrame>, Long> samples = new LinkedHashMap<>();
    private String version;

    private IprofProfileMerger() {
    }

    /**
     * Similarity of two profiles' sampling data in [0, 1], as {@code gcov-tool overlap} defines it
     * for arc counters: the sum over all methods of {@code min(a_i / sum(a), b_i / sum(b))} where
     * {@code a_i} is the inclusive sample count of method {@code i}. 1 means the two runs spent
     * their time in the same methods in the same proportions; values well below 1 mean the runs
     * exercise different code and the merged profile is a compromise between them.
     */
    public static double samplingOverlap(ParsedProfile a, ParsedProfile b) {
        Map<MethodIdentity, Long> countsA = inclusiveSamplesByMethod(a);
        Map<MethodIdentity, Long> countsB = inclusiveSamplesByMethod(b);
        double totalA = countsA.values().stream().mapToLong(Long::longValue).sum();
        double totalB = countsB.values().stream().mapToLong(Long::longValue).sum();
        if (totalA == 0 || totalB == 0) {
            return 0.0;
        }
        double overlap = 0.0;
        for (Map.Entry<MethodIdentity, Long> entry : countsA.entrySet()) {
            Long other = countsB.get(entry.getKey());
            if (other != null) {
                overlap += Math.min(entry.getValue() / totalA, other / totalB);
            }
        }
        return overlap;
    }

    private static Map<MethodIdentity, Long> inclusiveSamplesByMethod(ParsedProfile profile) {
        Map<MethodIdentity, Long> counts = new HashMap<>();
        for (SamplingEntry entry : profile.samplingEntries()) {
            java.util.Set<Integer> seen = new java.util.HashSet<>();
            for (ContextFrame frame : entry.context()) {
                if (seen.add(frame.methodId())) {
                    MethodDescriptor method = profile.methodsById().get(frame.methodId());
                    if (method != null) {
                        counts.merge(identity(profile, method), entry.count(), Long::sum);
                    }
                }
            }
        }
        return counts;
    }

    private static MethodIdentity identity(ParsedProfile profile, MethodDescriptor method) {
        return new MethodIdentity(typeName(profile, method.declaringTypeId()), method.name(), typeName(profile, method.returnTypeId()),
                        Arrays.stream(method.parameterTypeIds()).mapToObj(id -> typeName(profile, id)).toList());
    }

    public static ParsedProfile merge(List<WeightedProfile> inputs) {
        if (inputs.isEmpty()) {
            throw new IllegalArgumentException("At least one profile is required");
        }
        if (inputs.size() == 1 && inputs.get(0).weight() == 1.0) {
            return inputs.get(0).profile();
        }
        IprofProfileMerger merger = new IprofProfileMerger();
        for (WeightedProfile input : inputs) {
            merger.add(input.profile(), input.weight());
        }
        return merger.result();
    }

    private void add(ParsedProfile profile, double weight) {
        if (version == null) {
            version = profile.version();
        } else if (!version.equals(profile.version())) {
            throw new IprofFormatException("Cannot merge iprof versions " + version + " and " + profile.version());
        }
        Map<Integer, Integer> typeMap = new HashMap<>();
        for (Map.Entry<Integer, String> type : profile.typeNamesById().entrySet()) {
            typeMap.put(type.getKey(), typeIds.computeIfAbsent(type.getValue(), _ -> typeIds.size()));
        }
        Map<Integer, Integer> methodMap = new HashMap<>();
        for (MethodDescriptor method : profile.methodsById().values()) {
            MethodIdentity identity = identity(profile, method);
            Integer id = methodIds.get(identity);
            if (id == null) {
                id = methodIds.size();
                methodIds.put(identity, id);
                methodsById.put(id, new MethodDescriptor(id, method.name(), typeMap.get(method.declaringTypeId()), typeMap.get(method.returnTypeId()),
                                Arrays.stream(method.parameterTypeIds()).map(typeMap::get).toArray()));
            }
            methodMap.put(method.methodId(), id);
        }
        for (ConditionalEntry entry : profile.conditionalEntries()) {
            addRecords(conditionals.computeIfAbsent(remap(entry.context(), methodMap), _ -> new LinkedHashMap<>()), entry.records(), weight);
        }
        for (PreciseConditionalEntry entry : profile.preciseConditionalEntries()) {
            PreciseKey key = new PreciseKey(entry.stage(), remap(entry.context(), methodMap), Arrays.stream(entry.successorBcis()).boxed().toList(), entry.conditionKind(), entry.occurrence());
            preciseFingerprints.putIfAbsent(key, new long[]{entry.conditionFingerprint()});
            addRecords(precise.computeIfAbsent(key, _ -> new LinkedHashMap<>()), entry.records(), weight);
        }
        for (CallCountEntry entry : profile.callCountEntries()) {
            callCounts.merge(remap(entry.context(), methodMap), scale(entry.count(), weight), Long::sum);
        }
        for (VirtualInvokeEntry entry : profile.virtualInvokeEntries()) {
            Map<Integer, Long> counts = receivers.computeIfAbsent(remap(entry.context(), methodMap), _ -> new LinkedHashMap<>());
            long[] records = entry.records();
            for (int i = 0; i + 1 < records.length; i += 2) {
                counts.merge(typeMap.get((int) records[i]), scale(records[i + 1], weight), Long::sum);
            }
        }
        for (SamplingEntry entry : profile.samplingEntries()) {
            samples.merge(remap(entry.context(), methodMap), scale(entry.count(), weight), Long::sum);
        }
    }

    private static String typeName(ParsedProfile profile, int typeId) {
        String name = profile.typeNamesById().get(typeId);
        if (name == null) {
            throw new IprofFormatException("Method table refers to undeclared type id " + typeId);
        }
        return name;
    }

    private static List<ContextFrame> remap(List<ContextFrame> context, Map<Integer, Integer> methodMap) {
        List<ContextFrame> result = new ArrayList<>(context.size());
        for (ContextFrame frame : context) {
            Integer methodId = methodMap.get(frame.methodId());
            if (methodId == null) {
                throw new IprofFormatException("Context refers to undeclared method id " + frame.methodId());
            }
            result.add(new ContextFrame(methodId, frame.bci()));
        }
        return result;
    }

    /** Records are {@code [successorBci, branchIndex, count]} triples; the key packs the first two. */
    private static void addRecords(Map<Long, Long> target, long[] records, double weight) {
        for (int i = 0; i + 2 < records.length; i += 3) {
            long key = (records[i] << 32) | (records[i + 1] & 0xFFFFFFFFL);
            target.merge(key, scale(records[i + 2], weight), Long::sum);
        }
    }

    private static long[] toRecords(Map<Long, Long> counts) {
        long[] result = new long[counts.size() * 3];
        int i = 0;
        for (Map.Entry<Long, Long> entry : counts.entrySet()) {
            result[i++] = entry.getKey() >> 32;
            result[i++] = (int) (entry.getKey() & 0xFFFFFFFFL);
            result[i++] = entry.getValue();
        }
        return result;
    }

    private static long scale(long count, double weight) {
        return weight == 1.0 ? count : Math.round(count * weight);
    }

    private ParsedProfile result() {
        Map<Integer, String> typeNamesById = new LinkedHashMap<>();
        typeIds.forEach((name, id) -> typeNamesById.put(id, name));
        List<ConditionalEntry> conditionalEntries = new ArrayList<>();
        conditionals.forEach((context, counts) -> conditionalEntries.add(new ConditionalEntry(context, toRecords(counts))));
        List<PreciseConditionalEntry> preciseEntries = new ArrayList<>();
        precise.forEach((key, counts) -> preciseEntries.add(new PreciseConditionalEntry(key.stage(), key.context(), key.successors().stream().mapToInt(Integer::intValue).toArray(),
                        key.conditionKind(), preciseFingerprints.get(key)[0], key.occurrence(), toRecords(counts))));
        List<CallCountEntry> callCountEntries = new ArrayList<>();
        callCounts.forEach((context, count) -> callCountEntries.add(new CallCountEntry(context, count)));
        List<VirtualInvokeEntry> receiverEntries = new ArrayList<>();
        receivers.forEach((context, counts) -> {
            long[] records = new long[counts.size() * 2];
            int i = 0;
            for (Map.Entry<Integer, Long> entry : counts.entrySet()) {
                records[i++] = entry.getKey();
                records[i++] = entry.getValue();
            }
            receiverEntries.add(new VirtualInvokeEntry(context, records));
        });
        List<SamplingEntry> samplingEntries = new ArrayList<>();
        samples.forEach((context, count) -> samplingEntries.add(new SamplingEntry(context, count)));
        return new ParsedProfile(version, typeNamesById, methodsById, conditionalEntries, preciseEntries, callCountEntries, receiverEntries, samplingEntries);
    }
}
