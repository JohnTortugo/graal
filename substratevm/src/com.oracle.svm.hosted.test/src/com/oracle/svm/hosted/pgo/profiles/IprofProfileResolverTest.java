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

import java.io.IOException;
import java.io.StringReader;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.Assert;
import org.junit.Test;

import com.oracle.graal.pointsto.meta.AnalysisType;
import com.oracle.svm.core.pgo.IprofFormat;
import com.oracle.svm.hosted.meta.HostedMethod;
import com.oracle.svm.hosted.pgo.IprofParser;
import com.oracle.svm.hosted.pgo.IprofParser.MethodDescriptor;
import com.oracle.svm.hosted.pgo.IprofParser.ParsedIprofProfile;
import com.oracle.svm.hosted.pgo.profiles.IprofProfilesLookup.FrameKey;
import com.oracle.svm.hosted.pgo.profiles.PGOProfilesLookup.ProfiledValue;

import jdk.graal.compiler.graph.NodeSourcePosition;
import jdk.vm.ci.code.BytecodePosition;
import jdk.vm.ci.meta.JavaType;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import jdk.vm.ci.meta.ResolvedJavaType;
import jdk.vm.ci.meta.Signature;

/**
 * Tests {@link IprofProfileResolver}'s signature canonicalization and table build (context
 * resolution, unresolved-context dropping, duplicate-context merge), and the
 * {@link IprofProfilesLookup} queries on that table: exact and shortened-context matches, hit, miss
 * and coverage counters, and the recorded profile categories.
 */
public class IprofProfileResolverTest {

    // Descriptors that match the TYPES_AND_METHODS table below.
    private static final String FOO = "Lcom/example/Foo;";
    // com.example.Foo.bar(int)void
    static final String BAR_DESC = FOO + ".bar(I)V";
    // com.example.Foo.baz(int)java.lang.String
    private static final String BAZ_DESC = FOO + ".baz(I)Ljava/lang/String;";

    private static final String TYPES_AND_METHODS = "\"types\":[" +
                    "{\"id\":4,\"name\":\"int\"}," +
                    "{\"id\":8,\"name\":\"void\"}," +
                    "{\"id\":100,\"name\":\"com.example.Foo\"}," +
                    "{\"id\":101,\"name\":\"java.lang.String\"}" +
                    "]," +
                    "\"methods\":[" +
                    "{\"id\":1001,\"name\":\"bar\",\"signature\":[100,8,4]}," +
                    "{\"id\":1002,\"name\":\"baz\",\"signature\":[100,101,4]}" +
                    "],";

    /**
     * Builds a lookup for a profile whose methods with the given descriptors are in the image.
     * Image methods cannot be created in a unit test, so the descriptors map to {@code null}: the
     * resolver only needs to know which are present, and stack samples, which need the method,
     * stay unresolved.
     */
    static IprofProfilesLookup buildLookup(ParsedIprofProfile profile, Set<String> presentDescriptors) {
        return buildLookup(profile, presentDescriptors, Map.of());
    }

    static IprofProfilesLookup buildLookup(ParsedIprofProfile profile, Set<String> presentDescriptors, Map<String, AnalysisType> typesByDescriptor) {
        Map<String, HostedMethod> methods = new HashMap<>();
        for (String descriptor : presentDescriptors) {
            methods.put(descriptor, null);
        }
        return IprofProfileResolver.buildLookup(profile, methods, typesByDescriptor);
    }

    /** A lookup that holds only the given receiver profiles. */
    static IprofProfilesLookup receiverLookup(Map<List<FrameKey>, Map<AnalysisType, Long>> receivers) {
        return new IprofProfilesLookup(Map.of(), null, receivers, null, Map.of(), null, Map.of(), Map.of(), null);
    }

    /** A lookup that holds only the given stack samples. */
    static IprofProfilesLookup samplingLookup(Map<NodeSourcePosition, Long> samples) {
        return new IprofProfilesLookup(Map.of(), null, Map.of(), null, samples, null, Map.of(), Map.of(), null);
    }

    private static ParsedIprofProfile parse(String conditionalProfiles) throws IOException {
        String json = "{\"version\":\"1.0.0\"," + TYPES_AND_METHODS + "\"conditionalProfiles\":[" + conditionalProfiles + "]}";
        return new IprofParser().parse(new StringReader(json));
    }

    // --- signature canonicalization -------------------------------------------------------------

    @Test
    public void convertsIprofTypeNamesInMethodDescriptors() {
        Map<Integer, String> typeNames = Map.of(1, "com.example.Foo$$Lambda/0x1234", 2, "[Ljava.lang.String;", 3, "boolean", 4, "[Z", 5, "void", 6, "int");
        Assert.assertEquals("Lcom/example/Foo$$Lambda.0x1234;.m([Ljava/lang/String;Z[ZI)V",
                        IprofProfileResolver.descriptorForProfileMethod(new MethodDescriptor(7, "m", 1, 5, new int[]{2, 3, 4, 6}), typeNames));
        Assert.assertNull("a type id that the types table lacks", IprofProfileResolver.descriptorForProfileMethod(new MethodDescriptor(8, "m", 1, 5, new int[]{9}), typeNames));
    }

    @Test
    public void buildsCanonicalMethodDescriptor() throws IOException {
        ParsedIprofProfile parsed = parse("{\"ctx\":\"1001:9\",\"records\":[20,0,10,53,1,1]}");
        MethodDescriptor bar = parsed.methodsById().get(1001);
        MethodDescriptor baz = parsed.methodsById().get(1002);
        Assert.assertEquals(BAR_DESC, IprofProfileResolver.descriptorForProfileMethod(bar, parsed.typeNamesById()));
        Assert.assertEquals(BAZ_DESC, IprofProfileResolver.descriptorForProfileMethod(baz, parsed.typeNamesById()));
    }

    // --- table build: context resolution --------------------------------------------------------

    @Test
    public void resolvesSingleFrameContextWhenMethodPresent() throws IOException {
        ParsedIprofProfile parsed = parse("{\"ctx\":\"1001:9\",\"records\":[20,0,10,53,1,1]}");
        IprofProfilesLookup lookup = buildLookup(parsed, Set.of(BAR_DESC));

        Assert.assertTrue(lookup.profileCategoryRecorded("conditionalProfiles"));
        ConditionalProfileDiagnostics d = lookup.conditionalDiagnostics();
        Assert.assertEquals(1, d.totalEntries());
        Assert.assertEquals(1, d.resolvedEntries());
        Assert.assertEquals(1, d.singleFrameEntries());
        Assert.assertEquals(0, d.inlinedContextEntries());
        Assert.assertEquals(0, d.unresolvedEntries());
    }

    @Test
    public void resolvesInlinedContextWhenAllFramesPresent() throws IOException {
        ParsedIprofProfile parsed = parse("{\"ctx\":\"1001:9<1002:1\",\"records\":[20,0,10,53,1,1]}");
        IprofProfilesLookup lookup = buildLookup(parsed, Set.of(BAR_DESC, BAZ_DESC));

        ConditionalProfileDiagnostics d = lookup.conditionalDiagnostics();
        Assert.assertEquals(1, d.resolvedEntries());
        Assert.assertEquals(1, d.inlinedContextEntries());
        Assert.assertEquals(0, d.singleFrameEntries());
    }

    @Test
    public void dropsEntryWhenAnyContextFrameMethodAbsent() throws IOException {
        // Inlined context needs both bar and baz; only bar is present -> entry dropped.
        ParsedIprofProfile parsed = parse("{\"ctx\":\"1001:9<1002:1\",\"records\":[20,0,10,53,1,1]}");
        IprofProfilesLookup lookup = buildLookup(parsed, Set.of(BAR_DESC));

        ConditionalProfileDiagnostics d = lookup.conditionalDiagnostics();
        Assert.assertEquals(0, d.resolvedEntries());
        Assert.assertEquals(1, d.unresolvedEntries());
        Assert.assertFalse(lookup.profileCategoryRecorded("conditionalProfiles"));
    }

    @Test
    public void listsDroppedEntriesWithTheFramesTheImageLacks() throws IOException {
        ParsedIprofProfile parsed = parse("{\"ctx\":\"1001:9<1002:1\",\"records\":[20,0,10,53,1,1]},{\"ctx\":\"1001:12\",\"records\":[20,0,10,53,1,1]}");
        Map<String, HostedMethod> methods = new HashMap<>();
        methods.put(BAR_DESC, null);
        List<String> dropped = new ArrayList<>();
        IprofProfileResolver.buildLookup(parsed, methods, Map.of(), dropped);

        /* Only the entry with the absent frame is dropped; the absent frame is marked, the present one is not. */
        Assert.assertEquals(List.of("branch or switch: " + BAR_DESC + ":9 < " + BAZ_DESC + ":1 (not in the image)"), dropped);
    }

    // --- table build: duplicate-context merge (first-wins) --------------------------------------

    @Test
    public void mergesDuplicateContextFirstWins() throws IOException {
        ParsedIprofProfile parsed = parse(
                        "{\"ctx\":\"1001:9\",\"records\":[20,0,10,53,1,1]}," +
                                        "{\"ctx\":\"1001:9\",\"records\":[20,0,1,53,1,10]}");
        IprofProfilesLookup lookup = buildLookup(parsed, Set.of(BAR_DESC));

        ConditionalProfileDiagnostics d = lookup.conditionalDiagnostics();
        Assert.assertEquals(2, d.totalEntries());
        Assert.assertEquals(1, d.resolvedEntries());
        Assert.assertEquals(1, d.duplicateContextEntries());
    }

    // --- opposite-branch profiles remain distinct across distinct contexts ----------------------

    @Test
    public void oppositeBranchProfilesForDistinctContextsBothResolve() throws IOException {
        // Same method, different bci in the leaf frame -> distinct contexts, both retained.
        ParsedIprofProfile parsed = parse(
                        "{\"ctx\":\"1001:9\",\"records\":[20,0,10,53,1,1]}," +
                                        "{\"ctx\":\"1001:42\",\"records\":[20,0,1,53,1,10]}");
        IprofProfilesLookup lookup = buildLookup(parsed, Set.of(BAR_DESC));

        ConditionalProfileDiagnostics d = lookup.conditionalDiagnostics();
        Assert.assertEquals(2, d.resolvedEntries());
        Assert.assertEquals(0, d.duplicateContextEntries());
    }

    @Test
    public void clearDisablesLookup() throws IOException {
        ParsedIprofProfile parsed = parse("{\"ctx\":\"1001:9\",\"records\":[20,0,10,53,1,1]}");
        IprofProfilesLookup lookup = buildLookup(parsed, Set.of(BAR_DESC));
        Assert.assertTrue(lookup.profileCategoryRecorded("conditionalProfiles"));
        lookup.clear();
        Assert.assertFalse(lookup.profileCategoryRecorded("conditionalProfiles"));
        Assert.assertTrue(lookup.getConditionalProfile(null).isEmpty());
    }

    /** The fixture has only a conditionalProfiles section, so no other category is recorded. */
    @Test
    public void sectionsAbsentFromTheProfileAreNotRecorded() throws IOException {
        ParsedIprofProfile parsed = parse("{\"ctx\":\"1001:9\",\"records\":[20,0,10,53,1,1]}");
        IprofProfilesLookup lookup = buildLookup(parsed, Set.of(BAR_DESC));
        Assert.assertTrue(lookup.profileCategoryRecorded(IprofFormat.CONDITIONAL_PROFILES));
        Assert.assertFalse(lookup.profileCategoryRecorded(IprofFormat.CALL_COUNT_PROFILES));
        Assert.assertFalse(lookup.profileCategoryRecorded(IprofFormat.VIRTUAL_INVOKE_PROFILES));
        Assert.assertFalse(lookup.profileCategoryRecorded(IprofFormat.SAMPLING_PROFILES));
    }

    // --- empty / no-applicable-profile behavior -------------------------------------------------

    @Test
    public void emptyProfileYieldsNoRecordedCategory() throws IOException {
        // conditionalProfiles present but empty -> nothing resolves -> category not recorded.
        ParsedIprofProfile parsed = parse("");
        IprofProfilesLookup lookup = buildLookup(parsed, Set.of(BAR_DESC));
        Assert.assertEquals(0, lookup.conditionalDiagnostics().totalEntries());
        Assert.assertEquals(0, lookup.conditionalDiagnostics().resolvedEntries());
        Assert.assertFalse(lookup.profileCategoryRecorded("conditionalProfiles"));
    }

    // --- applied hit/miss counters --------------------------------------------------------------

    /* A query for a context the profile has returns that entry as is, without any fallback. */
    @Test
    public void exactContextReturnsItsEntry() throws IOException {
        ParsedIprofProfile parsed = parse("{\"ctx\":\"1001:9\",\"records\":[20,0,11,53,1,11]}");
        IprofProfilesLookup lookup = buildLookup(parsed, Set.of(BAR_DESC));
        BytecodePosition context = new BytecodePosition(null, mockBarMethod(), 9);

        Optional<ProfiledValue<long[]>> result = lookup.getConditionalProfile(context);
        Assert.assertTrue(result.isPresent());
        Assert.assertArrayEquals(new long[]{20, 0, 11, 53, 1, 11}, result.get().value());
        Assert.assertEquals(1, lookup.hitCount());
        Assert.assertEquals(0, lookup.contextFallbackCount());
    }

    /* A branch queried under an inlining chain the profile never saw falls back to the branch's shorter-context entry. */
    @Test
    public void unseenInliningContextFallsBackToShorterContext() throws IOException {
        String entry = "{\"ctx\":\"1001:9\",\"records\":[20,0,7,53,1,3]}";
        String json = "{\"version\":\"1.1.0\"," + TYPES_AND_METHODS + "\"conditionalProfiles\":[" + entry + "]}";
        IprofProfilesLookup lookup = buildLookup(
                        new IprofParser().parse(new StringReader(json)), Set.of(BAR_DESC));
        BytecodePosition caller = new BytecodePosition(null, mockBarMethod(), 40);
        BytecodePosition inlined = new BytecodePosition(caller, mockBarMethod(), 9);

        Optional<ProfiledValue<long[]>> result = lookup.getConditionalProfile(inlined);
        Assert.assertTrue(result.isPresent());
        Assert.assertArrayEquals(new long[]{20, 0, 7, 53, 1, 3}, result.get().value());
        Assert.assertEquals(1, lookup.contextFallbackCount());
        Assert.assertEquals(1, lookup.contextFallbackDroppedFrames());

        lookup.setContextFallback(false);
        Assert.assertTrue(lookup.getConditionalProfile(inlined).isEmpty());
    }

    /* A missing chain falls back to the sum over every profiled chain that shares its longest prefix. */
    @Test
    public void unseenInliningContextUsesSumOfLongestMatchingPrefix() throws IOException {
        String viaA = "{\"ctx\":\"1001:9<1001:40\",\"records\":[20,0,7,53,1,3]}";
        String viaB = "{\"ctx\":\"1001:9<1001:50\",\"records\":[20,0,1,53,1,9]}";
        String json = "{\"version\":\"1.1.0\"," + TYPES_AND_METHODS + "\"conditionalProfiles\":[" + viaA + "," + viaB + "]}";
        IprofProfilesLookup lookup = buildLookup(
                        new IprofParser().parse(new StringReader(json)), Set.of(BAR_DESC));

        BytecodePosition newCaller = new BytecodePosition(new BytecodePosition(null, mockBarMethod(), 60), mockBarMethod(), 9);
        long[] summed = lookup.getConditionalProfile(newCaller).orElseThrow().value();
        Assert.assertArrayEquals(new long[]{20, 0, 8, 53, 1, 12}, summed);

        BytecodePosition deeperViaA = new BytecodePosition(new BytecodePosition(new BytecodePosition(null, mockBarMethod(), 70), mockBarMethod(), 40), mockBarMethod(), 9);
        long[] viaAOnly = lookup.getConditionalProfile(deeperViaA).orElseThrow().value();
        Assert.assertArrayEquals(new long[]{20, 0, 7, 53, 1, 3}, viaAOnly);
        Assert.assertEquals(2, lookup.contextFallbackCount());
        Assert.assertEquals(2, lookup.contextFallbackDroppedFrames());
    }

    @Test
    public void tracksDistinctSuccessorApplicationCoverage() throws IOException {
        ParsedIprofProfile parsed = parse(
                        "{\"ctx\":\"1001:9\",\"records\":[20,0,10,53,1,1]}," +
                                        "{\"ctx\":\"1001:42\",\"records\":[20,0,1,53,1,10]}");
        IprofProfilesLookup lookup = buildLookup(parsed, Set.of(BAR_DESC));
        BytecodePosition queried = new BytecodePosition(null, mockBarMethod(), 9);

        Assert.assertTrue(lookup.getConditionalProfile(queried).isPresent());
        Assert.assertEquals(1, lookup.matchedContextCount());
        Assert.assertEquals(1, lookup.unusedResolvedContextCount());

        lookup.recordConditionalProfileApplication(queried, 2, 0);
        Assert.assertEquals(1, lookup.unappliedMatchedContextCount());
        lookup.recordConditionalProfileApplication(queried, 2, 1);
        Assert.assertEquals(1, lookup.partiallyAppliedContextCount());
        lookup.recordConditionalProfileApplication(queried, 2, 2);
        Assert.assertEquals(1, lookup.fullyAppliedContextCount());
        Assert.assertEquals(0, lookup.partiallyAppliedContextCount());
        Assert.assertEquals(0, lookup.unappliedMatchedContextCount());
    }

    static ResolvedJavaMethod mockBarMethod() {
        JavaType intType = proxy(JavaType.class, "I");
        JavaType voidType = proxy(JavaType.class, "V");
        ResolvedJavaType declaringType = proxy(ResolvedJavaType.class, FOO);
        /* Default methods such as toMethodDescriptor() run their JVMCI implementation on the mock. */
        Signature signature = (Signature) Proxy.newProxyInstance(Signature.class.getClassLoader(), new Class<?>[]{Signature.class}, (instance, method, arguments) -> switch (method.getName()) {
            case "getParameterCount" -> 1;
            case "getParameterType" -> intType;
            case "getReturnType" -> voidType;
            default -> method.isDefault() ? InvocationHandler.invokeDefault(instance, method, arguments) : defaultValue(method.getReturnType());
        });
        return (ResolvedJavaMethod) Proxy.newProxyInstance(ResolvedJavaMethod.class.getClassLoader(), new Class<?>[]{ResolvedJavaMethod.class}, (instance, method, arguments) -> switch (method
                        .getName()) {
            case "getDeclaringClass" -> declaringType;
            case "getName" -> "bar";
            case "getSignature" -> signature;
            case "hashCode" -> System.identityHashCode(instance);
            case "equals" -> instance == arguments[0];
            default -> defaultValue(method.getReturnType());
        });
    }

    private static <T> T proxy(Class<T> type, String name) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (instance, method, arguments) -> switch (method.getName()) {
            case "getName" -> name;
            case "hashCode" -> System.identityHashCode(instance);
            case "equals" -> instance == arguments[0];
            default -> defaultValue(method.getReturnType());
        }));
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == char.class) {
            return (char) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0.0f;
        }
        if (type == double.class) {
            return 0.0d;
        }
        return null;
    }

    @Test
    public void nullContextFindsNoProfileAndIsNotCounted() throws IOException {
        ParsedIprofProfile parsed = parse("{\"ctx\":\"1001:9\",\"records\":[20,0,10,53,1,1]}");
        IprofProfilesLookup lookup = buildLookup(parsed, Set.of(BAR_DESC));

        Assert.assertEquals(0, lookup.hitCount());
        Assert.assertEquals(0, lookup.missCount());

        /* A node without a source position has no context: no profile, no exception, no count. */
        Assert.assertTrue(lookup.getConditionalProfile(null).isEmpty());
        Assert.assertEquals(0, lookup.hitCount());
        Assert.assertEquals(0, lookup.missCount());
    }
}
