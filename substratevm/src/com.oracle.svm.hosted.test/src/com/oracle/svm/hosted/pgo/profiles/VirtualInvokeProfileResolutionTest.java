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
import com.oracle.svm.core.pgo.IprofFormat;
import com.oracle.svm.hosted.pgo.IprofParser;
import com.oracle.svm.hosted.pgo.IprofParser.IprofFormatException;
import com.oracle.svm.hosted.pgo.IprofParser.ParsedIprofProfile;
import com.oracle.svm.hosted.pgo.IprofParser.VirtualInvokeEntry;
import com.oracle.svm.hosted.pgo.profiles.PGODiagnostics.VirtualInvokeDiagnostics;

import jdk.vm.ci.code.BytecodePosition;
import jdk.vm.ci.meta.ResolvedJavaMethod;

/**
 * Unit tests for the {@code virtualInvokeProfiles} section: how the parser reads it and how the
 * lookup resolves it, including the shorter-context fallback for receivers.
 */
public class VirtualInvokeProfileResolutionTest {

    private static final String TYPES_AND_METHODS = "\"types\":[" +
                    "{\"id\":4,\"name\":\"int\"}," +
                    "{\"id\":8,\"name\":\"void\"}," +
                    "{\"id\":100,\"name\":\"com.example.Foo\"}," +
                    "{\"id\":101,\"name\":\"java.lang.String\"}," +
                    "{\"id\":102,\"name\":\"java.lang.Integer\"}" +
                    "]," +
                    "\"methods\":[" +
                    "{\"id\":1001,\"name\":\"bar\",\"signature\":[100,8,4]}" +
                    "],\"conditionalProfiles\":[],";

    private static ParsedIprofProfile parse(String virtualInvokeProfiles) throws IOException {
        String json = "{\"version\":\"1.1.0\"," + TYPES_AND_METHODS + "\"virtualInvokeProfiles\":[" + virtualInvokeProfiles + "]}";
        return new IprofParser().parse(new StringReader(json));
    }

    @Test
    public void parsesReceiverTypePairs() throws IOException {
        ParsedIprofProfile parsed = parse("{\"ctx\":\"1001:9\",\"records\":[101,7,102,3]}");
        Assert.assertEquals(1, parsed.virtualInvokeEntries().size());
        VirtualInvokeEntry entry = parsed.virtualInvokeEntries().getFirst();
        Assert.assertEquals(1, entry.context().size());
        Assert.assertArrayEquals(new long[]{101, 7, 102, 3}, entry.records());
    }

    @Test
    public void sectionIsOptional() throws IOException {
        String json = "{\"version\":\"1.1.0\"," + TYPES_AND_METHODS + "\"conditionalProfiles\":[]}";
        ParsedIprofProfile parsed = new IprofParser().parse(new StringReader(json));
        Assert.assertTrue(parsed.virtualInvokeEntries().isEmpty());
    }

    @Test(expected = IprofFormatException.class)
    public void rejectsOddRecordLength() throws IOException {
        parse("{\"ctx\":\"1001:9\",\"records\":[101,7,102]}");
    }

    @Test(expected = IprofFormatException.class)
    public void rejectsPrimitiveReceiverType() throws IOException {
        parse("{\"ctx\":\"1001:9\",\"records\":[4,7]}");
    }

    @Test(expected = IprofFormatException.class)
    public void rejectsVoidReceiverType() throws IOException {
        parse("{\"ctx\":\"1001:9\",\"records\":[8,7]}");
    }

    @Test(expected = IprofFormatException.class)
    public void rejectsUndeclaredReceiverType() throws IOException {
        parse("{\"ctx\":\"1001:9\",\"records\":[999,7]}");
    }

    /*
     * AnalysisType cannot be instantiated in a plain unit test, so resolution is exercised through
     * the drop-accounting path: contexts resolve, but no receiver type is present in the image.
     */
    @Test
    public void accountsForContextsAndDroppedReceivers() throws IOException {
        ParsedIprofProfile parsed = parse("{\"ctx\":\"1001:9\",\"records\":[101,7,102,3]}," +
                        "{\"ctx\":\"1001:9<1001:14\",\"records\":[101,5]}," +
                        "{\"ctx\":\"99999:1\",\"records\":[101,1]}");
        Map<String, AnalysisType> noTypes = Map.of();
        IprofProfilesLookup lookup = IprofProfileResolverTest.buildLookup(parsed, Set.of(IprofProfileResolverTest.BAR_DESC), noTypes);

        VirtualInvokeDiagnostics virtualInvokeDiagnostics = lookup.diagnostics().virtualInvoke();
        Assert.assertEquals(3, virtualInvokeDiagnostics.totalEntries());
        Assert.assertEquals(0, virtualInvokeDiagnostics.resolvedEntries());
        Assert.assertEquals(1, virtualInvokeDiagnostics.unresolvedEntries());
        Assert.assertEquals(2, virtualInvokeDiagnostics.noReceiverInImageEntries());
        Assert.assertEquals(3, virtualInvokeDiagnostics.receiverRecords());
        Assert.assertEquals(3, virtualInvokeDiagnostics.droppedReceiverRecords());
        Assert.assertEquals(15, virtualInvokeDiagnostics.receiverEvents());
        Assert.assertEquals(15, virtualInvokeDiagnostics.droppedReceiverEvents());
        Assert.assertFalse(lookup.profileCategoryRecorded(IprofFormat.VIRTUAL_INVOKE_PROFILES));
        Optional<Map<AnalysisType, Long>> result = lookup.getVirtualInvokeProfile(new BytecodePosition(null, IprofProfileResolverTest.mockBarMethod(), 9));
        Assert.assertTrue(result.isEmpty());
    }

    /**
     * A receiver profile recorded for a call compiled standalone must also serve the same call once
     * the compiled image inlines its method into a caller (deeper query context), when the fallback
     * is enabled; an exact-only lookup misses it.
     */
    @Test
    public void shortenedContextFallbackServesInlinedCopies() {
        ResolvedJavaMethod bar = IprofProfileResolverTest.mockBarMethod();
        Map<AnalysisType, Long> receivers = Collections.singletonMap(null, 7L);
        Map<PGOContext, Map<AnalysisType, Long>> data = Map.of(new PGOContext(List.of(new PGOContext.Frame(IprofProfileResolverTest.BAR_DESC, 9))), receivers);
        BytecodePosition inlinedTwice = new BytecodePosition(new BytecodePosition(new BytecodePosition(null, bar, 30), bar, 14), bar, 9);

        IprofProfilesLookup exactOnly = IprofProfileResolverTest.receiverLookup(data);
        exactOnly.setVirtualInvokeContextFallback(false);
        Assert.assertTrue(exactOnly.getVirtualInvokeProfile(inlinedTwice).isEmpty());
        Assert.assertEquals(1, exactOnly.virtualInvokeMisses());
        Assert.assertEquals(0, exactOnly.virtualInvokeContextFallbackHits());

        IprofProfilesLookup withFallback = IprofProfileResolverTest.receiverLookup(data);
        withFallback.setVirtualInvokeContextFallback(true);
        Assert.assertSame(receivers, withFallback.getVirtualInvokeProfile(inlinedTwice).orElseThrow());
        Assert.assertEquals(1, withFallback.virtualInvokeHits());
        Assert.assertEquals(1, withFallback.virtualInvokeContextFallbackHits());
        Assert.assertEquals(2, withFallback.virtualInvokeContextFallbackDroppedFrames());
        Assert.assertEquals(1, withFallback.matchedVirtualInvokeContextCount());
        /* An exact match does not count as a fallback. */
        Assert.assertSame(receivers, withFallback.getVirtualInvokeProfile(new BytecodePosition(null, bar, 9)).orElseThrow());
        Assert.assertEquals(1, withFallback.virtualInvokeContextFallbackHits());
        /* A different call site in the same method is never served by the fallback. */
        Assert.assertTrue(withFallback.getVirtualInvokeProfile(new BytecodePosition(new BytecodePosition(null, bar, 30), bar, 10)).isEmpty());
    }

    /* The phase reports a site's impossible receivers once per copy of the site, so their sum stops at Long.MAX_VALUE. */
    @Test
    public void impossibleReceiverEventsStopAtTheLongLimit() {
        IprofProfilesLookup lookup = IprofProfileResolverTest.receiverLookup(Map.of());
        lookup.recordImpossibleReceiver(Long.MAX_VALUE - 1);
        lookup.recordImpossibleReceiver(5);
        Assert.assertEquals(2, lookup.impossibleReceiverRecords());
        Assert.assertEquals(Long.MAX_VALUE, lookup.impossibleReceiverEvents());
    }
}
