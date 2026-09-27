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
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.oracle.svm.hosted.meta.HostedMethod;
import com.oracle.graal.pointsto.meta.AnalysisMethod;
import com.oracle.graal.pointsto.meta.AnalysisType;
import com.oracle.svm.hosted.meta.HostedType;
import com.oracle.svm.hosted.meta.HostedUniverse;
import com.oracle.svm.hosted.pgo.IprofConditionalParser.ConditionalEntry;
import com.oracle.svm.hosted.pgo.IprofConditionalParser.ContextFrame;
import com.oracle.svm.hosted.pgo.IprofConditionalParser.MethodDescriptor;
import com.oracle.svm.hosted.pgo.IprofConditionalParser.ParsedProfile;
import com.oracle.svm.hosted.pgo.IprofConditionalParser;
import com.oracle.svm.hosted.pgo.IprofConditionalParser.PreciseConditionalEntry;
import com.oracle.svm.hosted.pgo.IprofConditionalParser.SamplingEntry;
import com.oracle.svm.hosted.pgo.IprofConditionalParser.VirtualInvokeEntry;
import com.oracle.svm.hosted.pgo.profiles.ConditionalProfileSiteDescriptor.Stage;
import com.oracle.svm.hosted.pgo.profiles.SimpleConditionalProfilesLookup.FrameKey;
import com.oracle.svm.hosted.pgo.profiles.SimpleConditionalProfilesLookup.PreciseKey;
import com.oracle.svm.hosted.pgo.profiles.SimpleConditionalProfilesLookup.PreciseProfile;

import jdk.vm.ci.meta.JavaType;
import jdk.graal.compiler.graph.NodeSourcePosition;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import jdk.vm.ci.meta.Signature;

/**
 * Resolves a {@link ParsedProfile}'s conditional entries against a {@link HostedUniverse}, producing
 * the immutable table consumed by {@link SimpleConditionalProfilesLookup}.
 *
 * <p>
 * The core idea is a single canonical method-identity string in JVM-descriptor form, computed the
 * same way from two sources:
 * <ul>
 * <li>from the iprof {@code methods}/{@code types} tables ({@link #descriptorForProfileMethod}), and
 * <li>from a resolved method at query time and during universe indexing
 * ({@link #methodDescriptor(ResolvedJavaMethod)}).
 * </ul>
 * Because both paths yield identical strings, calling contexts recorded in the profile line up with
 * the {@link jdk.vm.ci.code.BytecodePosition} chains the compiler passes to the lookup, without
 * relying on {@code NodeSourcePosition} identity.
 */
public final class ConditionalProfileContextResolver {

    private ConditionalProfileContextResolver() {
    }

    /**
     * Canonical identity of a resolved method: {@code <declaringClassDescriptor>.<name><signature>}
     * where the declaring class and every signature type are rendered as JVM type descriptors (for
     * example {@code Ljava/lang/String;}, {@code [I}, {@code I}). This matches
     * {@link #descriptorForProfileMethod} exactly.
     */
    public static String methodDescriptor(ResolvedJavaMethod method) {
        if (method == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(method.getDeclaringClass().getName());
        sb.append('.').append(method.getName());
        Signature signature = method.getSignature();
        sb.append('(');
        int count = signature.getParameterCount(false);
        for (int i = 0; i < count; i++) {
            sb.append(typeDescriptor(signature.getParameterType(i, null)));
        }
        sb.append(')');
        sb.append(typeDescriptor(signature.getReturnType(null)));
        return sb.toString();
    }

    private static String typeDescriptor(JavaType type) {
        // JVMCI JavaType.getName() already returns the JVM descriptor form.
        return type.getName();
    }

    /**
     * Builds the canonical descriptor for an iprof method entry, converting the profile's type
     * names (dotted FQNs, primitive keywords, or {@code [L...;} array names) into JVM descriptors so
     * the result matches {@link #methodDescriptor(ResolvedJavaMethod)}.
     *
     * @return the descriptor, or {@code null} if a referenced type id is missing.
     */
    static String descriptorForProfileMethod(MethodDescriptor method, Map<Integer, String> typeNamesById) {
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

    private static String descriptorForTypeId(int typeId, Map<Integer, String> typeNamesById) {
        String name = typeNamesById.get(typeId);
        return name == null ? null : toDescriptor(name);
    }

    /**
     * Converts an iprof type name into a JVM type descriptor.
     * <ul>
     * <li>primitive keyword ({@code int}, {@code boolean}, {@code void}, ...) -&gt; single-letter
     * descriptor;
     * <li>array name ({@code [Z}, {@code [Ljava.lang.String;}) -&gt; descriptor with dots replaced
     * by slashes inside the class part;
     * <li>class FQN ({@code java.lang.String}) -&gt; {@code Ljava/lang/String;}.
     * </ul>
     *
     * @param iprofTypeName a non-empty iprof type name.
     * @throws IllegalArgumentException if {@code iprofTypeName} is {@code null} or empty. The parser
     *             already rejects empty type names strictly (see {@code IprofConditionalParser}),
     *             so this guard converts any remaining empty input into a clear failure rather than
     *             an opaque {@link String#charAt(int)} {@link StringIndexOutOfBoundsException}.
     */
    static String toDescriptor(String iprofTypeName) {
        if (iprofTypeName == null || iprofTypeName.isEmpty()) {
            throw new IllegalArgumentException("Cannot convert an empty iprof type name to a JVM descriptor");
        }
        switch (iprofTypeName) {
            case "boolean":
                return "Z";
            case "byte":
                return "B";
            case "short":
                return "S";
            case "char":
                return "C";
            case "int":
                return "I";
            case "long":
                return "J";
            case "float":
                return "F";
            case "double":
                return "D";
            case "void":
                return "V";
            default:
                break;
        }
        if (iprofTypeName.charAt(0) == '[') {
            // Array: JVM descriptor already, but object element FQNs use dots -> convert to slashes.
            return iprofTypeName.replace('.', '/');
        }
        // Object type: dotted FQN -> Lbinary/name;
        return 'L' + iprofTypeName.replace('.', '/') + ';';
    }

    /**
     * Resolves the parsed profile against the universe and builds the lookup table.
     *
     * @return a fully constructed, immutable lookup.
     */
    public static SimpleConditionalProfilesLookup resolve(ParsedProfile profile, HostedUniverse universe) {
        return buildLookup(profile, indexUniverseMethods(universe), indexUniverseTypes(universe));
    }

    static SimpleConditionalProfilesLookup buildLookup(ParsedProfile profile, Set<String> presentDescriptors) {
        return buildLookup(profile, presentDescriptors, Map.of());
    }

    static SimpleConditionalProfilesLookup buildLookup(ParsedProfile profile, Set<String> presentDescriptors, Map<String, AnalysisType> typesByDescriptor) {
        Map<String, HostedMethod> methods = new HashMap<>();
        for (String descriptor : presentDescriptors) {
            methods.put(descriptor, null);
        }
        return buildLookup(profile, methods, typesByDescriptor);
    }

    /**
     * Builds the lookup and diagnostics from a parsed profile and the set of canonical method
     * descriptors present in the image. This is the universe-independent core of {@link #resolve}
     * and is directly unit-testable.
     *
     * @param profile the parsed profile.
     * @param presentDescriptors canonical descriptors (see {@link #methodDescriptor}) of methods
     *            available in the image.
     */
    static SimpleConditionalProfilesLookup buildLookup(ParsedProfile profile, Map<String, HostedMethod> methodsByDescriptor, Map<String, AnalysisType> typesByDescriptor) {
        Set<String> presentDescriptors = methodsByDescriptor.keySet();
        Map<Integer, String> descriptorByMethodId = buildProfileMethodDescriptors(profile);
        Map<List<FrameKey>, long[]> legacyTable = new HashMap<>();
        for (ConditionalEntry entry : profile.conditionalEntries()) {
            List<FrameKey> key = canonicalKey(entry.context(), descriptorByMethodId, presentDescriptors);
            if (key != null) {
                legacyTable.putIfAbsent(key, entry.records());
            }
        }

        Map<PreciseKey, PreciseProfile> preciseTable = new HashMap<>();
        int resolved = 0;
        int unresolved = 0;
        int duplicates = 0;
        int singleFrame = 0;
        int inlined = 0;

        if (!profile.preciseConditionalEntries().isEmpty()) {
            for (PreciseConditionalEntry entry : profile.preciseConditionalEntries()) {
                List<FrameKey> context = canonicalKey(entry.context(), descriptorByMethodId, presentDescriptors);
                Stage stage;
                try {
                    stage = Stage.valueOf(entry.stage());
                } catch (IllegalArgumentException exception) {
                    unresolved++;
                    continue;
                }
                if (context == null) {
                    unresolved++;
                    continue;
                }
                ConditionalProfileSiteDescriptor site = new ConditionalProfileSiteDescriptor(stage, Arrays.stream(entry.successorBcis()).boxed().toList(),
                                entry.conditionKind(), entry.conditionFingerprint(), entry.occurrence());
                if (preciseTable.putIfAbsent(PreciseKey.from(context, site), new PreciseProfile(entry.conditionFingerprint(), entry.records())) != null) {
                    duplicates++;
                    continue;
                }
                resolved++;
                if (context.size() == 1) {
                    singleFrame++;
                } else {
                    inlined++;
                }
            }
        } else {
            for (ConditionalEntry entry : profile.conditionalEntries()) {
                List<FrameKey> key = canonicalKey(entry.context(), descriptorByMethodId, presentDescriptors);
                if (key == null) {
                    unresolved++;
                    continue;
                }
                if (legacyTable.get(key) != entry.records()) {
                    duplicates++;
                    continue;
                }
                resolved++;
                if (key.size() == 1) {
                    singleFrame++;
                } else {
                    inlined++;
                }
            }
        }

        int totalEntries = profile.preciseConditionalEntries().isEmpty() ? profile.conditionalEntries().size() : profile.preciseConditionalEntries().size();
        ConditionalProfileDiagnostics diagnostics = new ConditionalProfileDiagnostics(
                        profile.version(), totalEntries, resolved, unresolved, duplicates, singleFrame, inlined);

        Map<List<FrameKey>, Map<AnalysisType, Long>> virtualInvokeTable = new HashMap<>();
        VirtualInvokeDiagnostics virtualDiagnostics = resolveVirtualInvokes(profile, descriptorByMethodId, presentDescriptors, typesByDescriptor, virtualInvokeTable);
        Map<NodeSourcePosition, Long> sampleCounts = new HashMap<>();
        SamplingDiagnostics samplingDiagnostics = resolveSamples(profile, descriptorByMethodId, methodsByDescriptor, sampleCounts);
        return new SimpleConditionalProfilesLookup(legacyTable, preciseTable, diagnostics, virtualInvokeTable, virtualDiagnostics, sampleCounts, samplingDiagnostics);
    }

    /** Resolution statistics for the {@code virtualInvokeProfiles} section. */
    public record VirtualInvokeDiagnostics(int totalEntries, int resolvedEntries, int unresolvedContextEntries, int duplicateContextEntries,
                    int noKnownReceiverEntries, long receiverRecords, long droppedReceiverRecords, long receiverEvents, long droppedReceiverEvents) {
        public String summary() {
            return String.format("iprof virtualInvokeProfiles: %d entries, %d resolved, %d unresolved-context, %d duplicate-context, %d without image-present receiver; " +
                            "receiver records %d (%d dropped), receiver events %d (%d dropped)",
                            totalEntries, resolvedEntries, unresolvedContextEntries, duplicateContextEntries, noKnownReceiverEntries,
                            receiverRecords, droppedReceiverRecords, receiverEvents, droppedReceiverEvents);
        }
    }

    /**
     * Resolves {@code virtualInvokeProfiles}: the context must be fully present (as for conditional
     * entries) and each receiver type is mapped through its JVM descriptor to the image type.
     * Receiver types absent from the image are dropped record by record so that a site keeps its
     * remaining observed receivers.
     */
    private static VirtualInvokeDiagnostics resolveVirtualInvokes(ParsedProfile profile, Map<Integer, String> descriptorByMethodId, Set<String> presentDescriptors,
                    Map<String, AnalysisType> typesByDescriptor, Map<List<FrameKey>, Map<AnalysisType, Long>> table) {
        int resolved = 0;
        int unresolvedContext = 0;
        int duplicates = 0;
        int noReceiver = 0;
        long records = 0;
        long droppedRecords = 0;
        long events = 0;
        long droppedEvents = 0;
        for (VirtualInvokeEntry entry : profile.virtualInvokeEntries()) {
            List<FrameKey> key = canonicalKey(entry.context(), descriptorByMethodId, presentDescriptors);
            if (key == null) {
                unresolvedContext++;
                continue;
            }
            Map<AnalysisType, Long> receivers = new HashMap<>();
            long[] raw = entry.records();
            for (int i = 0; i < raw.length; i += IprofConditionalParser.VIRTUAL_INVOKE_RECORD_SIZE) {
                records++;
                long count = raw[i + 1];
                events += count;
                String descriptor = descriptorForTypeId((int) raw[i], profile.typeNamesById());
                AnalysisType type = descriptor == null ? null : typesByDescriptor.get(descriptor);
                if (type == null) {
                    droppedRecords++;
                    droppedEvents += count;
                    continue;
                }
                receivers.merge(type, count, Long::sum);
            }
            if (receivers.isEmpty()) {
                noReceiver++;
                continue;
            }
            if (table.putIfAbsent(key, Map.copyOf(receivers)) != null) {
                duplicates++;
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
        public String summary() {
            StringBuilder missing = new StringBuilder();
            missingFrameSamples.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue(), a.getValue())).limit(8)
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
     * method is absent from the image. Profiles recorded on another build contain synthetic frames
     * (hidden lambda classes, generated factory methods, implementation-specific helpers) that can
     * never match; keeping the innermost resolvable segment preserves leaf hotness and the contexts
     * below the mismatch, which is what a method-rooted view consumes. A stack whose leaf itself is
     * unresolvable is dropped.
     */
    private static SamplingDiagnostics resolveSamples(ParsedProfile profile, Map<Integer, String> descriptorByMethodId, Map<String, HostedMethod> methodsByDescriptor,
                    Map<NodeSourcePosition, Long> sampleCounts) {
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
            }
        }
        return new SamplingDiagnostics(profile.samplingEntries().size(), resolved, truncated, unresolved, samples, dropped, methods.size(), missing);
    }

    /** Indexes image types by JVM descriptor so profile type names resolve to analysis types. */
    private static Map<String, AnalysisType> indexUniverseTypes(HostedUniverse universe) {
        Map<String, AnalysisType> typesByDescriptor = new HashMap<>();
        for (HostedType type : universe.getTypes()) {
            typesByDescriptor.putIfAbsent(type.getName(), type.getWrapped());
        }
        return typesByDescriptor;
    }

    /**
     * Builds a canonical {@link FrameKey} chain (innermost frame first) for a conditional entry, or
     * {@code null} if any frame's method is absent from the image. Each frame's method identity is
     * confirmed present so that only contexts fully present in this image are stored.
     */
    private static List<FrameKey> canonicalKey(List<ContextFrame> entryContext, Map<Integer, String> descriptorByMethodId, Set<String> presentDescriptors) {
        List<FrameKey> key = new ArrayList<>(entryContext.size());
        for (ContextFrame frame : entryContext) {
            String descriptor = descriptorByMethodId.get(frame.methodId());
            if (descriptor == null || !presentDescriptors.contains(descriptor)) {
                return null;
            }
            key.add(new FrameKey(descriptor, frame.bci()));
        }
        return key;
    }

    private static Map<Integer, String> buildProfileMethodDescriptors(ParsedProfile profile) {
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
     * Indexes universe methods by their canonical descriptor. Descriptor collisions (which should
     * not occur for a fully-qualified signature) resolve first-wins; such contexts simply match the
     * first-registered method, and any mismatch degrades to a lookup miss handled gracefully by the
     * phase.
     */
    private static Map<String, HostedMethod> indexUniverseMethods(HostedUniverse universe) {
        Map<String, HostedMethod> methodsByDescriptor = new HashMap<>();
        for (HostedMethod method : universe.getMethods()) {
            if (!method.isOriginalMethod()) {
                continue;
            }
            methodsByDescriptor.putIfAbsent(methodDescriptor(method), method);
        }
        return methodsByDescriptor;
    }
}
