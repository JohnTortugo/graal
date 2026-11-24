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
import java.util.List;
import java.util.Map;

import org.junit.Assert;
import org.junit.Test;

import com.oracle.svm.core.pgo.IprofFormat;
import com.oracle.svm.hosted.pgo.IprofParser;
import com.oracle.svm.hosted.pgo.IprofParser.IprofFormatException;
import com.oracle.svm.hosted.pgo.IprofParser.ParsedIprofProfile;
import com.oracle.svm.hosted.pgo.IprofParser.SamplingEntry;

import jdk.graal.compiler.graph.NodeSourcePosition;

/**
 * Unit tests for the {@code samplingProfiles} section: how the parser reads the sampled stacks and
 * how the lookup resolves and hides them.
 */
public class SamplingProfileResolutionTest {

    private static final String TYPES_AND_METHODS = "\"types\":[" +
                    "{\"id\":4,\"name\":\"int\"}," +
                    "{\"id\":8,\"name\":\"void\"}," +
                    "{\"id\":100,\"name\":\"com.example.Foo\"}" +
                    "]," +
                    "\"methods\":[" +
                    "{\"id\":1001,\"name\":\"bar\",\"signature\":[100,8,4]}," +
                    "{\"id\":1005,\"name\":\"main\",\"signature\":[100,8]}" +
                    "],\"conditionalProfiles\":[],";

    private static ParsedIprofProfile parse(String samplingProfiles) throws IOException {
        String json = "{\"version\":\"1.1.0\"," + TYPES_AND_METHODS + "\"samplingProfiles\":[" + samplingProfiles + "]}";
        return new IprofParser().parse(new StringReader(json));
    }

    @Test
    public void parsesStacksInnermostFirst() throws IOException {
        ParsedIprofProfile parsed = parse("{\"ctx\":\"1001:9<1005:3\",\"records\":[42]}");
        Assert.assertEquals(1, parsed.samplingEntries().size());
        SamplingEntry entry = parsed.samplingEntries().getFirst();
        Assert.assertEquals(42, entry.count());
        Assert.assertEquals(List.of(1001, 1005), entry.context().stream().map(IprofParser.ContextFrame::methodId).toList());
        Assert.assertEquals(9, entry.context().getFirst().bci());
    }

    @Test
    public void sectionIsOptional() throws IOException {
        String json = "{\"version\":\"1.1.0\"," + TYPES_AND_METHODS + "\"conditionalProfiles\":[]}";
        ParsedIprofProfile parsed = new IprofParser().parse(new StringReader(json));
        Assert.assertTrue(parsed.samplingEntries().isEmpty());
    }

    @Test(expected = IprofFormatException.class)
    public void rejectsMultipleRecords() throws IOException {
        parse("{\"ctx\":\"1001:9\",\"records\":[1,2]}");
    }

    @Test(expected = IprofFormatException.class)
    public void rejectsNegativeCount() throws IOException {
        parse("{\"ctx\":\"1001:9\",\"records\":[-1]}");
    }

    /*
     * Real resolution needs HostedMethod instances; with none present every stack is unresolvable
     * at its leaf and is dropped, which exercises the accounting path.
     */
    @Test
    public void accountsForUnresolvableStacks() throws IOException {
        ParsedIprofProfile parsed = parse("{\"ctx\":\"1001:9<1005:3\",\"records\":[7]},{\"ctx\":\"99999:1\",\"records\":[5]}");
        IprofProfilesLookup lookup = IprofProfileResolverTest.buildLookup(parsed, java.util.Set.of(IprofProfileResolverTest.BAR_DESC));
        IprofProfileResolver.SamplingDiagnostics d = lookup.samplingDiagnostics();
        Assert.assertEquals(2, d.totalEntries());
        Assert.assertEquals(0, d.resolvedEntries());
        Assert.assertEquals(2, d.unresolvedEntries());
        Assert.assertEquals(12, d.samples());
        Assert.assertEquals(12, d.droppedSamples());
        Assert.assertTrue(lookup.getSampleCounts().isEmpty());
        Assert.assertFalse(lookup.profileCategoryRecorded(IprofFormat.SAMPLING_PROFILES));
        Assert.assertTrue(d.summary().contains(IprofProfileResolverTest.BAR_DESC));
    }

    /*
     * -H:-PGOUseStackSamples: the lookup then answers as for a profile without stack samples, so no
     * consumer sees them (sampled hotness, sampled callee profiles, self time).
     */
    @Test
    public void stackSamplesCanBeHidden() {
        Map<NodeSourcePosition, Long> samples = Map.of(new NodeSourcePosition(null, IprofProfileResolverTest.mockBarMethod(), 9), 42L);
        IprofProfilesLookup lookup = IprofProfileResolverTest.samplingLookup(samples);
        Assert.assertTrue(lookup.profileCategoryRecorded(IprofFormat.SAMPLING_PROFILES));
        Assert.assertEquals(List.of(42L), List.copyOf(lookup.getSampleCounts().orElseThrow().values()));

        lookup.setUseStackSamples(false);
        Assert.assertFalse(lookup.profileCategoryRecorded(IprofFormat.SAMPLING_PROFILES));
        Assert.assertTrue(lookup.getSampleCounts().isEmpty());
    }
}
