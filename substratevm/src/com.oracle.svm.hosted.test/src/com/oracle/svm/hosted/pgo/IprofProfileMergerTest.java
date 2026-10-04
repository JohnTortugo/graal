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
import java.util.Map;

import org.junit.Assert;
import org.junit.Test;

import com.oracle.svm.hosted.pgo.IprofConditionalParser.ContextFrame;
import com.oracle.svm.hosted.pgo.IprofConditionalParser.MethodDescriptor;
import com.oracle.svm.hosted.pgo.IprofConditionalParser.ParsedProfile;
import com.oracle.svm.hosted.pgo.IprofProfileMerger.WeightedProfile;

/** Unit tests for {@link IprofProfileMerger}. */
public class IprofProfileMergerTest {

    /* Two files describing the same program with different id assignments. */
    private static final String FIRST = "{\"version\":\"1.1.0\"," +
                    "\"types\":[{\"id\":4,\"name\":\"int\"},{\"id\":8,\"name\":\"void\"},{\"id\":100,\"name\":\"com.example.Foo\"},{\"id\":101,\"name\":\"com.example.Impl\"}]," +
                    "\"methods\":[{\"id\":1,\"name\":\"bar\",\"signature\":[100,8,4]},{\"id\":2,\"name\":\"baz\",\"signature\":[100,4]}]," +
                    "\"conditionalProfiles\":[{\"ctx\":\"1:9\",\"records\":[20,0,10,53,1,1]}]," +
                    "\"ceConditionalProfilesV2\":[{\"stage\":\"POST_HIGH_TIER\",\"ctx\":\"1:9\",\"successors\":[20,53],\"conditionKind\":\"K\",\"conditionFingerprint\":\"ab\",\"occurrence\":0,\"records\":[20,0,10,53,1,1]}]," +
                    "\"callCountProfiles\":[{\"ctx\":\"2:0<1:30\",\"records\":[100]}]," +
                    "\"virtualInvokeProfiles\":[{\"ctx\":\"1:40\",\"records\":[101,7]}]," +
                    "\"samplingProfiles\":[{\"ctx\":\"2:5<1:30\",\"records\":[3]}]}";

    private static final String SECOND = "{\"version\":\"1.1.0\"," +
                    "\"types\":[{\"id\":0,\"name\":\"com.example.Impl\"},{\"id\":1,\"name\":\"com.example.Foo\"},{\"id\":2,\"name\":\"void\"},{\"id\":3,\"name\":\"int\"},{\"id\":5,\"name\":\"com.example.Other\"}]," +
                    "\"methods\":[{\"id\":7,\"name\":\"baz\",\"signature\":[1,3]},{\"id\":8,\"name\":\"bar\",\"signature\":[1,2,3]},{\"id\":9,\"name\":\"qux\",\"signature\":[5,2]}]," +
                    "\"conditionalProfiles\":[{\"ctx\":\"8:9\",\"records\":[20,0,5,53,1,9]},{\"ctx\":\"9:3\",\"records\":[6,0,1,8,1,1]}]," +
                    "\"ceConditionalProfilesV2\":[{\"stage\":\"POST_HIGH_TIER\",\"ctx\":\"8:9\",\"successors\":[20,53],\"conditionKind\":\"K\",\"conditionFingerprint\":\"ab\",\"occurrence\":0,\"records\":[20,0,5,53,1,9]}]," +
                    "\"callCountProfiles\":[{\"ctx\":\"7:0<8:30\",\"records\":[50]}]," +
                    "\"virtualInvokeProfiles\":[{\"ctx\":\"8:40\",\"records\":[0,1,5,2]}]," +
                    "\"samplingProfiles\":[{\"ctx\":\"7:5<8:30\",\"records\":[1]},{\"ctx\":\"9:3\",\"records\":[4]}]}";

    private static ParsedProfile parse(String json) throws IOException {
        return new IprofConditionalParser().parse(new StringReader(json));
    }

    private static int methodId(ParsedProfile profile, String name) {
        return profile.methodsById().values().stream().filter(m -> m.name().equals(name)).map(MethodDescriptor::methodId).findFirst().orElseThrow();
    }

    private static int typeId(ParsedProfile profile, String name) {
        return profile.typeNamesById().entrySet().stream().filter(e -> e.getValue().equals(name)).map(Map.Entry::getKey).findFirst().orElseThrow();
    }

    @Test
    public void remapsIdentitiesAndAddsWeightedCounts() throws IOException {
        ParsedProfile merged = IprofProfileMerger.merge(List.of(new WeightedProfile(parse(FIRST), 1.0), new WeightedProfile(parse(SECOND), 2.0)));

        Assert.assertEquals("1.1.0", merged.version());
        Assert.assertEquals(5, merged.typeNamesById().size());
        Assert.assertEquals(3, merged.methodsById().size());
        int bar = methodId(merged, "bar");
        int baz = methodId(merged, "baz");
        int qux = methodId(merged, "qux");
        MethodDescriptor barDescriptor = merged.methodsById().get(bar);
        Assert.assertEquals(typeId(merged, "com.example.Foo"), barDescriptor.declaringTypeId());
        Assert.assertEquals(typeId(merged, "void"), barDescriptor.returnTypeId());
        Assert.assertArrayEquals(new int[]{typeId(merged, "int")}, barDescriptor.parameterTypeIds());

        /* Branch counts: 10 + 2*5 and 1 + 2*9; the second file's extra site survives untouched. */
        Assert.assertEquals(2, merged.conditionalEntries().size());
        Assert.assertArrayEquals(new long[]{20, 0, 20, 53, 1, 19}, merged.conditionalEntries().stream().filter(e -> e.context().equals(List.of(new ContextFrame(bar, 9)))).findFirst().orElseThrow().records());
        Assert.assertArrayEquals(new long[]{6, 0, 2, 8, 1, 2}, merged.conditionalEntries().stream().filter(e -> e.context().equals(List.of(new ContextFrame(qux, 3)))).findFirst().orElseThrow().records());
        Assert.assertEquals(1, merged.preciseConditionalEntries().size());
        Assert.assertArrayEquals(new long[]{20, 0, 20, 53, 1, 19}, merged.preciseConditionalEntries().get(0).records());

        Assert.assertEquals(1, merged.callCountEntries().size());
        Assert.assertEquals(List.of(new ContextFrame(baz, 0), new ContextFrame(bar, 30)), merged.callCountEntries().get(0).context());
        Assert.assertEquals(200, merged.callCountEntries().get(0).count());

        Assert.assertEquals(1, merged.virtualInvokeEntries().size());
        long[] receivers = merged.virtualInvokeEntries().get(0).records();
        Assert.assertEquals(4, receivers.length);
        Assert.assertEquals(typeId(merged, "com.example.Impl"), (int) receivers[0]);
        Assert.assertEquals(7 + 2 * 1, receivers[1]);
        Assert.assertEquals(typeId(merged, "com.example.Other"), (int) receivers[2]);
        Assert.assertEquals(2 * 2, receivers[3]);

        Assert.assertEquals(2, merged.samplingEntries().size());
        Assert.assertEquals(3 + 2 * 1, merged.samplingEntries().stream().filter(e -> e.context().size() == 2).findFirst().orElseThrow().count());
    }

    @Test
    public void overlapIsOneForTheSameRunAndSmallerForDifferentHotMethods() throws IOException {
        ParsedProfile first = parse(FIRST);
        ParsedProfile second = parse(SECOND);
        Assert.assertEquals(1.0, IprofProfileMerger.samplingOverlap(first, first), 1e-9);
        /* FIRST: bar 3, baz 3 (one stack). SECOND: bar 1, baz 1, qux 4. Shared share per method: min(0.5, 1/6) * 2. */
        Assert.assertEquals(2.0 / 6.0, IprofProfileMerger.samplingOverlap(first, second), 1e-9);
        Assert.assertEquals(IprofProfileMerger.samplingOverlap(first, second), IprofProfileMerger.samplingOverlap(second, first), 1e-9);
    }

    @Test
    public void singleUnweightedProfileIsReturnedAsIs() throws IOException {
        ParsedProfile first = parse(FIRST);
        Assert.assertSame(first, IprofProfileMerger.merge(List.of(new WeightedProfile(first, 1.0))));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNonPositiveWeight() throws IOException {
        new WeightedProfile(parse(FIRST), 0.0);
    }
}
