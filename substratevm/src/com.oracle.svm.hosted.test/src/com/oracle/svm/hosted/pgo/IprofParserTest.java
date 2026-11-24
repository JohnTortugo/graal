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
import java.io.StringReader;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

import com.oracle.svm.hosted.pgo.IprofParser.ConditionalEntry;
import com.oracle.svm.hosted.pgo.IprofParser.ContextFrame;
import com.oracle.svm.hosted.pgo.IprofParser.IprofFormatException;
import com.oracle.svm.hosted.pgo.IprofParser.ParsedIprofProfile;

/**
 * Unit tests for {@link IprofParser}: the sections it reads, the sections it skips, and the
 * malformed input it rejects.
 */
public class IprofParserTest {

    private static final String TYPES_AND_METHODS = "\"types\":[" +
                    "{\"id\":4,\"name\":\"int\"}," +
                    "{\"id\":9,\"name\":\"java.lang.Object\"}," +
                    "{\"id\":8,\"name\":\"void\"}," +
                    "{\"id\":100,\"name\":\"com.example.Foo\"}," +
                    "{\"id\":101,\"name\":\"java.lang.String\"}" +
                    "]," +
                    "\"methods\":[" +
                    "{\"id\":1001,\"name\":\"bar\",\"signature\":[100,8,4]}," +
                    "{\"id\":1002,\"name\":\"baz\",\"signature\":[100,4,101]}" +
                    "],";

    private static ParsedIprofProfile parse(String json) throws IOException {
        return new IprofParser().parse(new StringReader(json));
    }

    private static String profile(String version, String conditionalProfiles) {
        return "{\"version\":\"" + version + "\"," + TYPES_AND_METHODS + "\"conditionalProfiles\":[" + conditionalProfiles + "]}";
    }

    @Test
    public void parsesSingleFrameContext() throws IOException {
        ParsedIprofProfile parsed = parse(profile("1.0.0", "{\"ctx\":\"1001:9\",\"records\":[20,0,10,53,1,1]}"));

        Assert.assertEquals("1.0.0", parsed.version());
        Assert.assertEquals(1, parsed.conditionalEntries().size());
        ConditionalEntry entry = parsed.conditionalEntries().get(0);
        Assert.assertEquals(List.of(new ContextFrame(1001, 9)), entry.context());
        Assert.assertArrayEquals(new long[]{20, 0, 10, 53, 1, 1}, entry.records());
    }

    @Test
    public void parsesInlinedContextInnermostFirst() throws IOException {
        ParsedIprofProfile parsed = parse(profile("1.0.0", "{\"ctx\":\"1001:9<1002:1<1003:6<1004:2\",\"records\":[12,0,6,14,1,0]}"));

        ConditionalEntry entry = parsed.conditionalEntries().get(0);
        Assert.assertEquals(
                        List.of(new ContextFrame(1001, 9), new ContextFrame(1002, 1), new ContextFrame(1003, 6), new ContextFrame(1004, 2)),
                        entry.context());
    }

    @Test
    public void acceptsVersion110() throws IOException {
        ParsedIprofProfile parsed = parse(profile("1.1.0", "{\"ctx\":\"1001:9\",\"records\":[20,0,10,53,1,1]}"));
        Assert.assertEquals("1.1.0", parsed.version());
    }

    @Test
    public void parsesTypeAndMethodTables() throws IOException {
        ParsedIprofProfile parsed = parse(profile("1.0.0", "{\"ctx\":\"1001:9\",\"records\":[20,0,10,53,1,1]}"));

        Assert.assertEquals("com.example.Foo", parsed.typeNamesById().get(100));
        Assert.assertEquals("bar", parsed.methodsById().get(1001).name());
        Assert.assertEquals(100, parsed.methodsById().get(1001).declaringTypeId());
        Assert.assertEquals(8, parsed.methodsById().get(1001).returnTypeId());
        Assert.assertArrayEquals(new int[]{4}, parsed.methodsById().get(1001).parameterTypeIds());
    }

    @Test
    public void rejectsUnsupportedVersion() {
        assertRejected(() -> parse(profile("2.0.0", "{\"ctx\":\"1001:9\",\"records\":[20,0,10,53,1,1]}")), "version");
    }

    @Test
    public void rejectsRecordsNotMultipleOfThree() {
        assertRejected(() -> parse(profile("1.0.0", "{\"ctx\":\"1001:9\",\"records\":[20,0,10,53]}")), "multiple of");
    }

    @Test
    public void rejectsEmptyRecords() {
        assertRejected(() -> parse(profile("1.0.0", "{\"ctx\":\"1001:9\",\"records\":[]}")), "multiple of");
    }

    @Test
    public void rejectsMalformedContextFrame() {
        assertRejected(() -> parse(profile("1.0.0", "{\"ctx\":\"1001\",\"records\":[20,0,10,53,1,1]}")), "context frame");
    }

    @Test
    public void parsesAbsentConditionalProfilesAsEmpty() throws IOException {
        // conditionalProfiles is optional; when the section is absent the file is well-formed and
        // parses with an empty conditional-entry list rather than being rejected as malformed.
        ParsedIprofProfile parsed = parse("{\"version\":\"1.0.0\"," + TYPES_AND_METHODS.substring(0, TYPES_AND_METHODS.length() - 1) + "}");
        Assert.assertEquals("1.0.0", parsed.version());
        Assert.assertTrue(parsed.conditionalEntries().isEmpty());
        // Required sections remain populated.
        Assert.assertEquals("com.example.Foo", parsed.typeNamesById().get(100));
        Assert.assertEquals("bar", parsed.methodsById().get(1001).name());
    }

    @Test
    public void parsesEmptyConditionalProfilesArrayAsEmpty() throws IOException {
        ParsedIprofProfile parsed = parse(profile("1.0.0", ""));
        Assert.assertTrue(parsed.conditionalEntries().isEmpty());
    }

    @Test
    public void rejectsConditionalProfilesThatIsNotAnArray() {
        // Present-but-wrong-type must still be rejected even though the section is optional.
        String json = "{\"version\":\"1.0.0\"," + TYPES_AND_METHODS + "\"conditionalProfiles\":\"nope\"}";
        assertRejected(() -> parse(json), "conditionalProfiles");
    }

    @Test
    public void rejectsEmptyTypeName() {
        // version/types/methods stay strict: an empty type name is malformed input.
        String json = "{\"version\":\"1.0.0\"," +
                        "\"types\":[{\"id\":100,\"name\":\"\"}]," +
                        "\"methods\":[]," +
                        "\"conditionalProfiles\":[]}";
        assertRejected(() -> parse(json), "Empty type name");
    }

    @Test
    public void rejectsMissingVersionSection() {
        String json = "{" + TYPES_AND_METHODS + "\"conditionalProfiles\":[]}";
        assertRejected(() -> parse(json), "version");
    }

    @Test
    public void rejectsMissingTypesSection() {
        String json = "{\"version\":\"1.0.0\",\"methods\":[],\"conditionalProfiles\":[]}";
        assertRejected(() -> parse(json), "types");
    }

    @Test
    public void rejectsMissingMethodsSection() {
        String json = "{\"version\":\"1.0.0\",\"types\":[],\"conditionalProfiles\":[]}";
        assertRejected(() -> parse(json), "methods");
    }

    @Test
    public void rejectsUnsafeConditionalRecordValues() {
        assertRejected(() -> parse(profile("1.1.0", "{\"ctx\":\"1001:9\",\"records\":[20,-1,1,53,1,1]}")), "branch index");
        assertRejected(() -> parse(profile("1.1.0", "{\"ctx\":\"1001:9\",\"records\":[20,2,1,53,1,1]}")), "branch index");
        assertRejected(() -> parse(profile("1.1.0", "{\"ctx\":\"1001:9\",\"records\":[20,0,-1,53,1,1]}")), "negative count");
        assertRejected(() -> parse(profile("1.1.0", "{\"ctx\":\"1001:9\",\"records\":[2147483648,0,1,53,1,1]}")), "integer range");
        assertRejected(() -> parse(profile("1.1.0", "{\"ctx\":\"1001:9\",\"records\":[20,0,9223372036854775807,53,1,1]}")), "overflow");
    }

    @Test
    public void skipsSectionsItDoesNotRead() throws IOException {
        /* The producer writes monitorProfiles, which nothing reads yet: skipped, even if malformed. */
        String json = "{\"version\":\"1.1.0\"," + TYPES_AND_METHODS +
                        "\"conditionalProfiles\":[{\"ctx\":\"1001:9\",\"records\":[20,0,10,53,1,1]}]," +
                        "\"monitorProfiles\":[{\"ctx\":\"not a context\",\"records\":[-1]}]}";
        ParsedIprofProfile parsed = parse(json);
        Assert.assertEquals(1, parsed.conditionalEntries().size());
        Assert.assertArrayEquals(new long[]{20, 0, 10, 53, 1, 1}, parsed.conditionalEntries().getFirst().records());
    }

    private interface ThrowingParse {
        void run() throws IOException;
    }

    private static void assertRejected(ThrowingParse parse, String expectedFragment) {
        try {
            parse.run();
            Assert.fail("expected IprofFormatException containing '" + expectedFragment + "'");
        } catch (IprofFormatException e) {
            Assert.assertTrue("message '" + e.getMessage() + "' should contain '" + expectedFragment + "'", e.getMessage().contains(expectedFragment));
        } catch (IOException e) {
            Assert.fail("unexpected IOException: " + e.getMessage());
        }
    }
}
