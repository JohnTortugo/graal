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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.Assert;
import org.junit.Test;

import com.oracle.graal.pointsto.meta.AnalysisType;
import com.oracle.svm.hosted.pgo.IprofConditionalParser;
import com.oracle.svm.hosted.pgo.IprofConditionalParser.IprofFormatException;
import com.oracle.svm.hosted.pgo.IprofConditionalParser.ParsedProfile;
import com.oracle.svm.hosted.pgo.IprofConditionalParser.VirtualInvokeEntry;
import com.oracle.svm.hosted.pgo.profiles.ConditionalProfileContextResolver.VirtualInvokeDiagnostics;
import com.oracle.svm.hosted.pgo.profiles.SimpleConditionalProfilesLookup.FrameKey;

import jdk.vm.ci.code.BytecodePosition;
import jdk.vm.ci.meta.ResolvedJavaMethod;

public class VirtualInvokeProfileResolutionTest {

    private static final String TYPES_AND_METHODS = "\"types\":[" +
                    "{\"id\":4,\"name\":\"int\"}," +
                    "{\"id\":8,\"name\":\"void\"}," +
                    "{\"id\":100,\"name\":\"com.example.Foo\"}," +
                    "{\"id\":101,\"name\":\"java.lang.String\"}," +
                    "{\"id\":102,\"name\":\"java.lang.Integer\"}" +
                    "]," +
                    "\"methods\":[" +
                    "{\"id\":22263,\"name\":\"bar\",\"signature\":[100,8,4]}" +
                    "],\"conditionalProfiles\":[],";

    private static ParsedProfile parse(String virtualInvokeProfiles) throws IOException {
        String json = "{\"version\":\"1.1.0\"," + TYPES_AND_METHODS + "\"virtualInvokeProfiles\":[" + virtualInvokeProfiles + "]}";
        return new IprofConditionalParser().parse(new StringReader(json));
    }

    @Test
    public void parsesReceiverTypePairs() throws IOException {
        ParsedProfile parsed = parse("{\"ctx\":\"22263:9\",\"records\":[101,7,102,3]}");
        Assert.assertEquals(1, parsed.virtualInvokeEntries().size());
        VirtualInvokeEntry entry = parsed.virtualInvokeEntries().getFirst();
        Assert.assertEquals(1, entry.context().size());
        Assert.assertArrayEquals(new long[]{101, 7, 102, 3}, entry.records());
    }

    @Test
    public void sectionIsOptional() throws IOException {
        String json = "{\"version\":\"1.1.0\"," + TYPES_AND_METHODS + "\"ceConditionalProfilesV2\":[]}";
        ParsedProfile parsed = new IprofConditionalParser().parse(new StringReader(json));
        Assert.assertTrue(parsed.virtualInvokeEntries().isEmpty());
    }

    @Test(expected = IprofFormatException.class)
    public void rejectsOddRecordLength() throws IOException {
        parse("{\"ctx\":\"22263:9\",\"records\":[101,7,102]}");
    }

    @Test(expected = IprofFormatException.class)
    public void rejectsUndeclaredReceiverType() throws IOException {
        parse("{\"ctx\":\"22263:9\",\"records\":[999,7]}");
    }

    /*
     * AnalysisType cannot be instantiated in a plain unit test, so resolution is exercised through
     * the drop-accounting path: contexts resolve, but no receiver type is present in the image.
     */
    @Test
    public void accountsForContextsAndDroppedReceivers() throws IOException {
        ParsedProfile parsed = parse("{\"ctx\":\"22263:9\",\"records\":[101,7,102,3]}," +
                        "{\"ctx\":\"22263:9<22263:14\",\"records\":[101,5]}," +
                        "{\"ctx\":\"99999:1\",\"records\":[101,1]}");
        Map<String, AnalysisType> noTypes = Map.of();
        SimpleConditionalProfilesLookup lookup = ConditionalProfileContextResolver.buildLookup(parsed, Set.of(ConditionalProfileContextResolverTest.BAR_DESC), noTypes);

        VirtualInvokeDiagnostics d = lookup.virtualInvokeDiagnostics();
        Assert.assertEquals(3, d.totalEntries());
        Assert.assertEquals(0, d.resolvedEntries());
        Assert.assertEquals(1, d.unresolvedContextEntries());
        Assert.assertEquals(2, d.noKnownReceiverEntries());
        Assert.assertEquals(3, d.receiverRecords());
        Assert.assertEquals(3, d.droppedReceiverRecords());
        Assert.assertEquals(15, d.receiverEvents());
        Assert.assertEquals(15, d.droppedReceiverEvents());
        Assert.assertFalse(lookup.profileCategoryRecorded(SimpleConditionalProfilesLookup.VIRTUAL_INVOKE_PROFILES_CATEGORY));
        Optional<Map<AnalysisType, Long>> result = lookup.getVirtualInvokeProfile(new BytecodePosition(null, ConditionalProfileContextResolverTest.mockBarMethod(), 9));
        Assert.assertTrue(result.isEmpty());
    }

    /**
     * A receiver profile recorded for a call compiled standalone must also serve the same call once
     * the compiled image inlines its method into a caller (deeper query context), when the fallback
     * is enabled; an exact-only lookup misses it.
     */
    @Test
    public void shortenedContextFallbackServesInlinedCopies() {
        ResolvedJavaMethod bar = ConditionalProfileContextResolverTest.mockBarMethod();
        Map<AnalysisType, Long> receivers = Collections.singletonMap(null, 7L);
        Map<List<FrameKey>, Map<AnalysisType, Long>> data = Map.of(List.of(new FrameKey(ConditionalProfileContextResolverTest.BAR_DESC, 9)), receivers);
        BytecodePosition inlinedTwice = new BytecodePosition(new BytecodePosition(new BytecodePosition(null, bar, 30), bar, 14), bar, 9);

        SimpleConditionalProfilesLookup exactOnly = new SimpleConditionalProfilesLookup(Map.of(), Map.of(), null, data, null);
        Assert.assertTrue(exactOnly.getVirtualInvokeProfile(inlinedTwice).isEmpty());
        Assert.assertEquals(1, exactOnly.virtualInvokeMissCount());
        Assert.assertEquals(0, exactOnly.virtualInvokeFallbackCount());

        SimpleConditionalProfilesLookup withFallback = new SimpleConditionalProfilesLookup(Map.of(), Map.of(), null, data, null);
        withFallback.setReceiverContextFallback(true);
        Assert.assertSame(receivers, withFallback.getVirtualInvokeProfile(inlinedTwice).orElseThrow());
        Assert.assertEquals(1, withFallback.virtualInvokeHitCount());
        Assert.assertEquals(1, withFallback.virtualInvokeFallbackCount());
        Assert.assertEquals(2, withFallback.virtualInvokeFallbackDroppedFrames());
        Assert.assertEquals(1, withFallback.matchedVirtualInvokeContextCount());
        /* An exact match does not count as a fallback. */
        Assert.assertSame(receivers, withFallback.getVirtualInvokeProfile(new BytecodePosition(null, bar, 9)).orElseThrow());
        Assert.assertEquals(1, withFallback.virtualInvokeFallbackCount());
        /* A different call site in the same method is never served by the fallback. */
        Assert.assertTrue(withFallback.getVirtualInvokeProfile(new BytecodePosition(new BytecodePosition(null, bar, 30), bar, 10)).isEmpty());
    }
}
