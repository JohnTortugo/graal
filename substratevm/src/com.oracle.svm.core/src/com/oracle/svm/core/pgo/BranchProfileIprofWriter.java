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
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import jdk.graal.compiler.util.json.JsonWriter;

/** Writes the conditional-only subset of the public iprof 1.1.0 format. */
public final class BranchProfileIprofWriter {

    private static final String VERSION = "1.1.0";

    private BranchProfileIprofWriter() {
    }

    public static DumpStatistics write(Path path, List<BranchProfileCounter> counters) throws IOException {
        try (JsonWriter writer = new JsonWriter(path)) {
            return write(writer, counters);
        }
    }

    /** Unit-testable entry point that writes to an already-open character stream. */
    public static DumpStatistics write(Writer output, List<BranchProfileCounter> counters) throws IOException {
        try (JsonWriter writer = new JsonWriter(output)) {
            return write(writer, counters);
        }
    }

    private static DumpStatistics write(JsonWriter writer, List<BranchProfileCounter> counters) throws IOException {
        List<BranchProfileCounter> activeCounters = counters.stream()
                        .filter(counter -> counter.getTrueCount() != 0 || counter.getFalseCount() != 0)
                        .toList();
        Metadata metadata = Metadata.create(activeCounters);

        writer.appendObjectStart();
        writer.appendKeyValue("version", VERSION).appendSeparator();
        writeTypes(writer, metadata).appendSeparator();
        writeMethods(writer, metadata).appendSeparator();
        writeConditionalProfiles(writer, metadata, activeCounters);
        writer.appendObjectEnd();

        long events = activeCounters.stream().mapToLong(counter -> counter.getTrueCount() + counter.getFalseCount()).sum();
        return new DumpStatistics(metadata.typesByName.size(), metadata.methodsByDescriptor.size(), activeCounters.size(), events);
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

    private static void writeConditionalProfiles(JsonWriter writer, Metadata metadata, List<BranchProfileCounter> counters) throws IOException {
        writer.quote("conditionalProfiles").appendFieldSeparator().appendArrayStart();
        boolean first = true;
        for (BranchProfileCounter counter : counters) {
            if (counter.getTrueCount() == 0 && counter.getFalseCount() == 0) {
                continue;
            }
            if (!first) {
                writer.appendSeparator();
            }
            first = false;
            writer.appendObjectStart().appendKeyValue("ctx", context(metadata, counter)).appendSeparator()
                            .quote("records").appendFieldSeparator().appendArrayStart()
                            .printValue(counter.getTrueSuccessorBci()).appendSeparator().printValue(0).appendSeparator().printValue(counter.getTrueCount()).appendSeparator()
                            .printValue(counter.getFalseSuccessorBci()).appendSeparator().printValue(1).appendSeparator().printValue(counter.getFalseCount())
                            .appendArrayEnd().appendObjectEnd();
        }
        writer.appendArrayEnd();
    }

    private static String context(Metadata metadata, BranchProfileCounter counter) {
        StringBuilder result = new StringBuilder();
        String[] descriptors = counter.getMethodDescriptors();
        int[] bcis = counter.getContextBcis();
        for (int i = 0; i < descriptors.length; i++) {
            if (i != 0) {
                result.append('<');
            }
            result.append(metadata.methodsByDescriptor.get(descriptors[i])).append(':').append(bcis[i]);
        }
        return result.toString();
    }

    public record DumpStatistics(int types, int methods, int conditionalProfiles, long recordedEvents) {
    }

    private static final class Metadata {
        private final TreeMap<String, Integer> typesByName;
        private final TreeMap<String, Integer> methodsByDescriptor;

        private Metadata(TreeMap<String, Integer> typesByName, TreeMap<String, Integer> methodsByDescriptor) {
            this.typesByName = typesByName;
            this.methodsByDescriptor = methodsByDescriptor;
        }

        private static Metadata create(List<BranchProfileCounter> counters) {
            TreeSet<String> descriptors = new TreeSet<>();
            for (BranchProfileCounter counter : counters) {
                descriptors.addAll(Arrays.asList(counter.getMethodDescriptors()));
            }

            TreeSet<String> types = new TreeSet<>();
            for (String descriptor : descriptors) {
                ParsedMethod method = ParsedMethod.parse(descriptor);
                types.add(method.declaringType);
                types.add(method.returnType);
                types.addAll(method.parameterTypes);
            }

            TreeMap<String, Integer> typeIds = assignIds(types);
            TreeMap<String, Integer> methodIds = assignIds(descriptors);
            return new Metadata(typeIds, methodIds);
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
            String returnType = typeName(methodDescriptor.substring(parametersEnd + 1, returnEnd));
            return new ParsedMethod(declaring, name, returnType, List.copyOf(parameters));
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
