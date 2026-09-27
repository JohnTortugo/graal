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

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.graalvm.collections.EconomicMap;

import com.oracle.svm.hosted.pgo.phases.PGOApplyProfilesPhase;

import jdk.graal.compiler.util.json.JsonParser;

/**
 * Clean-room reader for the public iprof file format, restricted to the {@code conditionalProfiles}
 * category. The parser is intentionally free of any Native Image singletons or universe state so it
 * can be unit-tested in isolation; method-id resolution against the {@code HostedUniverse} happens
 * later (see {@code PGOConditionalProfilesFeature}).
 *
 * <p>
 * Only the {@code version}, {@code types}, {@code methods} and {@code conditionalProfiles} sections
 * are consumed. Every other section (call-count, virtual-invoke, instanceof, monitor, sampling,
 * image-heap) is ignored on purpose: this is a conditional-only consumer.
 *
 * <p>
 * {@code version}, {@code types} and {@code methods} are required and strictly validated. The
 * {@code conditionalProfiles} section is <em>optional</em> per the iprof schema: when it is absent
 * the document is well-formed and parses with an empty conditional-entry list (the consumer is
 * expected to warn that no applicable profile was found). When it IS present it must be a JSON
 * array whose entries are structurally valid.
 *
 * <p>
 * Supported schema versions are {@value #VERSION_1_0_0} and {@value #VERSION_1_1_0}; the
 * {@code conditionalProfiles} encoding is identical across both. Any other version, or any
 * structural violation (missing required section, malformed context string, record array whose
 * length is not a multiple of {@link PGOApplyProfilesPhase#CONDITIONAL_RECORD_SIZE}) is rejected
 * with an {@link IprofFormatException}.
 */
public final class IprofConditionalParser {

    public static final String VERSION_1_0_0 = "1.0.0";
    public static final String VERSION_1_1_0 = "1.1.0";

    private static final String KEY_VERSION = "version";
    private static final String KEY_TYPES = "types";
    private static final String KEY_METHODS = "methods";
    private static final String KEY_CONDITIONAL_PROFILES = "conditionalProfiles";
    private static final String KEY_PRECISE_CONDITIONAL_PROFILES = "ceConditionalProfilesV2";
    private static final String KEY_VIRTUAL_INVOKE_PROFILES = "virtualInvokeProfiles";
    private static final String KEY_SAMPLING_PROFILES = "samplingProfiles";
    private static final String KEY_STAGE = "stage";
    private static final String KEY_SUCCESSORS = "successors";
    private static final String KEY_CONDITION_KIND = "conditionKind";
    private static final String KEY_CONDITION_FINGERPRINT = "conditionFingerprint";
    private static final String KEY_OCCURRENCE = "occurrence";
    private static final String KEY_ID = "id";
    private static final String KEY_NAME = "name";
    private static final String KEY_SIGNATURE = "signature";
    private static final String KEY_CTX = "ctx";
    private static final String KEY_RECORDS = "records";

    private static final char CONTEXT_FRAME_SEPARATOR = '<';
    private static final char CONTEXT_METHOD_BCI_SEPARATOR = ':';

    /** Signals that an iprof stream is not well-formed or is an unsupported version. */
    public static final class IprofFormatException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public IprofFormatException(String message) {
            super(message);
        }
    }

    /** A single {@code methodId:bci} frame of a conditional profile calling context. */
    public record ContextFrame(int methodId, int bci) {
    }

    /**
     * One parsed {@code conditionalProfiles} entry: the calling context (innermost frame first) and
     * the raw {@code [branchBci, key, count]} triplet array exactly as required by
     * {@link PGOApplyProfilesPhase}.
     */
    public record ConditionalEntry(List<ContextFrame> context, long[] records) {
    }

    /** One precise CE branch-site entry with explicit stage and graph-site discriminator. */
    public record PreciseConditionalEntry(String stage, List<ContextFrame> context, int[] successorBcis,
                    String conditionKind, long conditionFingerprint, int occurrence, long[] records) {
    }

    /** Descriptor of a method as declared in the iprof {@code methods} table. */
    /**
     * One parsed {@code virtualInvokeProfiles} entry: the calling context of an indirect call and
     * its records as {@code [typeId, count, typeId, count, ...]} receiver-type pairs.
     */
    public record VirtualInvokeEntry(List<ContextFrame> context, long[] records) {
    }

    /**
     * One parsed {@code samplingProfiles} entry: a sampled call stack (innermost frame first, the
     * innermost BCI being the sampled position) and how often it was observed.
     */
    public record SamplingEntry(List<ContextFrame> context, long count) {
    }

    public record MethodDescriptor(int methodId, String name, int declaringTypeId, int returnTypeId, int[] parameterTypeIds) {
    }

    /** Immutable result of parsing the in-scope sections of an iprof file. */
    public static final class ParsedProfile {
        private final String version;
        private final Map<Integer, String> typeNamesById;
        private final Map<Integer, MethodDescriptor> methodsById;
        private final List<ConditionalEntry> conditionalEntries;
        private final List<PreciseConditionalEntry> preciseConditionalEntries;
        private final List<VirtualInvokeEntry> virtualInvokeEntries;
        private final List<SamplingEntry> samplingEntries;

        ParsedProfile(String version, Map<Integer, String> typeNamesById, Map<Integer, MethodDescriptor> methodsById,
                        List<ConditionalEntry> conditionalEntries, List<PreciseConditionalEntry> preciseConditionalEntries) {
            this(version, typeNamesById, methodsById, conditionalEntries, preciseConditionalEntries, Collections.emptyList(), Collections.emptyList());
        }

        ParsedProfile(String version, Map<Integer, String> typeNamesById, Map<Integer, MethodDescriptor> methodsById,
                        List<ConditionalEntry> conditionalEntries, List<PreciseConditionalEntry> preciseConditionalEntries,
                        List<VirtualInvokeEntry> virtualInvokeEntries, List<SamplingEntry> samplingEntries) {
            this.version = version;
            this.typeNamesById = Collections.unmodifiableMap(typeNamesById);
            this.methodsById = Collections.unmodifiableMap(methodsById);
            this.conditionalEntries = Collections.unmodifiableList(conditionalEntries);
            this.preciseConditionalEntries = Collections.unmodifiableList(preciseConditionalEntries);
            this.virtualInvokeEntries = Collections.unmodifiableList(virtualInvokeEntries);
            this.samplingEntries = Collections.unmodifiableList(samplingEntries);
        }

        public String version() {
            return version;
        }

        public Map<Integer, String> typeNamesById() {
            return typeNamesById;
        }

        public Map<Integer, MethodDescriptor> methodsById() {
            return methodsById;
        }

        public List<ConditionalEntry> conditionalEntries() {
            return conditionalEntries;
        }

        public List<PreciseConditionalEntry> preciseConditionalEntries() {
            return preciseConditionalEntries;
        }

        public List<VirtualInvokeEntry> virtualInvokeEntries() {
            return virtualInvokeEntries;
        }

        public List<SamplingEntry> samplingEntries() {
            return samplingEntries;
        }
    }

    /**
     * Parses the in-scope sections of an iprof document.
     *
     * @param reader a character stream positioned at the start of the JSON document; closed by the
     *            caller.
     * @return the parsed, immutable model.
     * @throws IprofFormatException if the stream is malformed or an unsupported version.
     * @throws IOException if reading from {@code reader} fails.
     */
    @SuppressWarnings("unchecked")
    public ParsedProfile parse(Reader reader) throws IOException {
        Object root = new JsonParser(reader).parse();
        if (!(root instanceof EconomicMap)) {
            throw new IprofFormatException("Iprof root must be a JSON object");
        }
        EconomicMap<String, Object> top = (EconomicMap<String, Object>) root;

        String version = requireString(top, KEY_VERSION);
        if (!VERSION_1_0_0.equals(version) && !VERSION_1_1_0.equals(version)) {
            throw new IprofFormatException("Unsupported iprof version '" + version + "'; supported: " + VERSION_1_0_0 + ", " + VERSION_1_1_0);
        }

        Map<Integer, String> typeNamesById = parseTypes(requireList(top, KEY_TYPES));
        Map<Integer, MethodDescriptor> methodsById = parseMethods(requireList(top, KEY_METHODS));
        /*
         * conditionalProfiles is an optional category per the iprof schema. When the section is
         * absent the file is well-formed; it simply carries no conditional data. We parse this as
         * an empty list rather than rejecting the file as malformed. The consumer
         * (PGOConditionalProfilesFeature) is responsible for emitting a prominent
         * no-applicable-profile warning in that case. When the key IS present it must still be a
         * JSON array and every entry must be structurally valid.
         */
        List<ConditionalEntry> conditionalEntries = optionalConditionalProfiles(top);
        List<PreciseConditionalEntry> preciseConditionalEntries = optionalPreciseConditionalProfiles(top);
        List<VirtualInvokeEntry> virtualInvokeEntries = optionalVirtualInvokeProfiles(top, typeNamesById);
        List<SamplingEntry> samplingEntries = optionalSamplingProfiles(top);

        return new ParsedProfile(version, typeNamesById, methodsById, conditionalEntries, preciseConditionalEntries, virtualInvokeEntries, samplingEntries);
    }

    private static Map<Integer, String> parseTypes(List<Object> types) {
        Map<Integer, String> result = new HashMap<>(types.size());
        for (Object element : types) {
            EconomicMap<String, Object> type = asObject(element, KEY_TYPES);
            int id = requireInt(type, KEY_ID);
            String name = requireString(type, KEY_NAME);
            if (name.isEmpty()) {
                throw new IprofFormatException("Empty type name for type id " + id);
            }
            if (result.put(id, name) != null) {
                throw new IprofFormatException("Duplicate type id " + id);
            }
        }
        return result;
    }

    private static Map<Integer, MethodDescriptor> parseMethods(List<Object> methods) {
        Map<Integer, MethodDescriptor> result = new HashMap<>(methods.size());
        for (Object element : methods) {
            EconomicMap<String, Object> method = asObject(element, KEY_METHODS);
            int id = requireInt(method, KEY_ID);
            String name = requireString(method, KEY_NAME);
            List<Object> signature = requireList(method, KEY_SIGNATURE);
            if (signature.size() < 2) {
                throw new IprofFormatException("Method id " + id + " signature must contain at least a declaring type and a return type");
            }
            int declaringTypeId = asInt(signature.get(0), KEY_SIGNATURE);
            int returnTypeId = asInt(signature.get(1), KEY_SIGNATURE);
            int[] parameterTypeIds = new int[signature.size() - 2];
            for (int i = 2; i < signature.size(); i++) {
                parameterTypeIds[i - 2] = asInt(signature.get(i), KEY_SIGNATURE);
            }
            if (result.put(id, new MethodDescriptor(id, name, declaringTypeId, returnTypeId, parameterTypeIds)) != null) {
                throw new IprofFormatException("Duplicate method id " + id);
            }
        }
        return result;
    }

    /**
     * Returns the parsed {@code conditionalProfiles} entries, or an empty list when the section is
     * absent. Absence is legal (the category is optional in the iprof schema); presence with a
     * non-array value, or any structurally invalid entry, is still rejected.
     */
    private static List<ConditionalEntry> optionalConditionalProfiles(EconomicMap<String, Object> top) {
        Object value = top.get(KEY_CONDITIONAL_PROFILES);
        if (value == null) {
            return Collections.emptyList();
        }
        if (!(value instanceof List)) {
            throw new IprofFormatException("Key '" + KEY_CONDITIONAL_PROFILES + "' must be a JSON array");
        }
        @SuppressWarnings("unchecked")
        List<Object> conditionalProfiles = (List<Object>) value;
        return parseConditionalProfiles(conditionalProfiles);
    }

    private static List<PreciseConditionalEntry> optionalPreciseConditionalProfiles(EconomicMap<String, Object> top) {
        Object value = top.get(KEY_PRECISE_CONDITIONAL_PROFILES);
        if (value == null) {
            return Collections.emptyList();
        }
        if (!(value instanceof List)) {
            throw new IprofFormatException("Key '" + KEY_PRECISE_CONDITIONAL_PROFILES + "' must be a JSON array");
        }
        @SuppressWarnings("unchecked")
        List<Object> preciseProfiles = (List<Object>) value;
        List<PreciseConditionalEntry> result = new ArrayList<>(preciseProfiles.size());
        for (Object element : preciseProfiles) {
            EconomicMap<String, Object> entry = asObject(element, KEY_PRECISE_CONDITIONAL_PROFILES);
            String stage = requireString(entry, KEY_STAGE);
            String ctx = requireString(entry, KEY_CTX);
            List<Object> rawSuccessors = requireList(entry, KEY_SUCCESSORS);
            if (rawSuccessors.size() != 2) {
                throw new IprofFormatException("Precise conditional context '" + ctx + "' must contain exactly two successor BCIs");
            }
            int[] successorBcis = {asInt(rawSuccessors.get(0), KEY_SUCCESSORS), asInt(rawSuccessors.get(1), KEY_SUCCESSORS)};
            String conditionKind = requireString(entry, KEY_CONDITION_KIND);
            if (conditionKind.isEmpty()) {
                throw new IprofFormatException("Empty condition kind for precise conditional context '" + ctx + "'");
            }
            long fingerprint;
            try {
                fingerprint = Long.parseUnsignedLong(requireString(entry, KEY_CONDITION_FINGERPRINT), 16);
            } catch (NumberFormatException exception) {
                throw new IprofFormatException("Invalid condition fingerprint for precise conditional context '" + ctx + "'");
            }
            int occurrence = requireInt(entry, KEY_OCCURRENCE);
            if (occurrence < 0) {
                throw new IprofFormatException("Negative occurrence for precise conditional context '" + ctx + "'");
            }
            long[] records = parseRecords(requireList(entry, KEY_RECORDS), ctx);
            result.add(new PreciseConditionalEntry(stage, parseContext(ctx), successorBcis, conditionKind, fingerprint, occurrence, records));
        }
        return result;
    }

    /**
     * Returns the parsed {@code virtualInvokeProfiles} entries, or an empty list when the section
     * is absent. Records are {@code [typeId, count]} pairs; every type id must be declared in the
     * {@code types} section.
     */
    private static List<VirtualInvokeEntry> optionalVirtualInvokeProfiles(EconomicMap<String, Object> top, Map<Integer, String> typeNamesById) {
        Object value = top.get(KEY_VIRTUAL_INVOKE_PROFILES);
        if (value == null) {
            return Collections.emptyList();
        }
        if (!(value instanceof List)) {
            throw new IprofFormatException("Key '" + KEY_VIRTUAL_INVOKE_PROFILES + "' must be a JSON array");
        }
        @SuppressWarnings("unchecked")
        List<Object> profiles = (List<Object>) value;
        List<VirtualInvokeEntry> result = new ArrayList<>(profiles.size());
        for (Object element : profiles) {
            EconomicMap<String, Object> entry = asObject(element, KEY_VIRTUAL_INVOKE_PROFILES);
            String ctx = requireString(entry, KEY_CTX);
            List<Object> rawRecords = requireList(entry, KEY_RECORDS);
            if (rawRecords.isEmpty() || rawRecords.size() % VIRTUAL_INVOKE_RECORD_SIZE != 0) {
                throw new IprofFormatException("Virtual invoke records length " + rawRecords.size() + " for context '" + ctx + "' is not a positive multiple of " + VIRTUAL_INVOKE_RECORD_SIZE);
            }
            long[] records = new long[rawRecords.size()];
            for (int i = 0; i < rawRecords.size(); i++) {
                records[i] = asLong(rawRecords.get(i), KEY_RECORDS);
            }
            for (int i = 0; i < records.length; i += VIRTUAL_INVOKE_RECORD_SIZE) {
                if (records[i] < 0 || records[i] > Integer.MAX_VALUE || !typeNamesById.containsKey((int) records[i])) {
                    throw new IprofFormatException("Virtual invoke context '" + ctx + "' references undeclared type id " + records[i]);
                }
                if (records[i + 1] < 0) {
                    throw new IprofFormatException("Negative receiver count for virtual invoke context '" + ctx + "'");
                }
            }
            result.add(new VirtualInvokeEntry(parseContext(ctx), records));
        }
        return result;
    }

    public static final int VIRTUAL_INVOKE_RECORD_SIZE = 2;

    /** Returns the parsed {@code samplingProfiles} entries; each has exactly one record, the sample count. */
    private static List<SamplingEntry> optionalSamplingProfiles(EconomicMap<String, Object> top) {
        Object value = top.get(KEY_SAMPLING_PROFILES);
        if (value == null) {
            return Collections.emptyList();
        }
        if (!(value instanceof List)) {
            throw new IprofFormatException("Key '" + KEY_SAMPLING_PROFILES + "' must be a JSON array");
        }
        @SuppressWarnings("unchecked")
        List<Object> profiles = (List<Object>) value;
        List<SamplingEntry> result = new ArrayList<>(profiles.size());
        for (Object element : profiles) {
            EconomicMap<String, Object> entry = asObject(element, KEY_SAMPLING_PROFILES);
            String ctx = requireString(entry, KEY_CTX);
            List<Object> rawRecords = requireList(entry, KEY_RECORDS);
            if (rawRecords.size() != 1) {
                throw new IprofFormatException("Sampling records for context '" + ctx + "' must contain exactly one count");
            }
            long count = asLong(rawRecords.get(0), KEY_RECORDS);
            if (count < 0) {
                throw new IprofFormatException("Negative sample count for context '" + ctx + "'");
            }
            result.add(new SamplingEntry(parseContext(ctx), count));
        }
        return result;
    }

    private static List<ConditionalEntry> parseConditionalProfiles(List<Object> conditionalProfiles) {
        List<ConditionalEntry> result = new ArrayList<>(conditionalProfiles.size());
        for (Object element : conditionalProfiles) {
            EconomicMap<String, Object> entry = asObject(element, KEY_CONDITIONAL_PROFILES);
            String ctx = requireString(entry, KEY_CTX);
            List<Object> rawRecords = requireList(entry, KEY_RECORDS);
            List<ContextFrame> context = parseContext(ctx);
            long[] records = parseRecords(rawRecords, ctx);
            result.add(new ConditionalEntry(context, records));
        }
        return result;
    }

    /**
     * Splits a context string of the form {@code methodId:bci<methodId:bci<...} into frames,
     * preserving the innermost-first order used by the profile and by
     * {@link PGOApplyProfilesPhase#createPointContext}.
     */
    static List<ContextFrame> parseContext(String ctx) {
        if (ctx.isEmpty()) {
            throw new IprofFormatException("Empty conditional context string");
        }
        List<ContextFrame> frames = new ArrayList<>();
        int start = 0;
        while (start <= ctx.length()) {
            int separator = ctx.indexOf(CONTEXT_FRAME_SEPARATOR, start);
            int end = separator < 0 ? ctx.length() : separator;
            frames.add(parseFrame(ctx.substring(start, end), ctx));
            if (separator < 0) {
                break;
            }
            start = separator + 1;
        }
        return frames;
    }

    private static ContextFrame parseFrame(String frame, String ctx) {
        int colon = frame.indexOf(CONTEXT_METHOD_BCI_SEPARATOR);
        if (colon <= 0 || colon == frame.length() - 1) {
            throw new IprofFormatException("Malformed context frame '" + frame + "' in context '" + ctx + "'");
        }
        int methodId = parseNonNegativeInt(frame.substring(0, colon), ctx);
        int bci = parseInt(frame.substring(colon + 1), ctx);
        return new ContextFrame(methodId, bci);
    }

    private static long[] parseRecords(List<Object> rawRecords, String ctx) {
        if (rawRecords.isEmpty() || rawRecords.size() % PGOApplyProfilesPhase.CONDITIONAL_RECORD_SIZE != 0) {
            throw new IprofFormatException("Conditional records length " + rawRecords.size() + " for context '" + ctx +
                            "' is not a positive multiple of " + PGOApplyProfilesPhase.CONDITIONAL_RECORD_SIZE);
        }
        long[] records = new long[rawRecords.size()];
        for (int i = 0; i < rawRecords.size(); i++) {
            records[i] = asLong(rawRecords.get(i), KEY_RECORDS);
        }
        return records;
    }

    private static int parseInt(String value, String ctx) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IprofFormatException("Expected integer but found '" + value + "' in context '" + ctx + "'");
        }
    }

    private static int parseNonNegativeInt(String value, String ctx) {
        int parsed = parseInt(value, ctx);
        if (parsed < 0) {
            throw new IprofFormatException("Expected non-negative method id but found " + parsed + " in context '" + ctx + "'");
        }
        return parsed;
    }

    // --- JSON access helpers with strict typing -------------------------------------------------

    @SuppressWarnings("unchecked")
    private static EconomicMap<String, Object> asObject(Object value, String owner) {
        if (!(value instanceof EconomicMap)) {
            throw new IprofFormatException("Expected JSON object in '" + owner + "'");
        }
        return (EconomicMap<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> requireList(EconomicMap<String, Object> map, String key) {
        Object value = require(map, key);
        if (!(value instanceof List)) {
            throw new IprofFormatException("Key '" + key + "' must be a JSON array");
        }
        return (List<Object>) value;
    }

    private static String requireString(EconomicMap<String, Object> map, String key) {
        Object value = require(map, key);
        if (!(value instanceof String)) {
            throw new IprofFormatException("Key '" + key + "' must be a string");
        }
        return (String) value;
    }

    private static int requireInt(EconomicMap<String, Object> map, String key) {
        return asInt(require(map, key), key);
    }

    private static Object require(EconomicMap<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            throw new IprofFormatException("Missing required key '" + key + "'");
        }
        return value;
    }

    private static int asInt(Object value, String owner) {
        long asLong = asLong(value, owner);
        if (asLong < Integer.MIN_VALUE || asLong > Integer.MAX_VALUE) {
            throw new IprofFormatException("Value " + asLong + " in '" + owner + "' does not fit in an int");
        }
        return (int) asLong;
    }

    private static long asLong(Object value, String owner) {
        if (value instanceof Integer || value instanceof Long) {
            return ((Number) value).longValue();
        }
        throw new IprofFormatException("Expected integral number in '" + owner + "' but found " + describe(value));
    }

    private static String describe(Object value) {
        return value == null ? "null" : value.getClass().getTypeName();
    }
}
