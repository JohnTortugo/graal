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
import java.lang.reflect.Proxy;
import java.util.Set;

import org.junit.Assert;
import org.junit.Test;

import com.oracle.svm.hosted.pgo.IprofConditionalParser;
import com.oracle.svm.hosted.pgo.IprofConditionalParser.MethodDescriptor;
import com.oracle.svm.hosted.pgo.IprofConditionalParser.ParsedProfile;
import com.oracle.svm.hosted.pgo.profiles.ConditionalProfileSiteDescriptor.Stage;

import jdk.vm.ci.code.BytecodePosition;
import jdk.vm.ci.meta.JavaType;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import jdk.vm.ci.meta.ResolvedJavaType;
import jdk.vm.ci.meta.Signature;

/**
 * Tests {@link ConditionalProfileContextResolver}'s signature canonicalization and the
 * universe-independent table build (context resolution, unresolved-context dropping, and
 * duplicate-context merge behavior).
 */
public class ConditionalProfileContextResolverTest {

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
                    "{\"id\":22263,\"name\":\"bar\",\"signature\":[100,8,4]}," +
                    "{\"id\":22269,\"name\":\"baz\",\"signature\":[100,101,4]}" +
                    "],";

    private static ParsedProfile parse(String conditionalProfiles) throws IOException {
        String json = "{\"version\":\"1.0.0\"," + TYPES_AND_METHODS + "\"conditionalProfiles\":[" + conditionalProfiles + "]}";
        return new IprofConditionalParser().parse(new StringReader(json));
    }

    private static ParsedProfile parsePrecise(String preciseProfiles) throws IOException {
        String json = "{\"version\":\"1.1.0\"," + TYPES_AND_METHODS + "\"conditionalProfiles\":[],\"ceConditionalProfilesV2\":[" + preciseProfiles + "]}";
        return new IprofConditionalParser().parse(new StringReader(json));
    }

    // --- signature canonicalization -------------------------------------------------------------

    @Test
    public void convertsIprofTypeNamesToDescriptors() {
        Assert.assertEquals("I", ConditionalProfileContextResolver.toDescriptor("int"));
        Assert.assertEquals("Z", ConditionalProfileContextResolver.toDescriptor("boolean"));
        Assert.assertEquals("V", ConditionalProfileContextResolver.toDescriptor("void"));
        Assert.assertEquals("Ljava/lang/String;", ConditionalProfileContextResolver.toDescriptor("java.lang.String"));
        Assert.assertEquals("[Z", ConditionalProfileContextResolver.toDescriptor("[Z"));
        Assert.assertEquals("[Ljava/lang/String;", ConditionalProfileContextResolver.toDescriptor("[Ljava.lang.String;"));
    }

    @Test
    public void buildsCanonicalMethodDescriptor() throws IOException {
        ParsedProfile parsed = parse("{\"ctx\":\"22263:9\",\"records\":[20,0,10,53,1,1]}");
        MethodDescriptor bar = parsed.methodsById().get(22263);
        MethodDescriptor baz = parsed.methodsById().get(22269);
        Assert.assertEquals(BAR_DESC, ConditionalProfileContextResolver.descriptorForProfileMethod(bar, parsed.typeNamesById()));
        Assert.assertEquals(BAZ_DESC, ConditionalProfileContextResolver.descriptorForProfileMethod(baz, parsed.typeNamesById()));
    }

    // --- table build: context resolution --------------------------------------------------------

    @Test
    public void resolvesSingleFrameContextWhenMethodPresent() throws IOException {
        ParsedProfile parsed = parse("{\"ctx\":\"22263:9\",\"records\":[20,0,10,53,1,1]}");
        SimpleConditionalProfilesLookup lookup = ConditionalProfileContextResolver.buildLookup(parsed, Set.of(BAR_DESC));

        Assert.assertTrue(lookup.profileCategoryRecorded("conditionalProfiles"));
        ConditionalProfileDiagnostics d = lookup.diagnostics();
        Assert.assertEquals(1, d.totalEntries());
        Assert.assertEquals(1, d.resolvedEntries());
        Assert.assertEquals(1, d.singleFrameEntries());
        Assert.assertEquals(0, d.inlinedContextEntries());
        Assert.assertEquals(0, d.unresolvedEntries());
    }

    @Test
    public void resolvesInlinedContextWhenAllFramesPresent() throws IOException {
        ParsedProfile parsed = parse("{\"ctx\":\"22263:9<22269:1\",\"records\":[20,0,10,53,1,1]}");
        SimpleConditionalProfilesLookup lookup = ConditionalProfileContextResolver.buildLookup(parsed, Set.of(BAR_DESC, BAZ_DESC));

        ConditionalProfileDiagnostics d = lookup.diagnostics();
        Assert.assertEquals(1, d.resolvedEntries());
        Assert.assertEquals(1, d.inlinedContextEntries());
        Assert.assertEquals(0, d.singleFrameEntries());
    }

    @Test
    public void dropsEntryWhenAnyContextFrameMethodAbsent() throws IOException {
        // Inlined context needs both bar and baz; only bar is present -> entry dropped.
        ParsedProfile parsed = parse("{\"ctx\":\"22263:9<22269:1\",\"records\":[20,0,10,53,1,1]}");
        SimpleConditionalProfilesLookup lookup = ConditionalProfileContextResolver.buildLookup(parsed, Set.of(BAR_DESC));

        ConditionalProfileDiagnostics d = lookup.diagnostics();
        Assert.assertEquals(0, d.resolvedEntries());
        Assert.assertEquals(1, d.unresolvedEntries());
        Assert.assertFalse(lookup.profileCategoryRecorded("conditionalProfiles"));
    }

    // --- table build: duplicate-context merge (first-wins) --------------------------------------

    @Test
    public void mergesDuplicateContextFirstWins() throws IOException {
        ParsedProfile parsed = parse(
                        "{\"ctx\":\"22263:9\",\"records\":[20,0,10,53,1,1]}," +
                                        "{\"ctx\":\"22263:9\",\"records\":[20,0,1,53,1,10]}");
        SimpleConditionalProfilesLookup lookup = ConditionalProfileContextResolver.buildLookup(parsed, Set.of(BAR_DESC));

        ConditionalProfileDiagnostics d = lookup.diagnostics();
        Assert.assertEquals(2, d.totalEntries());
        Assert.assertEquals(1, d.resolvedEntries());
        Assert.assertEquals(1, d.duplicateContextEntries());
    }

    // --- opposite-branch profiles remain distinct across distinct contexts ----------------------

    @Test
    public void oppositeBranchProfilesForDistinctContextsBothResolve() throws IOException {
        // Same method, different bci in the leaf frame -> distinct contexts, both retained.
        ParsedProfile parsed = parse(
                        "{\"ctx\":\"22263:9\",\"records\":[20,0,10,53,1,1]}," +
                                        "{\"ctx\":\"22263:42\",\"records\":[20,0,1,53,1,10]}");
        SimpleConditionalProfilesLookup lookup = ConditionalProfileContextResolver.buildLookup(parsed, Set.of(BAR_DESC));

        ConditionalProfileDiagnostics d = lookup.diagnostics();
        Assert.assertEquals(2, d.resolvedEntries());
        Assert.assertEquals(0, d.duplicateContextEntries());
    }

    @Test
    public void clearDisablesLookup() throws IOException {
        ParsedProfile parsed = parse("{\"ctx\":\"22263:9\",\"records\":[20,0,10,53,1,1]}");
        SimpleConditionalProfilesLookup lookup = ConditionalProfileContextResolver.buildLookup(parsed, Set.of(BAR_DESC));
        Assert.assertTrue(lookup.profileCategoryRecorded("conditionalProfiles"));
        lookup.clear();
        Assert.assertFalse(lookup.profileCategoryRecorded("conditionalProfiles"));
        Assert.assertTrue(lookup.getConditionalProfile(null).isEmpty());
    }

    @Test
    public void onlyConditionalCategoryRecorded() throws IOException {
        ParsedProfile parsed = parse("{\"ctx\":\"22263:9\",\"records\":[20,0,10,53,1,1]}");
        SimpleConditionalProfilesLookup lookup = ConditionalProfileContextResolver.buildLookup(parsed, Set.of(BAR_DESC));
        Assert.assertTrue(lookup.profileCategoryRecorded("conditionalProfiles"));
        Assert.assertFalse(lookup.profileCategoryRecorded("callCountProfiles"));
        Assert.assertFalse(lookup.profileCategoryRecorded("virtualInvokeProfiles"));
        Assert.assertFalse(lookup.profileCategoryRecorded("instanceOfProfiles"));
        Assert.assertFalse(lookup.profileCategoryRecorded("monitorProfiles"));
        Assert.assertFalse(lookup.profileCategoryRecorded("samplingProfiles"));
    }

    // --- toDescriptor rejects empty/invalid type names cleanly ----------------------------------

    @Test
    public void toDescriptorRejectsEmptyNameCleanly() {
        // Must be a clean IllegalArgumentException, not a StringIndexOutOfBoundsException from charAt.
        try {
            ConditionalProfileContextResolver.toDescriptor("");
            Assert.fail("expected IllegalArgumentException for empty type name");
        } catch (IllegalArgumentException e) {
            Assert.assertTrue("message should mention empty type name", e.getMessage().contains("empty"));
        }
    }

    @Test
    public void toDescriptorRejectsNullNameCleanly() {
        try {
            ConditionalProfileContextResolver.toDescriptor(null);
            Assert.fail("expected IllegalArgumentException for null type name");
        } catch (IllegalArgumentException e) {
            // expected
        }
    }

    // --- empty / no-applicable-profile behavior -------------------------------------------------

    @Test
    public void emptyProfileYieldsNoRecordedCategory() throws IOException {
        // conditionalProfiles present but empty -> nothing resolves -> category not recorded.
        ParsedProfile parsed = parse("");
        SimpleConditionalProfilesLookup lookup = ConditionalProfileContextResolver.buildLookup(parsed, Set.of(BAR_DESC));
        Assert.assertEquals(0, lookup.diagnostics().totalEntries());
        Assert.assertEquals(0, lookup.diagnostics().resolvedEntries());
        Assert.assertFalse(lookup.profileCategoryRecorded("conditionalProfiles"));
    }

    // --- applied hit/miss counters --------------------------------------------------------------

    @Test
    public void ambiguousLegacyContextDoesNotFallback() throws IOException {
        String legacy = "{\"ctx\":\"22263:9\",\"records\":[20,0,10,53,1,1]}";
        String first = "{\"stage\":\"POST_HIGH_TIER\",\"ctx\":\"22263:9\",\"successors\":[20,53]," +
                        "\"conditionKind\":\"IntegerEqualsNode\",\"conditionFingerprint\":\"2a\",\"occurrence\":0,\"records\":[20,0,10,53,1,1]}";
        String second = "{\"stage\":\"POST_HIGH_TIER\",\"ctx\":\"22263:9\",\"successors\":[20,53]," +
                        "\"conditionKind\":\"IntegerEqualsNode\",\"conditionFingerprint\":\"2a\",\"occurrence\":1,\"records\":[20,0,1,53,1,10]}";
        String json = "{\"version\":\"1.1.0\"," + TYPES_AND_METHODS + "\"conditionalProfiles\":[" + legacy + "],\"ceConditionalProfilesV2\":[" + first + ',' + second + "]}";
        SimpleConditionalProfilesLookup lookup = ConditionalProfileContextResolver.buildLookup(
                        new IprofConditionalParser().parse(new StringReader(json)), Set.of(BAR_DESC));
        BytecodePosition context = new BytecodePosition(null, mockBarMethod(), 9);
        ConditionalProfileSiteDescriptor earlySite = new ConditionalProfileSiteDescriptor(Stage.ROOT_PRE_INLINE, java.util.List.of(20, 53), "IntegerEqualsNode", 42L, 0);

        Assert.assertTrue(lookup.getConditionalProfile(context, earlySite).isEmpty());
        Assert.assertEquals(0, lookup.preciseMissDiagnostics().unambiguousFallback());
    }

    @Test
    public void exactPreciseSiteOverridesContradictoryLegacyData() throws IOException {
        String legacy = "{\"ctx\":\"22263:9\",\"records\":[20,0,1,53,1,10]}";
        String precise = "{\"stage\":\"ROOT_PRE_INLINE\",\"ctx\":\"22263:9\",\"successors\":[20,53]," +
                        "\"conditionKind\":\"IntegerEqualsNode\",\"conditionFingerprint\":\"2a\",\"occurrence\":0,\"records\":[20,0,10,53,1,1]}";
        String json = "{\"version\":\"1.1.0\"," + TYPES_AND_METHODS + "\"conditionalProfiles\":[" + legacy + "],\"ceConditionalProfilesV2\":[" + precise + "]}";
        SimpleConditionalProfilesLookup lookup = ConditionalProfileContextResolver.buildLookup(
                        new IprofConditionalParser().parse(new StringReader(json)), Set.of(BAR_DESC));
        BytecodePosition context = new BytecodePosition(null, mockBarMethod(), 9);
        ConditionalProfileSiteDescriptor exact = new ConditionalProfileSiteDescriptor(Stage.ROOT_PRE_INLINE, java.util.List.of(20, 53), "IntegerEqualsNode", 42L, 0);

        long[] records = lookup.getConditionalProfile(context, exact).orElseThrow().value();
        Assert.assertArrayEquals(new long[]{20, 0, 10, 53, 1, 1}, records);
        Assert.assertEquals(0, lookup.preciseMissDiagnostics().unambiguousFallback());
    }

    @Test
    public void preciseLookupUsesOnlyUnambiguousLegacyFallbackAcrossStages() throws IOException {
        String legacy = "{\"ctx\":\"22263:9\",\"records\":[20,0,10,53,1,1]}";
        String precise = "{\"stage\":\"POST_HIGH_TIER\",\"ctx\":\"22263:9\",\"successors\":[20,53]," +
                        "\"conditionKind\":\"IntegerEqualsNode\",\"conditionFingerprint\":\"2a\",\"occurrence\":0,\"records\":[20,0,10,53,1,1]}";
        String json = "{\"version\":\"1.1.0\"," + TYPES_AND_METHODS + "\"conditionalProfiles\":[" + legacy + "],\"ceConditionalProfilesV2\":[" + precise + "]}";
        ParsedProfile parsed = new IprofConditionalParser().parse(new StringReader(json));
        SimpleConditionalProfilesLookup lookup = ConditionalProfileContextResolver.buildLookup(parsed, Set.of(BAR_DESC));
        BytecodePosition context = new BytecodePosition(null, mockBarMethod(), 9);
        ConditionalProfileSiteDescriptor earlySite = new ConditionalProfileSiteDescriptor(Stage.ROOT_PRE_INLINE, java.util.List.of(20, 53), "IntegerEqualsNode", 42L, 0);

        Assert.assertTrue(lookup.getConditionalProfile(context, earlySite).isPresent());
        Assert.assertEquals(1, lookup.preciseMissDiagnostics().unambiguousFallback());
    }

    @Test
    public void preciseLookupRequiresExactStageAndSiteIdentity() throws IOException {
        ParsedProfile parsed = parsePrecise("{\"stage\":\"ROOT_PRE_INLINE\",\"ctx\":\"22263:9\",\"successors\":[20,53]," +
                        "\"conditionKind\":\"IntegerEqualsNode\",\"conditionFingerprint\":\"2a\",\"occurrence\":0,\"records\":[20,0,10,53,1,1]}");
        SimpleConditionalProfilesLookup lookup = ConditionalProfileContextResolver.buildLookup(parsed, Set.of(BAR_DESC));
        BytecodePosition context = new BytecodePosition(null, mockBarMethod(), 9);
        ConditionalProfileSiteDescriptor exact = new ConditionalProfileSiteDescriptor(Stage.ROOT_PRE_INLINE, java.util.List.of(20, 53), "IntegerEqualsNode", 42L, 0);
        ConditionalProfileSiteDescriptor wrongFingerprint = new ConditionalProfileSiteDescriptor(Stage.ROOT_PRE_INLINE, java.util.List.of(20, 53), "IntegerEqualsNode", 99L, 0);
        ConditionalProfileSiteDescriptor wrongOccurrence = new ConditionalProfileSiteDescriptor(Stage.ROOT_PRE_INLINE, java.util.List.of(20, 53), "IntegerEqualsNode", 42L, 1);
        ConditionalProfileSiteDescriptor wrongStage = new ConditionalProfileSiteDescriptor(Stage.POST_HIGH_TIER, java.util.List.of(20, 53), "IntegerEqualsNode", 42L, 0);

        Assert.assertEquals(1, lookup.availableContextCount());
        Assert.assertTrue(lookup.getConditionalProfile(context, exact).isPresent());
        Assert.assertTrue(lookup.getConditionalProfile(context, wrongFingerprint).isPresent());
        Assert.assertTrue(lookup.getConditionalProfile(context, wrongOccurrence).isEmpty());
        Assert.assertTrue(lookup.getConditionalProfile(context, wrongStage).isEmpty());
        Assert.assertEquals(2, lookup.hitCount());
        Assert.assertEquals(2, lookup.missCount());
        Assert.assertEquals(1, lookup.preciseMissDiagnostics().fingerprintDrift());
    }

    @Test
    public void tracksDistinctSuccessorApplicationCoverage() throws IOException {
        ParsedProfile parsed = parse(
                        "{\"ctx\":\"22263:9\",\"records\":[20,0,10,53,1,1]}," +
                                        "{\"ctx\":\"22263:42\",\"records\":[20,0,1,53,1,10]}");
        SimpleConditionalProfilesLookup lookup = ConditionalProfileContextResolver.buildLookup(parsed, Set.of(BAR_DESC));
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
        Signature signature = (Signature) Proxy.newProxyInstance(Signature.class.getClassLoader(), new Class<?>[]{Signature.class}, (_, method, _) -> switch (method.getName()) {
            case "getParameterCount" -> 1;
            case "getParameterType" -> intType;
            case "getReturnType" -> voidType;
            default -> defaultValue(method.getReturnType());
        });
        return (ResolvedJavaMethod) Proxy.newProxyInstance(ResolvedJavaMethod.class.getClassLoader(), new Class<?>[]{ResolvedJavaMethod.class}, (instance, method, arguments) -> switch (method.getName()) {
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
    public void missCounterIncrementsWhenNoContextMatches() throws IOException {
        ParsedProfile parsed = parse("{\"ctx\":\"22263:9\",\"records\":[20,0,10,53,1,1]}");
        SimpleConditionalProfilesLookup lookup = ConditionalProfileContextResolver.buildLookup(parsed, Set.of(BAR_DESC));

        Assert.assertEquals(0, lookup.hitCount());
        Assert.assertEquals(0, lookup.missCount());

        // A null context is a miss (and must not throw).
        Assert.assertTrue(lookup.getConditionalProfile(null).isEmpty());
        // getConditionalProfile with null short-circuits before counting, so counters stay at 0.
        Assert.assertEquals(0, lookup.hitCount());
        Assert.assertEquals(0, lookup.missCount());
    }
}
