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
import com.oracle.svm.hosted.pgo.IprofParser.ProfileMethod;
import com.oracle.svm.hosted.pgo.IprofParser.ParsedIprofProfile;
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

    /** The type descriptor of {@code com.example.Foo} from {@link #TYPES_AND_METHODS}. */
    private static final String FOO = "Lcom/example/Foo;";
    /** The method descriptor of {@code com.example.Foo.bar(int)void}, method id 1001. */
    static final String BAR_DESC = FOO + ".bar(I)V";
    /** The method descriptor of {@code com.example.Foo.baz(int)String}, method id 1002. */
    private static final String BAZ_DESC = FOO + ".baz(I)Ljava/lang/String;";
    /**
     * The {@code types} and {@code methods} sections that the test profiles share. It ends with a
     * comma so that another section can follow.
     */
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
     *
     * @param profile the parsed profile
     * @param presentDescriptors the descriptors of the methods that are in the image
     * @return the lookup built from {@code profile}
     */
    static IprofProfilesLookup buildLookup(ParsedIprofProfile profile, Set<String> presentDescriptors) {
        return buildLookup(profile, presentDescriptors, Map.of());
    }

    /**
     * Like {@link #buildLookup(ParsedIprofProfile, Set)}, and also resolves the receiver types of
     * {@code virtualInvokeProfiles} by their descriptors.
     *
     * @param profile the parsed profile
     * @param presentDescriptors the descriptors of the methods that are in the image
     * @param typesByDescriptor the image types that receiver types resolve to, by type descriptor
     * @return the lookup built from {@code profile}
     */
    static IprofProfilesLookup buildLookup(ParsedIprofProfile profile, Set<String> presentDescriptors, Map<String, AnalysisType> typesByDescriptor) {
        Map<String, HostedMethod> methods = new HashMap<>();
        for (String descriptor : presentDescriptors) {
            methods.put(descriptor, null);
        }
        return IprofProfileResolver.buildLookup(profile, methods, typesByDescriptor, null);
    }

    /**
     * A lookup that holds only the given receiver profiles.
     *
     * @param receivers the receiver counts of every indirect-invoke context
     * @return the lookup
     */
    static IprofProfilesLookup receiverLookup(Map<PGOContext, Map<AnalysisType, Long>> receivers) {
        return new IprofProfilesLookup(Map.of(), Map.of(), receivers, Map.of(), Map.of(), Map.of(), null);
    }

    /**
     * A lookup that holds only the given stack samples.
     *
     * @param samples the sample count of every stack
     * @return the lookup
     */
    static IprofProfilesLookup samplingLookup(Map<NodeSourcePosition, Long> samples) {
        return new IprofProfilesLookup(Map.of(), Map.of(), Map.of(), samples, Map.of(), Map.of(), null);
    }

    /**
     * Parses a version 1.0.0 profile whose {@code conditionalProfiles} section holds the entries
     * {@code conditionalProfiles}.
     */
    private static ParsedIprofProfile parseConditionalProfiles(String conditionalProfiles) throws IOException {
        String json = "{\"version\":\"1.0.0\"," + TYPES_AND_METHODS + "\"conditionalProfiles\":[" + conditionalProfiles + "]}";
        return new IprofParser().parse(new StringReader(json));
    }

    // --- signature canonicalization -------------------------------------------------------------

    @Test
    public void convertsIprofTypeNamesInMethodDescriptors() {
        Map<Integer, String> typeNames = Map.of(1, "com.example.Foo$$Lambda/0x1234", 2, "[Ljava.lang.String;", 3, "boolean", 4, "[Z", 5, "void", 6, "int");
        Assert.assertEquals("Lcom/example/Foo$$Lambda.0x1234;.m([Ljava/lang/String;Z[ZI)V",
                        IprofProfileResolver.descriptorForProfileMethod(new ProfileMethod(7, "m", 1, 5, new int[]{2, 3, 4, 6}), typeNames));
        Assert.assertNull("a type id that the types table lacks", IprofProfileResolver.descriptorForProfileMethod(new ProfileMethod(8, "m", 1, 5, new int[]{9}), typeNames));
    }

    @Test
    public void buildsCanonicalMethodDescriptor() throws IOException {
        ParsedIprofProfile parsed = parseConditionalProfiles("{\"ctx\":\"1001:9\",\"records\":[20,0,10,53,1,1]}");
        ProfileMethod bar = parsed.methodsById().get(1001);
        ProfileMethod baz = parsed.methodsById().get(1002);
        Assert.assertEquals(BAR_DESC, IprofProfileResolver.descriptorForProfileMethod(bar, parsed.typeNamesById()));
        Assert.assertEquals(BAZ_DESC, IprofProfileResolver.descriptorForProfileMethod(baz, parsed.typeNamesById()));
    }

    /* Stack-sample frames named by stack-trace name are matched to image methods by class and signature first. */
    @Test
    public void removesTheMethodNameFromADescriptor() {
        Assert.assertEquals("Ljava/lang/Object;(J)V", IprofProfileResolver.withoutMethodName("Ljava/lang/Object;.waitSubst(J)V"));
        Assert.assertEquals("Lcom/example/Foo$$Lambda.0x1234;()V", IprofProfileResolver.withoutMethodName("Lcom/example/Foo$$Lambda.0x1234;.run()V"));
        /* A descriptor without a class name of the form L...; is kept whole. */
        Assert.assertEquals("[I.clone()Ljava/lang/Object;", IprofProfileResolver.withoutMethodName("[I.clone()Ljava/lang/Object;"));
    }

    // --- table build: context resolution --------------------------------------------------------

    @Test
    public void resolvesSingleFrameConditionalContextWhenMethodPresent() throws IOException {
        ParsedIprofProfile parsed = parseConditionalProfiles("{\"ctx\":\"1001:9\",\"records\":[20,0,10,53,1,1]}");
        IprofProfilesLookup lookup = buildLookup(parsed, Set.of(BAR_DESC));

        Assert.assertTrue(lookup.profileCategoryRecorded("conditionalProfiles"));
        PGODiagnostics.ConditionalDiagnostics conditionalDiagnostics = lookup.diagnostics().conditional();
        Assert.assertEquals(1, conditionalDiagnostics.totalEntries());
        Assert.assertEquals(1, conditionalDiagnostics.resolvedEntries());
        Assert.assertEquals(1, conditionalDiagnostics.singleFrameEntries());
        Assert.assertEquals(0, conditionalDiagnostics.inlinedContextEntries());
        Assert.assertEquals(0, conditionalDiagnostics.unresolvedEntries());
    }

    @Test
    public void resolvesInlinedConditionalContextWhenAllFramesPresent() throws IOException {
        ParsedIprofProfile parsed = parseConditionalProfiles("{\"ctx\":\"1001:9<1002:1\",\"records\":[20,0,10,53,1,1]}");
        IprofProfilesLookup lookup = buildLookup(parsed, Set.of(BAR_DESC, BAZ_DESC));

        PGODiagnostics.ConditionalDiagnostics conditionalDiagnostics = lookup.diagnostics().conditional();
        Assert.assertEquals(1, conditionalDiagnostics.resolvedEntries());
        Assert.assertEquals(1, conditionalDiagnostics.inlinedContextEntries());
        Assert.assertEquals(0, conditionalDiagnostics.singleFrameEntries());
    }

    @Test
    public void dropsConditionalEntryWhenAnyContextFrameMethodAbsent() throws IOException {
        // Inlined context needs both bar and baz; only bar is present -> entry dropped.
        ParsedIprofProfile parsed = parseConditionalProfiles("{\"ctx\":\"1001:9<1002:1\",\"records\":[20,0,10,53,1,1]}");
        IprofProfilesLookup lookup = buildLookup(parsed, Set.of(BAR_DESC));

        PGODiagnostics.ConditionalDiagnostics conditionalDiagnostics = lookup.diagnostics().conditional();
        Assert.assertEquals(0, conditionalDiagnostics.resolvedEntries());
        Assert.assertEquals(1, conditionalDiagnostics.unresolvedEntries());
        Assert.assertFalse(lookup.profileCategoryRecorded("conditionalProfiles"));
    }

    @Test
    public void listsDroppedEntriesWithTheFramesTheImageLacks() throws IOException {
        ParsedIprofProfile parsed = parseConditionalProfiles("{\"ctx\":\"1001:9<1002:1\",\"records\":[20,0,10,53,1,1]},{\"ctx\":\"1001:12\",\"records\":[20,0,10,53,1,1]}");
        Map<String, HostedMethod> methods = new HashMap<>();
        methods.put(BAR_DESC, null);
        List<String> dropped = new ArrayList<>();
        IprofProfileResolver.buildLookup(parsed, methods, Map.of(), dropped);

        /* Only the entry with the absent frame is dropped; the absent frame is marked, the present one is not. */
        Assert.assertEquals(List.of("branch or switch: " + BAR_DESC + ":9 < " + BAZ_DESC + ":1 (not in the image)"), dropped);
    }

    // --- table build: duplicate-context merge (first-wins) --------------------------------------

    @Test
    public void keepsFirstOfDuplicateConditionalContexts() throws IOException {
        ParsedIprofProfile parsed = parseConditionalProfiles(
                        "{\"ctx\":\"1001:9\",\"records\":[20,0,10,53,1,1]}," +
                                        "{\"ctx\":\"1001:9\",\"records\":[20,0,1,53,1,10]}");
        IprofProfilesLookup lookup = buildLookup(parsed, Set.of(BAR_DESC));

        PGODiagnostics.ConditionalDiagnostics conditionalDiagnostics = lookup.diagnostics().conditional();
        Assert.assertEquals(2, conditionalDiagnostics.totalEntries());
        Assert.assertEquals(1, conditionalDiagnostics.resolvedEntries());
        Assert.assertEquals(1, conditionalDiagnostics.duplicateContextEntries());
    }

    // --- opposite-branch profiles remain distinct across distinct contexts ----------------------

    @Test
    public void oppositeBranchProfilesForDistinctContextsBothResolve() throws IOException {
        // Same method, different bci in the leaf frame -> distinct contexts, both retained.
        ParsedIprofProfile parsed = parseConditionalProfiles(
                        "{\"ctx\":\"1001:9\",\"records\":[20,0,10,53,1,1]}," +
                                        "{\"ctx\":\"1001:42\",\"records\":[20,0,1,53,1,10]}");
        IprofProfilesLookup lookup = buildLookup(parsed, Set.of(BAR_DESC));

        PGODiagnostics.ConditionalDiagnostics conditionalDiagnostics = lookup.diagnostics().conditional();
        Assert.assertEquals(2, conditionalDiagnostics.resolvedEntries());
        Assert.assertEquals(0, conditionalDiagnostics.duplicateContextEntries());
    }

    /* clear() drops the tables: the lookup then answers as for an absent profile, and the diagnostics stay. */
    @Test
    public void clearDropsTheTables() throws IOException {
        ParsedIprofProfile parsed = parseConditionalProfiles("{\"ctx\":\"1001:9\",\"records\":[20,0,10,53,1,1]}");
        IprofProfilesLookup lookup = buildLookup(parsed, Set.of(BAR_DESC));
        BytecodePosition context = new BytecodePosition(null, mockBarMethod(), 9);
        Assert.assertTrue(lookup.profileCategoryRecorded("conditionalProfiles"));
        Assert.assertTrue(lookup.getConditionalProfile(context).isPresent());
        lookup.clear();
        Assert.assertFalse(lookup.profileCategoryRecorded("conditionalProfiles"));
        Assert.assertTrue(lookup.getConditionalProfile(context).isEmpty());
        Assert.assertEquals(0, lookup.availableConditionalContextCount());
        Assert.assertEquals(0, lookup.unusedConditionalContextCount());
        Assert.assertEquals(1, lookup.diagnostics().conditional().resolvedEntries());
    }

    /** The fixture has only a conditionalProfiles section, so no other category is recorded. */
    @Test
    public void sectionsAbsentFromTheProfileAreNotRecorded() throws IOException {
        ParsedIprofProfile parsed = parseConditionalProfiles("{\"ctx\":\"1001:9\",\"records\":[20,0,10,53,1,1]}");
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
        ParsedIprofProfile parsed = parseConditionalProfiles("");
        IprofProfilesLookup lookup = buildLookup(parsed, Set.of(BAR_DESC));
        Assert.assertEquals(0, lookup.diagnostics().conditional().totalEntries());
        Assert.assertEquals(0, lookup.diagnostics().conditional().resolvedEntries());
        Assert.assertFalse(lookup.profileCategoryRecorded("conditionalProfiles"));
    }

    // --- applied hit/miss counters --------------------------------------------------------------

    /* A query for a context the profile has returns that entry as is, without any fallback. */
    @Test
    public void exactConditionalContextReturnsItsEntry() throws IOException {
        ParsedIprofProfile parsed = parseConditionalProfiles("{\"ctx\":\"1001:9\",\"records\":[20,0,11,53,1,11]}");
        IprofProfilesLookup lookup = buildLookup(parsed, Set.of(BAR_DESC));
        BytecodePosition context = new BytecodePosition(null, mockBarMethod(), 9);

        Optional<ProfiledValue<long[]>> result = lookup.getConditionalProfile(context);
        Assert.assertTrue(result.isPresent());
        Assert.assertArrayEquals(new long[]{20, 0, 11, 53, 1, 11}, result.get().value());
        Assert.assertEquals(1, lookup.conditionalHits());
        Assert.assertEquals(0, lookup.conditionalContextFallbackHits());
    }

    /* A branch queried under an inlining chain the profile never saw falls back to the branch's shorter-context entry. */
    @Test
    public void unseenConditionalContextFallsBackToShorterContext() throws IOException {
        IprofProfilesLookup lookup = buildLookup(parseConditionalProfiles("{\"ctx\":\"1001:9\",\"records\":[20,0,7,53,1,3]}"), Set.of(BAR_DESC));
        BytecodePosition caller = new BytecodePosition(null, mockBarMethod(), 40);
        BytecodePosition inlined = new BytecodePosition(caller, mockBarMethod(), 9);

        Optional<ProfiledValue<long[]>> result = lookup.getConditionalProfile(inlined);
        Assert.assertTrue(result.isPresent());
        Assert.assertArrayEquals(new long[]{20, 0, 7, 53, 1, 3}, result.get().value());
        Assert.assertEquals(1, lookup.conditionalContextFallbackHits());
        Assert.assertEquals(1, lookup.conditionalContextFallbackDroppedFrames());

        lookup.setConditionalContextFallback(false);
        Assert.assertTrue(lookup.getConditionalProfile(inlined).isEmpty());
    }

    /* A missing chain falls back to the sum over every profiled chain that shares its longest prefix. */
    @Test
    public void unseenConditionalContextUsesSumOfLongestMatchingPrefix() throws IOException {
        String viaA = "{\"ctx\":\"1001:9<1001:40\",\"records\":[20,0,7,53,1,3]}";
        String viaB = "{\"ctx\":\"1001:9<1001:50\",\"records\":[20,0,1,53,1,9]}";
        IprofProfilesLookup lookup = buildLookup(parseConditionalProfiles(viaA + "," + viaB), Set.of(BAR_DESC));

        BytecodePosition newCaller = new BytecodePosition(new BytecodePosition(null, mockBarMethod(), 60), mockBarMethod(), 9);
        long[] summed = lookup.getConditionalProfile(newCaller).orElseThrow().value();
        Assert.assertArrayEquals(new long[]{20, 0, 8, 53, 1, 12}, summed);

        BytecodePosition deeperViaA = new BytecodePosition(new BytecodePosition(new BytecodePosition(null, mockBarMethod(), 70), mockBarMethod(), 40), mockBarMethod(), 9);
        long[] viaAOnly = lookup.getConditionalProfile(deeperViaA).orElseThrow().value();
        Assert.assertArrayEquals(new long[]{20, 0, 7, 53, 1, 3}, viaAOnly);
        Assert.assertEquals(2, lookup.conditionalContextFallbackHits());
        Assert.assertEquals(2, lookup.conditionalContextFallbackDroppedFrames());
    }

    /**
     * Of two profiled contexts, one is queried and one is unused. The queried one then counts as
     * unapplied, partly applied or fully applied as the phase reports 0, 1 or 2 of its 2 successors
     * applied; the latest report wins.
     */
    @Test
    public void tracksConditionalSuccessorCoverage() throws IOException {
        ParsedIprofProfile parsed = parseConditionalProfiles(
                        "{\"ctx\":\"1001:9\",\"records\":[20,0,10,53,1,1]}," +
                                        "{\"ctx\":\"1001:42\",\"records\":[20,0,1,53,1,10]}");
        IprofProfilesLookup lookup = buildLookup(parsed, Set.of(BAR_DESC));
        BytecodePosition queried = new BytecodePosition(null, mockBarMethod(), 9);

        Assert.assertTrue(lookup.getConditionalProfile(queried).isPresent());
        Assert.assertEquals(1, lookup.matchedConditionalContextCount());
        Assert.assertEquals(1, lookup.unusedConditionalContextCount());

        lookup.recordConditionalProfileApplication(queried, 2, 0);
        Assert.assertEquals(1, lookup.unappliedMatchedConditionalContextCount());
        lookup.recordConditionalProfileApplication(queried, 2, 1);
        Assert.assertEquals(1, lookup.partiallyAppliedConditionalContextCount());
        lookup.recordConditionalProfileApplication(queried, 2, 2);
        Assert.assertEquals(1, lookup.fullyAppliedConditionalContextCount());
        Assert.assertEquals(0, lookup.partiallyAppliedConditionalContextCount());
        Assert.assertEquals(0, lookup.unappliedMatchedConditionalContextCount());
    }

    /**
     * A mock of {@code com.example.Foo.bar(int)void} with just enough behavior to give
     * {@link #BAR_DESC} as its descriptor. Every call returns a new mock, and mocks are equal only
     * to themselves.
     *
     * @return the mock method
     */
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

    /** A mock of interface {@code type} whose {@code getName()} returns {@code name}. */
    private static <T> T proxy(Class<T> type, String name) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (instance, method, arguments) -> switch (method.getName()) {
            case "getName" -> name;
            case "hashCode" -> System.identityHashCode(instance);
            case "equals" -> instance == arguments[0];
            default -> defaultValue(method.getReturnType());
        }));
    }

    /**
     * The value a mock returns for a method with return type {@code type}: zero or {@code false}
     * for a primitive type, {@code null} otherwise.
     */
    private static Object defaultValue(Class<?> type) {
        return Map.of(boolean.class, false, byte.class, (byte) 0, short.class, (short) 0, char.class, (char) 0, int.class, 0, long.class, 0L, float.class, 0.0f, double.class, 0.0d).get(type);
    }

    @Test
    public void nullContextFindsNoConditionalProfileAndIsNotCounted() throws IOException {
        ParsedIprofProfile parsed = parseConditionalProfiles("{\"ctx\":\"1001:9\",\"records\":[20,0,10,53,1,1]}");
        IprofProfilesLookup lookup = buildLookup(parsed, Set.of(BAR_DESC));

        Assert.assertEquals(0, lookup.conditionalHits());
        Assert.assertEquals(0, lookup.conditionalMisses());

        /* A node without a source position has no context: no profile, no exception, no count. */
        Assert.assertTrue(lookup.getConditionalProfile(null).isEmpty());
        Assert.assertEquals(0, lookup.conditionalHits());
        Assert.assertEquals(0, lookup.conditionalMisses());
    }
}
