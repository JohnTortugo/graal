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
import java.io.Writer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import jdk.graal.compiler.util.json.JsonWriter;

/** Writes legacy conditional profiles plus a precise stage-qualified CE extension. */
public final class BranchProfileIprofWriter {

    public static final String PRECISE_CONDITIONAL_PROFILES_KEY = "ceConditionalProfilesV2";
    private static final String VERSION = "1.1.0";

    private BranchProfileIprofWriter() {
    }

    public static DumpStatistics write(Path path, List<BranchProfileCounter> counters) throws IOException {
        try (JsonWriter writer = new JsonWriter(path)) {
            return write(writer, counters);
        }
    }

    public static DumpStatistics write(Writer output, List<BranchProfileCounter> counters) throws IOException {
        try (JsonWriter writer = new JsonWriter(output)) {
            return write(writer, counters);
        }
    }

    private static DumpStatistics write(JsonWriter writer, List<BranchProfileCounter> counters) throws IOException {
        List<BranchProfileCounter> activeCounters = counters.stream()
                        .filter(counter -> counter.getTrueCount() != 0 || counter.getFalseCount() != 0)
                        .toList();
        Map<PreciseSiteKey, PreciseSiteData> preciseSites = aggregatePreciseSites(activeCounters);
        Map<ContextKey, Map.Entry<PreciseSiteKey, PreciseSiteData>> legacySites = unambiguousLegacySites(preciseSites);
        Metadata metadata = Metadata.create(preciseSites.keySet());

        writer.appendObjectStart();
        writer.appendKeyValue("version", VERSION).appendSeparator();
        writeTypes(writer, metadata).appendSeparator();
        writeMethods(writer, metadata).appendSeparator();
        writeLegacyProfiles(writer, metadata, legacySites).appendSeparator();
        writePreciseProfiles(writer, metadata, preciseSites);
        writer.appendObjectEnd();

        long events = activeCounters.stream().mapToLong(counter -> counter.getTrueCount() + counter.getFalseCount()).sum();
        return new DumpStatistics(metadata.typesByName.size(), metadata.methodsByDescriptor.size(), legacySites.size(), preciseSites.size(), events);
    }

    private static Map<PreciseSiteKey, PreciseSiteData> aggregatePreciseSites(List<BranchProfileCounter> counters) {
        Map<PreciseSiteKey, PreciseSiteData> aggregate = new HashMap<>();
        for (BranchProfileCounter counter : counters) {
            PreciseSiteKey key = PreciseSiteKey.from(counter);
            PreciseSiteData data = aggregate.computeIfAbsent(key, _ -> new PreciseSiteData(counter.getConditionFingerprint(), new long[2]));
            data.counts()[0] += counter.getTrueCount();
            data.counts()[1] += counter.getFalseCount();
        }
        return aggregate.entrySet().stream().sorted(Map.Entry.comparingByKey(PRECISE_SITE_COMPARATOR))
                        .collect(LinkedHashMap::new, (map, entry) -> map.put(entry.getKey(), entry.getValue()), Map::putAll);
    }

    /** Legacy output is safe only when one context identifies exactly one precise branch site. */
    /**
     * A legacy context-only entry describes the bytecode branch, so it is the sum over every physical
     * copy of that branch: a loop header's peeled guard plus its in-loop exit condition, or a peeled
     * iteration plus the loop body. Copies that route to different successor BCIs are not the same
     * branch anymore (the compiler rewired them); such contexts stay ambiguous and get no legacy entry.
     */
    private static Map<ContextKey, Map.Entry<PreciseSiteKey, PreciseSiteData>> unambiguousLegacySites(Map<PreciseSiteKey, PreciseSiteData> preciseSites) {
        Map<ContextKey, List<Map.Entry<PreciseSiteKey, PreciseSiteData>>> byContext = new HashMap<>();
        for (Map.Entry<PreciseSiteKey, PreciseSiteData> entry : preciseSites.entrySet()) {
            byContext.computeIfAbsent(entry.getKey().context, _ -> new ArrayList<>()).add(entry);
        }
        Map<ContextKey, Map.Entry<PreciseSiteKey, PreciseSiteData>> result = new TreeMap<>(CONTEXT_COMPARATOR);
        byContext.forEach((context, entries) -> {
            PreciseSiteKey first = entries.getFirst().getKey();
            long trueCount = 0;
            long falseCount = 0;
            for (Map.Entry<PreciseSiteKey, PreciseSiteData> entry : entries) {
                PreciseSiteKey key = entry.getKey();
                long[] counts = entry.getValue().counts();
                if (key.trueSuccessorBci == first.trueSuccessorBci && key.falseSuccessorBci == first.falseSuccessorBci) {
                    trueCount += counts[0];
                    falseCount += counts[1];
                } else if (key.trueSuccessorBci == first.falseSuccessorBci && key.falseSuccessorBci == first.trueSuccessorBci) {
                    /* Same branch with a negated condition: successors swapped. */
                    trueCount += counts[1];
                    falseCount += counts[0];
                } else {
                    return;
                }
            }
            if (entries.size() == 1) {
                result.put(context, entries.getFirst());
            } else {
                result.put(context, Map.entry(first, new PreciseSiteData(entries.getFirst().getValue().conditionFingerprint(), new long[]{trueCount, falseCount})));
            }
        });
        return result;
    }

    private static JsonWriter writeTypes(JsonWriter writer, Metadata metadata) throws IOException {
        writer.quote("types").appendFieldSeparator().appendArrayStart();
        boolean first = true;
        for (Map.Entry<String, Integer> type : metadata.typesByName.entrySet()) {
            if (!first) {
                writer.appendSeparator();
            }
            first = false;
            writer.appendObjectStart().appendKeyValue("id", type.getValue()).appendSeparator()
                            .appendKeyValue("name", type.getKey()).appendObjectEnd();
        }
        return writer.appendArrayEnd();
    }

    private static JsonWriter writeMethods(JsonWriter writer, Metadata metadata) throws IOException {
        writer.quote("methods").appendFieldSeparator().appendArrayStart();
        boolean first = true;
        for (Map.Entry<String, Integer> methodEntry : metadata.methodsByDescriptor.entrySet()) {
            if (!first) {
                writer.appendSeparator();
            }
            first = false;
            ParsedMethod method = ParsedMethod.parse(methodEntry.getKey());
            writer.appendObjectStart().appendKeyValue("id", methodEntry.getValue()).appendSeparator()
                            .appendKeyValue("name", method.name).appendSeparator()
                            .quote("signature").appendFieldSeparator().appendArrayStart();
            writer.printValue(metadata.typesByName.get(method.declaringType)).appendSeparator()
                            .printValue(metadata.typesByName.get(method.returnType));
            for (String parameterType : method.parameterTypes) {
                writer.appendSeparator().printValue(metadata.typesByName.get(parameterType));
            }
            writer.appendArrayEnd().appendObjectEnd();
        }
        return writer.appendArrayEnd();
    }

    private static JsonWriter writeLegacyProfiles(JsonWriter writer, Metadata metadata,
                    Map<ContextKey, Map.Entry<PreciseSiteKey, PreciseSiteData>> profiles) throws IOException {
        writer.quote("conditionalProfiles").appendFieldSeparator().appendArrayStart();
        boolean first = true;
        for (Map.Entry<ContextKey, Map.Entry<PreciseSiteKey, PreciseSiteData>> profile : profiles.entrySet()) {
            if (!first) {
                writer.appendSeparator();
            }
            first = false;
            writeProfileEntry(writer, metadata, profile.getKey(), profile.getValue().getKey(), profile.getValue().getValue(), false);
        }
        return writer.appendArrayEnd();
    }

    private static void writePreciseProfiles(JsonWriter writer, Metadata metadata, Map<PreciseSiteKey, PreciseSiteData> profiles) throws IOException {
        writer.quote(PRECISE_CONDITIONAL_PROFILES_KEY).appendFieldSeparator().appendArrayStart();
        boolean first = true;
        for (Map.Entry<PreciseSiteKey, PreciseSiteData> profile : profiles.entrySet()) {
            if (!first) {
                writer.appendSeparator();
            }
            first = false;
            writeProfileEntry(writer, metadata, profile.getKey().context, profile.getKey(), profile.getValue(), true);
        }
        writer.appendArrayEnd();
    }

    private static void writeProfileEntry(JsonWriter writer, Metadata metadata, ContextKey context, PreciseSiteKey site, PreciseSiteData data, boolean precise) throws IOException {
        writer.appendObjectStart();
        if (precise) {
            writer.appendKeyValue("stage", site.stage).appendSeparator()
                            .appendKeyValue("ctx", context(metadata, context)).appendSeparator()
                            .quote("successors").appendFieldSeparator().appendArrayStart()
                            .printValue(site.trueSuccessorBci).appendSeparator().printValue(site.falseSuccessorBci).appendArrayEnd().appendSeparator()
                            .appendKeyValue("conditionKind", site.conditionKind).appendSeparator()
                            .appendKeyValue("conditionFingerprint", Long.toUnsignedString(data.conditionFingerprint(), 16)).appendSeparator()
                            .appendKeyValue("occurrence", site.occurrence).appendSeparator();
        } else {
            writer.appendKeyValue("ctx", context(metadata, context)).appendSeparator();
        }
        writer.quote("records").appendFieldSeparator().appendArrayStart()
                        .printValue(site.trueSuccessorBci).appendSeparator().printValue(0).appendSeparator().printValue(data.counts()[0]).appendSeparator()
                        .printValue(site.falseSuccessorBci).appendSeparator().printValue(1).appendSeparator().printValue(data.counts()[1])
                        .appendArrayEnd().appendObjectEnd();
    }

    private static String context(Metadata metadata, ContextKey context) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < context.methodDescriptors.size(); i++) {
            if (i != 0) {
                result.append('<');
            }
            result.append(metadata.methodsByDescriptor.get(context.methodDescriptors.get(i))).append(':').append(context.bcis.get(i));
        }
        return result.toString();
    }

    public record DumpStatistics(int types, int methods, int conditionalProfiles, int preciseConditionalProfiles, long recordedEvents) {
    }

    private record ContextKey(List<String> methodDescriptors, List<Integer> bcis) {
        private ContextKey {
            methodDescriptors = List.copyOf(methodDescriptors);
            bcis = List.copyOf(bcis);
        }
    }

    private record PreciseSiteKey(String stage, ContextKey context, int trueSuccessorBci, int falseSuccessorBci,
                    String conditionKind, int occurrence) {
        private static PreciseSiteKey from(BranchProfileCounter counter) {
            ContextKey context = new ContextKey(Arrays.asList(counter.getMethodDescriptors()), Arrays.stream(counter.getContextBcis()).boxed().toList());
            return new PreciseSiteKey(counter.getStage(), context, counter.getTrueSuccessorBci(), counter.getFalseSuccessorBci(),
                            counter.getConditionKind(), counter.getOccurrence());
        }
    }

    private record PreciseSiteData(long conditionFingerprint, long[] counts) {
    }

    private static final Comparator<ContextKey> CONTEXT_COMPARATOR = Comparator
                    .comparing((ContextKey key) -> String.join("\u0000", key.methodDescriptors))
                    .thenComparing(key -> key.bcis.toString());

    private static final Comparator<PreciseSiteKey> PRECISE_SITE_COMPARATOR = Comparator
                    .comparing(PreciseSiteKey::stage)
                    .thenComparing(PreciseSiteKey::context, CONTEXT_COMPARATOR)
                    .thenComparingInt(PreciseSiteKey::trueSuccessorBci)
                    .thenComparingInt(PreciseSiteKey::falseSuccessorBci)
                    .thenComparing(PreciseSiteKey::conditionKind)
                    .thenComparingInt(PreciseSiteKey::occurrence);

    private static final class Metadata {
        private final TreeMap<String, Integer> typesByName;
        private final TreeMap<String, Integer> methodsByDescriptor;

        private Metadata(TreeMap<String, Integer> typesByName, TreeMap<String, Integer> methodsByDescriptor) {
            this.typesByName = typesByName;
            this.methodsByDescriptor = methodsByDescriptor;
        }

        private static Metadata create(Iterable<PreciseSiteKey> sites) {
            TreeSet<String> descriptors = new TreeSet<>();
            for (PreciseSiteKey site : sites) {
                descriptors.addAll(site.context.methodDescriptors);
            }
            TreeSet<String> types = new TreeSet<>();
            for (String descriptor : descriptors) {
                ParsedMethod method = ParsedMethod.parse(descriptor);
                types.add(method.declaringType);
                types.add(method.returnType);
                types.addAll(method.parameterTypes);
            }
            return new Metadata(assignIds(types), assignIds(descriptors));
        }

        private static TreeMap<String, Integer> assignIds(TreeSet<String> values) {
            TreeMap<String, Integer> ids = new TreeMap<>();
            int nextId = 0;
            for (String value : values) {
                ids.put(value, nextId++);
            }
            return ids;
        }
    }

    private static final class ParsedMethod {
        private final String declaringType;
        private final String name;
        private final String returnType;
        private final List<String> parameterTypes;

        private ParsedMethod(String declaringType, String name, String returnType, List<String> parameterTypes) {
            this.declaringType = declaringType;
            this.name = name;
            this.returnType = returnType;
            this.parameterTypes = parameterTypes;
        }

        private static ParsedMethod parse(String methodDescriptor) {
            int declaringEnd = methodDescriptor.indexOf(";.");
            int parametersStart = methodDescriptor.indexOf('(', declaringEnd + 2);
            int parametersEnd = methodDescriptor.indexOf(')', parametersStart + 1);
            if (declaringEnd < 1 || parametersStart < declaringEnd + 3 || parametersEnd < parametersStart) {
                throw new IllegalArgumentException("Malformed canonical method descriptor: " + methodDescriptor);
            }
            String declaring = typeName(methodDescriptor.substring(0, declaringEnd + 1));
            String name = methodDescriptor.substring(declaringEnd + 2, parametersStart);
            List<String> parameters = new ArrayList<>();
            int cursor = parametersStart + 1;
            while (cursor < parametersEnd) {
                int end = typeDescriptorEnd(methodDescriptor, cursor, parametersEnd);
                parameters.add(typeName(methodDescriptor.substring(cursor, end)));
                cursor = end;
            }
            int returnEnd = typeDescriptorEnd(methodDescriptor, parametersEnd + 1, methodDescriptor.length());
            if (returnEnd != methodDescriptor.length()) {
                throw new IllegalArgumentException("Trailing data in canonical method descriptor: " + methodDescriptor);
            }
            return new ParsedMethod(declaring, name, typeName(methodDescriptor.substring(parametersEnd + 1, returnEnd)), List.copyOf(parameters));
        }

        private static int typeDescriptorEnd(String descriptor, int start, int limit) {
            int cursor = start;
            while (cursor < limit && descriptor.charAt(cursor) == '[') {
                cursor++;
            }
            if (cursor >= limit) {
                throw new IllegalArgumentException("Incomplete type descriptor in " + descriptor);
            }
            if (descriptor.charAt(cursor) == 'L') {
                int semicolon = descriptor.indexOf(';', cursor + 1);
                if (semicolon < 0 || semicolon >= limit) {
                    throw new IllegalArgumentException("Incomplete object descriptor in " + descriptor);
                }
                return semicolon + 1;
            }
            if ("ZBSCIJFDV".indexOf(descriptor.charAt(cursor)) < 0) {
                throw new IllegalArgumentException("Unknown type descriptor in " + descriptor);
            }
            return cursor + 1;
        }

        private static String typeName(String descriptor) {
            int arrays = 0;
            while (arrays < descriptor.length() && descriptor.charAt(arrays) == '[') {
                arrays++;
            }
            if (arrays != 0) {
                return descriptor.replace('/', '.');
            }
            if (descriptor.length() == 1) {
                return switch (descriptor.charAt(0)) {
                    case 'Z' -> "boolean";
                    case 'B' -> "byte";
                    case 'S' -> "short";
                    case 'C' -> "char";
                    case 'I' -> "int";
                    case 'J' -> "long";
                    case 'F' -> "float";
                    case 'D' -> "double";
                    case 'V' -> "void";
                    default -> throw new IllegalArgumentException("Unknown primitive descriptor: " + descriptor);
                };
            }
            if (descriptor.charAt(0) != 'L' || descriptor.charAt(descriptor.length() - 1) != ';') {
                throw new IllegalArgumentException("Malformed object descriptor: " + descriptor);
            }
            return descriptor.substring(1, descriptor.length() - 1).replace('/', '.');
        }
    }
}
