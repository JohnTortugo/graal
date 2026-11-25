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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.oracle.svm.hosted.meta.HostedMethod;
import com.oracle.graal.pointsto.meta.AnalysisMethod;
import com.oracle.graal.pointsto.meta.AnalysisType;
import com.oracle.svm.hosted.meta.HostedType;
import com.oracle.svm.hosted.meta.HostedUniverse;
import com.oracle.svm.hosted.pgo.IprofParser.CallCountEntry;
import com.oracle.svm.hosted.pgo.IprofParser.ConditionalEntry;
import com.oracle.svm.hosted.pgo.IprofParser.ContextFrame;
import com.oracle.svm.hosted.pgo.IprofParser.MethodDescriptor;
import com.oracle.svm.hosted.pgo.IprofParser.ParsedIprofProfile;
import com.oracle.svm.hosted.pgo.IprofParser;
import com.oracle.svm.hosted.pgo.IprofParser.SamplingEntry;
import com.oracle.svm.hosted.pgo.IprofParser.VirtualInvokeEntry;
import com.oracle.svm.hosted.pgo.ProfilingUtilities;
import com.oracle.svm.hosted.pgo.phases.PGOApplyProfilesPhase;
import com.oracle.svm.hosted.pgo.profiles.PGOContext.Frame;

import jdk.graal.compiler.graph.NodeSourcePosition;
import jdk.vm.ci.meta.MetaUtil;

/**
 * Resolves the entries of a {@link ParsedIprofProfile} against a {@link HostedUniverse}: its
 * branch and switch, receiver, call-count and stack-sample entries. It produces the immutable
 * tables that {@link IprofProfilesLookup} serves to the compiler.
 *
 * <p>
 * The core idea is a single canonical method-identity string in JVM-descriptor form, such as
 * {@code Ljava/lang/String;.charAt(I)C}, computed the same way from two sources:
 * <ul>
 * <li>from the iprof {@code methods}/{@code types} tables ({@link #descriptorForProfileMethod}), and
 * <li>from a resolved method at query time and during universe indexing
 * ({@link ProfilingUtilities#methodDescriptor}, which the instrumented build also uses to record
 * the contexts).
 * </ul>
 * Because both paths yield identical strings, calling contexts recorded in the profile line up with
 * the {@link jdk.vm.ci.code.BytecodePosition} chains the compiler passes to the lookup, without
 * relying on {@code NodeSourcePosition} identity.
 */
public final class IprofProfileResolver {

    private IprofProfileResolver() {
    }

    /**
     * Builds the canonical descriptor for an iprof method entry, converting the profile's type
     * names ({@link Class#getName()} form) into JVM descriptors so the result matches
     * {@link ProfilingUtilities#methodDescriptor}. For example, method {@code charAt} with the
     * signature type ids of {@code java.lang.String}, {@code char} and {@code int} gives
     * {@code Ljava/lang/String;.charAt(I)C}.
     *
     * @return the descriptor, or {@code null} if a referenced type id is missing.
     */
    public static String descriptorForProfileMethod(MethodDescriptor method, Map<Integer, String> typeNamesById) {
        String declaring = descriptorForTypeId(method.declaringTypeId(), typeNamesById);
        String returnType = descriptorForTypeId(method.returnTypeId(), typeNamesById);
        if (declaring == null || returnType == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(declaring).append('.').append(method.name()).append('(');
        for (int paramTypeId : method.parameterTypeIds()) {
            String param = descriptorForTypeId(paramTypeId, typeNamesById);
            if (param == null) {
                return null;
            }
            sb.append(param);
        }
        sb.append(')').append(returnType);
        return sb.toString();
    }

    /**
     * The descriptor of a profile type, as JVMCI writes it: {@code I}, {@code [I},
     * {@code Ljava/lang/String;} and {@code LFoo$$Lambda.0x1234;} for {@code int}, {@code [I},
     * {@code java.lang.String} and the hidden class {@code Foo$$Lambda/0x1234}.
     */
    private static String descriptorForTypeId(int typeId, Map<Integer, String> typeNamesById) {
        String name = typeNamesById.get(typeId);
        return name == null ? null : MetaUtil.toInternalName(name);
    }

    /**
     * Resolves the parsed profile against the universe and builds the lookup table.
     *
     * @param collectDropped whether to keep a readable context of every entry that is dropped,
     *            for {@code PGOPrintProfileQualityDetails}
     * @return a fully constructed, immutable lookup.
     */
    public static IprofProfilesLookup resolve(ParsedIprofProfile profile, HostedUniverse universe, boolean collectDropped) {
        Set<String> ambiguousMethods = new HashSet<>();
        Set<String> ambiguousTypes = new HashSet<>();
        Map<String, HostedMethod> methods = indexUniverseMethods(universe, ambiguousMethods);
        Map<String, HostedMethod> methodsByStackTraceName = indexStackTraceNames(universe, unresolvedSampleFrames(profile, methods.keySet(), ambiguousMethods));
        return buildLookup(profile, methods, methodsByStackTraceName, indexUniverseTypes(universe, ambiguousTypes), ambiguousMethods.size(), ambiguousTypes.size(),
                        collectDropped ? new ArrayList<>() : null);
    }

    static IprofProfilesLookup buildLookup(ParsedIprofProfile profile, Map<String, HostedMethod> methodsByDescriptor, Map<String, AnalysisType> typesByDescriptor) {
        return buildLookup(profile, methodsByDescriptor, typesByDescriptor, null);
    }

    static IprofProfilesLookup buildLookup(ParsedIprofProfile profile, Map<String, HostedMethod> methodsByDescriptor, Map<String, AnalysisType> typesByDescriptor,
                    List<String> dropped) {
        return buildLookup(profile, methodsByDescriptor, Map.of(), typesByDescriptor, 0, 0, dropped);
    }

    /**
     * Builds the lookup and diagnostics from a parsed profile and the set of canonical method
     * descriptors present in the image. This is the universe-independent core of {@link #resolve}
     * and is directly unit-testable.
     *
     * @param profile the parsed profile.
     * @param methodsByDescriptor methods available in the image, keyed by their canonical
     *            descriptor (see {@link ProfilingUtilities#methodDescriptor}).
     * @param methodsByStackTraceName image methods whose frames the sampler names differently,
     *            keyed by that name (see {@link #indexStackTraceNames}); used for stack samples
     *            only.
     * @param typesByDescriptor types available in the image, keyed by their descriptor.
     * @param ambiguousMethods how many method descriptors name more than one image method.
     * @param ambiguousTypes how many type descriptors name more than one image type.
     * @param dropped if not {@code null}, receives a readable context of every dropped entry.
     */
    static IprofProfilesLookup buildLookup(ParsedIprofProfile profile, Map<String, HostedMethod> methodsByDescriptor, Map<String, HostedMethod> methodsByStackTraceName,
                    Map<String, AnalysisType> typesByDescriptor, int ambiguousMethods, int ambiguousTypes, List<String> dropped) {
        Set<String> presentDescriptors = methodsByDescriptor.keySet();
        Map<Integer, String> descriptorByMethodId = buildProfileMethodDescriptors(profile);
        Map<PGOContext, long[]> conditionalTable = new HashMap<>();
        int resolved = 0;
        int unresolved = 0;
        int duplicates = 0;
        int singleFrame = 0;
        int inlined = 0;
        for (ConditionalEntry entry : profile.conditionalEntries()) {
            PGOContext key = canonicalKey(entry.context(), descriptorByMethodId, presentDescriptors);
            if (key == null) {
                unresolved++;
                addDropped(dropped, "branch or switch", entry.context(), descriptorByMethodId, presentDescriptors);
                continue;
            }
            /* The first entry for a context wins. */
            if (conditionalTable.putIfAbsent(key, entry.records()) != null) {
                duplicates++;
                addDropped(dropped, "branch or switch, duplicate context", entry.context(), descriptorByMethodId, presentDescriptors);
                continue;
            }
            resolved++;
            if (key.depth() == 1) {
                singleFrame++;
            } else {
                inlined++;
            }
        }

        ConditionalProfileDiagnostics conditionalDiagnostics = new ConditionalProfileDiagnostics(
                        profile.version(), profile.conditionalEntries().size(), resolved, unresolved, duplicates, singleFrame, inlined);
        Set<PGOContext> overflowedPrefixes = new HashSet<>();
        Map<PGOContext, long[]> prefixSums = prefixSums(conditionalTable, overflowedPrefixes);

        Map<PGOContext, Map<AnalysisType, Long>> virtualInvokeTable = new HashMap<>();
        VirtualInvokeDiagnostics virtualDiagnostics = resolveVirtualInvokes(profile, descriptorByMethodId, presentDescriptors, typesByDescriptor, virtualInvokeTable, dropped);
        Map<NodeSourcePosition, Long> sampleCounts = new HashMap<>();
        Map<String, HostedMethod> sampleMethods = methodsByDescriptor;
        if (!methodsByStackTraceName.isEmpty()) {
            sampleMethods = new HashMap<>(methodsByDescriptor);
            methodsByStackTraceName.forEach(sampleMethods::putIfAbsent);
        }
        SamplingDiagnostics samplingDiagnostics = resolveSamples(profile, descriptorByMethodId, sampleMethods, sampleCounts, dropped);
        Map<PGOContext, Long> callCountsByContext = new HashMap<>();
        Map<String, Long> callCountsByMethod = new HashMap<>();
        CallCountDiagnostics callCountDiagnostics = resolveCallCounts(profile, descriptorByMethodId, presentDescriptors, callCountsByContext, callCountsByMethod, dropped);
        PGODiagnostics diagnostics = new PGODiagnostics(conditionalDiagnostics, virtualDiagnostics, samplingDiagnostics, callCountDiagnostics, ambiguousMethods, ambiguousTypes,
                        overflowedPrefixes.size(), dropped == null ? List.of() : dropped);
        return new IprofProfilesLookup(conditionalTable, prefixSums, virtualInvokeTable, sampleCounts, callCountsByMethod, callCountsByContext, diagnostics);
    }

    /** A branch or switch arm of a conditional record: its successor bci and successor index. */
    private record Arm(long successorBci, long successorIndex) {
    }

    /**
     * Adds every entry's records to each prefix of its context, including the full context. A
     * record is {@link PGOApplyProfilesPhase#CONDITIONAL_RECORD_SIZE} longs: successor bci,
     * successor index and count. The counts of one arm are summed; arms keep the order of their
     * first record. For example, the entries {@code foo:12 < a:5 -> [14, 0, 9, 17, 1, 1]} and
     * {@code foo:12 < b:7 -> [14, 0, 2, 17, 1, 8]} give {@code foo:12 -> [14, 0, 11, 17, 1, 9]}
     * and keep both full contexts.
     * <p>
     * {@code PGOApplyProfilesPhase} adds up the counts of a record in a long. The parser and the
     * merger make sure that the counts of one entry fit, but the sum over several entries need not.
     * A prefix whose counts would not fit is left out and added to {@code overflowed}: the fallback
     * then finds no profile for it, rather than one with wrapped-around counts.
     */
    static Map<PGOContext, long[]> prefixSums(Map<PGOContext, long[]> conditionalData, Set<PGOContext> overflowed) {
        Map<PGOContext, Map<Arm, Long>> sums = new HashMap<>();
        Map<PGOContext, Long> totals = new HashMap<>();
        for (Map.Entry<PGOContext, long[]> entry : conditionalData.entrySet()) {
            PGOContext context = entry.getKey();
            long[] records = entry.getValue();
            long entryTotal = 0;
            for (int i = 0; i + 2 < records.length; i += PGOApplyProfilesPhase.CONDITIONAL_RECORD_SIZE) {
                entryTotal += records[i + 2];
            }
            for (int depth = 1; depth <= context.depth(); depth++) {
                PGOContext prefix = context.prefix(depth);
                if (overflowed.contains(prefix)) {
                    continue;
                }
                long total = totals.getOrDefault(prefix, 0L);
                if (total > Long.MAX_VALUE - entryTotal) {
                    overflowed.add(prefix);
                    totals.remove(prefix);
                    sums.remove(prefix);
                    continue;
                }
                totals.put(prefix, total + entryTotal);
                /* No arm can overflow: the counts of each arm add up to at most the total. */
                Map<Arm, Long> counts = sums.computeIfAbsent(prefix, _ -> new LinkedHashMap<>());
                for (int i = 0; i + 2 < records.length; i += PGOApplyProfilesPhase.CONDITIONAL_RECORD_SIZE) {
                    counts.merge(new Arm(records[i], records[i + 1]), records[i + 2], Long::sum);
                }
            }
        }
        Map<PGOContext, long[]> result = new HashMap<>(sums.size());
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
        return result;
    }

    /** Resolution statistics for the {@code callCountProfiles} section. */
    public record CallCountDiagnostics(int totalEntries, int resolvedEntries, int unresolvedEntries, long totalCount) {
        /** For example {@code iprof callCountProfiles: 4424 entries, 4411 resolved, 13 unresolved; total count 918273}. */
        public String summary() {
            return String.format("iprof callCountProfiles: %d entries, %d resolved, %d unresolved; total count %d", totalEntries, resolvedEntries, unresolvedEntries, totalCount);
        }
    }

    /**
     * Resolves {@code callCountProfiles}. A context counts calls of its innermost method made from
     * that chain of callers. The count is added to the context only if every frame is in the image,
     * as for the other kinds. It is added to the method's total whenever the method itself is in the
     * image, so a caller that the image lacks does not lower the method's count.
     */
    private static CallCountDiagnostics resolveCallCounts(ParsedIprofProfile profile, Map<Integer, String> descriptorByMethodId, Set<String> presentDescriptors,
                    Map<PGOContext, Long> byContext, Map<String, Long> byMethod, List<String> dropped) {
        int resolved = 0;
        int unresolved = 0;
        long total = 0;
        for (CallCountEntry entry : profile.callCountEntries()) {
            String headDescriptor = descriptorByMethodId.get(entry.context().getFirst().methodId());
            if (headDescriptor != null && presentDescriptors.contains(headDescriptor)) {
                /* Method totals remain useful even when an outer caller frame is absent. */
                byMethod.merge(headDescriptor, entry.count(), Long::sum);
            }
            PGOContext key = canonicalKey(entry.context(), descriptorByMethodId, presentDescriptors);
            if (key == null) {
                unresolved++;
                addDropped(dropped, "call count", entry.context(), descriptorByMethodId, presentDescriptors);
                continue;
            }
            byContext.merge(key, entry.count(), Long::sum);
            total += entry.count();
            resolved++;
        }
        return new CallCountDiagnostics(profile.callCountEntries().size(), resolved, unresolved, total);
    }

    /** Resolution statistics for the {@code virtualInvokeProfiles} section. */
    public record VirtualInvokeDiagnostics(int totalEntries, int resolvedEntries, int unresolvedContextEntries, int duplicateContextEntries,
                    int noKnownReceiverEntries, long receiverRecords, long droppedReceiverRecords, long receiverEvents, long droppedReceiverEvents) {
        /**
         * For example {@code iprof virtualInvokeProfiles: 1736 entries, 1726 resolved, 8
         * unresolved-context, 0 duplicate-context, 2 without image-present receiver; receiver records
         * 2950 (12 dropped), receiver events 81234 (40 dropped)}.
         */
        public String summary() {
            return String.format("iprof virtualInvokeProfiles: %d entries, %d resolved, %d unresolved-context, %d duplicate-context, %d without image-present receiver; " +
                            "receiver records %d (%d dropped), receiver events %d (%d dropped)",
                            totalEntries, resolvedEntries, unresolvedContextEntries, duplicateContextEntries, noKnownReceiverEntries,
                            receiverRecords, droppedReceiverRecords, receiverEvents, droppedReceiverEvents);
        }
    }

    /**
     * Resolves {@code virtualInvokeProfiles}: the context must be fully present (as for the other
     * kinds) and each receiver type is mapped through its JVM descriptor to the image type. A
     * receiver type that the image does not have is dropped from its site, which keeps the other
     * observed receivers and its whole context. {@code PGOProfileUseFeature} warns about such
     * types: they mean that the instrumented build saw code that this build does not have.
     */
    private static VirtualInvokeDiagnostics resolveVirtualInvokes(ParsedIprofProfile profile, Map<Integer, String> descriptorByMethodId, Set<String> presentDescriptors,
                    Map<String, AnalysisType> typesByDescriptor, Map<PGOContext, Map<AnalysisType, Long>> table, List<String> dropped) {
        int resolved = 0;
        int unresolvedContext = 0;
        int duplicates = 0;
        int noReceiver = 0;
        long records = 0;
        long droppedRecords = 0;
        long events = 0;
        long droppedEvents = 0;
        for (VirtualInvokeEntry entry : profile.virtualInvokeEntries()) {
            PGOContext key = canonicalKey(entry.context(), descriptorByMethodId, presentDescriptors);
            if (key == null) {
                unresolvedContext++;
                addDropped(dropped, "receivers", entry.context(), descriptorByMethodId, presentDescriptors);
                continue;
            }
            Map<AnalysisType, Long> receivers = new HashMap<>();
            long[] raw = entry.records();
            for (int i = 0; i < raw.length; i += IprofParser.VIRTUAL_INVOKE_RECORD_SIZE) {
                records++;
                long count = raw[i + 1];
                events += count;
                String descriptor = descriptorForTypeId((int) raw[i], profile.typeNamesById());
                AnalysisType type = descriptor == null ? null : typesByDescriptor.get(descriptor);
                if (type == null) {
                    droppedRecords++;
                    droppedEvents += count;
                    addDropped(dropped, "receiver type " + profile.typeNamesById().get((int) raw[i]) + " not found in the image", entry.context(), descriptorByMethodId, presentDescriptors);
                    continue;
                }
                receivers.merge(type, count, Long::sum);
            }
            if (receivers.isEmpty()) {
                noReceiver++;
                addDropped(dropped, "receivers, no recorded type in the image", entry.context(), descriptorByMethodId, presentDescriptors);
                continue;
            }
            if (table.putIfAbsent(key, Map.copyOf(receivers)) != null) {
                duplicates++;
                addDropped(dropped, "receivers, duplicate context", entry.context(), descriptorByMethodId, presentDescriptors);
                continue;
            }
            resolved++;
        }
        return new VirtualInvokeDiagnostics(profile.virtualInvokeEntries().size(), resolved, unresolvedContext, duplicates, noReceiver,
                        records, droppedRecords, events, droppedEvents);
    }

    /** Resolution statistics for the {@code samplingProfiles} section. */
    public record SamplingDiagnostics(int totalEntries, int resolvedEntries, int truncatedEntries, int unresolvedEntries, long samples, long droppedSamples,
                    int distinctMethods, Map<String, Long> missingFrameSamples) {
        /** How many of the unresolvable frames with the most samples the summary names. */
        private static final int REPORTED_MISSING_FRAMES = 8;

        /**
         * For example {@code iprof samplingProfiles: 42 stacks, 41 fully resolved, 0 truncated at an
         * unresolvable outer frame, 1 unresolved; samples 392 (4 dropped); 59 distinct methods; top
         * unresolvable frames by samples: Lcom/example/Old;.run()V=4}.
         */
        public String summary() {
            StringBuilder missing = new StringBuilder();
            missingFrameSamples.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue(), a.getValue())).limit(REPORTED_MISSING_FRAMES)
                            .forEach(e -> missing.append(missing.isEmpty() ? "" : ", ").append(e.getKey()).append('=').append(e.getValue()));
            return String.format("iprof samplingProfiles: %d stacks, %d fully resolved, %d truncated at an unresolvable outer frame, %d unresolved; " +
                            "samples %d (%d dropped); %d distinct methods; top unresolvable frames by samples: %s",
                            totalEntries, resolvedEntries, truncatedEntries, unresolvedEntries, samples, droppedSamples, distinctMethods, missing);
        }
    }

    /**
     * Resolves sampled stacks into the {@link NodeSourcePosition} chains {@code PrefixTree} expects:
     * the position itself is the outermost frame and the caller chain walks inward to the sampled
     * leaf.
     *
     * Frames are resolved from the leaf outward and the stack is truncated at the first frame whose
     * method is absent from the image. A profile recorded on a different build of the application may
     * contain frames that do not exist in this image (for example hidden lambda classes or generated
     * factory methods); keeping the innermost resolvable segment preserves leaf hotness and the contexts
     * below the mismatch, which is what a method-rooted view consumes. A stack whose leaf itself is
     * unresolvable is dropped. The sampler names a frame as stack traces do, which for a few
     * substituted JDK methods is not the method's name in the image ({@code Object.waitSubst} for
     * {@code Object.wait}); {@code methodsByDescriptor} also holds the methods under those names
     * (see {@link #indexStackTraceNames}).
     */
    private static SamplingDiagnostics resolveSamples(ParsedIprofProfile profile, Map<Integer, String> descriptorByMethodId, Map<String, HostedMethod> methodsByDescriptor,
                    Map<NodeSourcePosition, Long> sampleCounts, List<String> droppedContexts) {
        int resolved = 0;
        int truncated = 0;
        int unresolved = 0;
        long samples = 0;
        long dropped = 0;
        Set<AnalysisMethod> methods = new HashSet<>();
        Map<String, Long> missing = new HashMap<>();
        for (SamplingEntry entry : profile.samplingEntries()) {
            samples += entry.count();
            NodeSourcePosition chain = null;
            boolean complete = true;
            for (ContextFrame frame : entry.context()) {
                String descriptor = descriptorByMethodId.get(frame.methodId());
                HostedMethod method = descriptor == null ? null : methodsByDescriptor.get(descriptor);
                if (method == null) {
                    missing.merge(descriptor == null ? "<method id " + frame.methodId() + ">" : descriptor, entry.count(), Long::sum);
                    complete = false;
                    break;
                }
                /* Innermost first in the file, so each frame becomes the new outermost position. */
                chain = new NodeSourcePosition(chain, method.getWrapped(), frame.bci());
            }
            if (chain == null) {
                unresolved++;
                dropped += entry.count();
                addDropped(droppedContexts, "stack sample", entry.context(), descriptorByMethodId, methodsByDescriptor.keySet());
                continue;
            }
            for (NodeSourcePosition position : chain) {
                methods.add((AnalysisMethod) position.getMethod());
            }
            sampleCounts.merge(chain, entry.count(), Long::sum);
            if (complete) {
                resolved++;
            } else {
                truncated++;
                addDropped(droppedContexts, "stack sample, cut at the first frame not in the image", entry.context(), descriptorByMethodId, methodsByDescriptor.keySet());
            }
        }
        return new SamplingDiagnostics(profile.samplingEntries().size(), resolved, truncated, unresolved, samples, dropped, methods.size(), missing);
    }

    /**
     * Indexes image types by JVM descriptor so profile type names resolve to analysis types. As for
     * methods, a name that two image types share names neither of them; such names are added to
     * {@code ambiguous}.
     */
    private static Map<String, AnalysisType> indexUniverseTypes(HostedUniverse universe, Set<String> ambiguous) {
        Map<String, AnalysisType> typesByDescriptor = new HashMap<>();
        for (HostedType type : universe.getTypes()) {
            if (typesByDescriptor.putIfAbsent(type.getName(), type.getWrapped()) != null) {
                ambiguous.add(type.getName());
            }
        }
        typesByDescriptor.keySet().removeAll(ambiguous);
        return typesByDescriptor;
    }

    /**
     * Adds a readable form of a dropped entry's context to {@code dropped}, if the caller collects
     * them: innermost frame first, as in the file, with every frame that is not in the image marked.
     * For example {@code call count: Lcom/example/Foo;.bar()V:0 < Lcom/example/Old;.run()V:7 (not in
     * the image)}.
     */
    private static void addDropped(List<String> dropped, String kind, List<ContextFrame> context, Map<Integer, String> descriptorByMethodId, Set<String> presentDescriptors) {
        if (dropped == null) {
            return;
        }
        StringBuilder result = new StringBuilder(kind).append(": ");
        String separator = "";
        for (ContextFrame frame : context) {
            String descriptor = descriptorByMethodId.get(frame.methodId());
            result.append(separator).append(descriptor == null ? "method id " + frame.methodId() : descriptor).append(':').append(frame.bci());
            if (descriptor == null || !presentDescriptors.contains(descriptor)) {
                result.append(" (not in the image)");
            }
            separator = " < ";
        }
        dropped.add(result.toString());
    }

    /**
     * Builds the {@link PGOContext} of a branch or switch, call-count or receiver entry, or
     * {@code null} if any frame's method is absent from the image. For example, the context
     * {@code 7:12<3:5} gives {@code Ljava/lang/String;.charAt(I)C:12 < Lcom/example/Foo;.bar()V:5}
     * when the methods table names those methods with ids 7 and 3.
     */
    private static PGOContext canonicalKey(List<ContextFrame> entryContext, Map<Integer, String> descriptorByMethodId, Set<String> presentDescriptors) {
        List<Frame> frames = new ArrayList<>(entryContext.size());
        for (ContextFrame frame : entryContext) {
            String descriptor = descriptorByMethodId.get(frame.methodId());
            if (descriptor == null || !presentDescriptors.contains(descriptor)) {
                return null;
            }
            frames.add(new Frame(descriptor, frame.bci()));
        }
        return new PGOContext(frames);
    }

    /** The descriptor of every method of the profile's methods table by id, such as {@code 7 -> Ljava/lang/String;.charAt(I)C}. */
    private static Map<Integer, String> buildProfileMethodDescriptors(ParsedIprofProfile profile) {
        Map<Integer, String> descriptorByMethodId = new HashMap<>(profile.methodsById().size());
        for (MethodDescriptor method : profile.methodsById().values()) {
            String descriptor = descriptorForProfileMethod(method, profile.typeNamesById());
            if (descriptor != null) {
                descriptorByMethodId.put(method.methodId(), descriptor);
            }
        }
        return descriptorByMethodId;
    }

    /**
     * Indexes image methods by their canonical descriptor. A descriptor that two image methods
     * share (classes of the same name from different class loaders) names neither of them: their
     * profile entries stay unresolved rather than being applied to whichever method came first.
     * Such descriptors are added to {@code ambiguous}.
     */
    private static Map<String, HostedMethod> indexUniverseMethods(HostedUniverse universe, Set<String> ambiguous) {
        Map<String, HostedMethod> methodsByDescriptor = new HashMap<>();
        for (HostedMethod method : universe.getMethods()) {
            if (!method.isOriginalMethod()) {
                continue;
            }
            String descriptor = ProfilingUtilities.methodDescriptor(method);
            if (methodsByDescriptor.putIfAbsent(descriptor, method) != null) {
                ambiguous.add(descriptor);
            }
        }
        methodsByDescriptor.keySet().removeAll(ambiguous);
        return methodsByDescriptor;
    }

    /**
     * The frames of the stack samples that no canonical descriptor of the image names. A frame
     * whose descriptor names two image methods is left out: it stays unresolved, as in the other
     * sections.
     */
    private static Set<String> unresolvedSampleFrames(ParsedIprofProfile profile, Set<String> presentDescriptors, Set<String> ambiguousDescriptors) {
        Map<Integer, String> descriptorByMethodId = buildProfileMethodDescriptors(profile);
        Set<String> unresolved = new HashSet<>();
        for (SamplingEntry entry : profile.samplingEntries()) {
            for (ContextFrame frame : entry.context()) {
                String descriptor = descriptorByMethodId.get(frame.methodId());
                if (descriptor != null && !presentDescriptors.contains(descriptor) && !ambiguousDescriptors.contains(descriptor)) {
                    unresolved.add(descriptor);
                }
            }
        }
        return unresolved;
    }

    /**
     * Indexes image methods under the name the stack sampler gives their frames, for the sampled
     * frames that no canonical descriptor names. The sampler names a frame as stack traces do
     * ({@link jdk.vm.ci.meta.ResolvedJavaMethod#asStackTraceElement}), and for a substituted
     * method that is the substitution's own name: the frame of {@code Object.wait(long)} is
     * {@code Ljava/lang/Object;.waitSubst(J)V}. Only methods whose class and signature match an
     * unresolved frame are asked for their stack-trace name. A name that two image methods share
     * names neither.
     */
    private static Map<String, HostedMethod> indexStackTraceNames(HostedUniverse universe, Set<String> unresolvedFrames) {
        if (unresolvedFrames.isEmpty()) {
            return Map.of();
        }
        Set<String> classesAndSignatures = new HashSet<>();
        for (String frame : unresolvedFrames) {
            classesAndSignatures.add(withoutMethodName(frame));
        }
        Map<String, HostedMethod> result = new HashMap<>();
        Set<String> ambiguous = new HashSet<>();
        for (HostedMethod method : universe.getMethods()) {
            if (!method.isOriginalMethod() || !classesAndSignatures.contains(withoutMethodName(ProfilingUtilities.methodDescriptor(method)))) {
                continue;
            }
            String name = method.asStackTraceElement(0).getMethodName();
            String frame = method.getDeclaringClass().getName() + "." + name + method.getSignature().toMethodDescriptor();
            if (!name.equals(method.getName()) && unresolvedFrames.contains(frame) && result.putIfAbsent(frame, method) != null) {
                ambiguous.add(frame);
            }
        }
        result.keySet().removeAll(ambiguous);
        return result;
    }

    /** A method descriptor without the method's name: {@code Ljava/lang/Object;(J)V} for {@code Ljava/lang/Object;.wait(J)V}. */
    static String withoutMethodName(String methodDescriptor) {
        int nameStart = methodDescriptor.indexOf(";.") + 2;
        int signatureStart = methodDescriptor.indexOf('(', nameStart);
        if (nameStart < 2 || signatureStart < 0) {
            return methodDescriptor;
        }
        return methodDescriptor.substring(0, nameStart - 1) + methodDescriptor.substring(signatureStart);
    }
}
