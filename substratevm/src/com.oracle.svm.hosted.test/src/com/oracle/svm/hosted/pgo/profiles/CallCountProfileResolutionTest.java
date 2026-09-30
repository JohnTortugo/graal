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

import java.io.StringReader;
import java.util.Set;

import org.junit.Assert;
import org.junit.Test;

import com.oracle.svm.hosted.pgo.IprofConditionalParser;
import com.oracle.svm.hosted.pgo.IprofConditionalParser.IprofFormatException;
import com.oracle.svm.hosted.pgo.IprofConditionalParser.ParsedProfile;

import jdk.vm.ci.code.BytecodePosition;

public class CallCountProfileResolutionTest {
    private static final String PREFIX = "{\"version\":\"1.1.0\",\"types\":[{\"id\":1,\"name\":\"com.example.Foo\"},{\"id\":2,\"name\":\"void\"},{\"id\":3,\"name\":\"int\"}]," +
                    "\"methods\":[{\"id\":7,\"name\":\"bar\",\"signature\":[1,2,3]},{\"id\":8,\"name\":\"caller\",\"signature\":[1,2,3]}],\"conditionalProfiles\":[],\"callCountProfiles\":";

    private static ParsedProfile parse(String entries) throws Exception {
        return new IprofConditionalParser().parse(new StringReader(PREFIX + entries + "}"));
    }

    @Test
    public void parsesResolvesAndAggregatesContexts() throws Exception {
        ParsedProfile parsed = parse("[{\"ctx\":\"7:0\",\"records\":[3]},{\"ctx\":\"7:0\",\"records\":[4]}]");
        Assert.assertEquals(2, parsed.callCountEntries().size());
        SimpleConditionalProfilesLookup lookup = ConditionalProfileContextResolver.buildLookup(parsed, Set.of(ConditionalProfileContextResolverTest.BAR_DESC));
        Assert.assertTrue(lookup.profileCategoryRecorded(SimpleConditionalProfilesLookup.CALL_COUNT_PROFILES_CATEGORY));
        Assert.assertEquals(Long.valueOf(7), lookup.getContextCallCount(new BytecodePosition(null, ConditionalProfileContextResolverTest.mockBarMethod(), 0)).orElseThrow());
        Assert.assertEquals(2, lookup.callCountDiagnostics().resolvedEntries());
        Assert.assertEquals(7, lookup.callCountDiagnostics().totalCount());

        lookup.setUseCallCounts(false);
        Assert.assertFalse(lookup.profileCategoryRecorded(SimpleConditionalProfilesLookup.CALL_COUNT_PROFILES_CATEGORY));
        Assert.assertTrue(lookup.getContextCallCount(new BytecodePosition(null, ConditionalProfileContextResolverTest.mockBarMethod(), 0)).isEmpty());
    }

    @Test
    public void missingOuterFrameDoesNotUndercountPresentMethod() throws Exception {
        ParsedProfile parsed = parse("[{\"ctx\":\"7:0<8:4\",\"records\":[9]}]");
        SimpleConditionalProfilesLookup lookup = ConditionalProfileContextResolver.buildLookup(parsed, Set.of(ConditionalProfileContextResolverTest.BAR_DESC));
        Assert.assertEquals(0, lookup.callCountDiagnostics().resolvedEntries());
        Assert.assertEquals(1, lookup.callCountDiagnostics().unresolvedEntries());
        Assert.assertEquals(9, lookup.getMethodCallCount(ConditionalProfileContextResolverTest.BAR_DESC));
    }

    @Test(expected = IprofFormatException.class)
    public void rejectsNonEntryBci() throws Exception {
        parse("[{\"ctx\":\"7:1\",\"records\":[1]}]");
    }

    @Test(expected = IprofFormatException.class)
    public void rejectsMultipleRecords() throws Exception {
        parse("[{\"ctx\":\"7:0\",\"records\":[1,2]}]");
    }
}
