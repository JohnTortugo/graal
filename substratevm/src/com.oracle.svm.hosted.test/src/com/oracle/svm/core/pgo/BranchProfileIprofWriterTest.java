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
package com.oracle.svm.core.pgo;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

import com.oracle.svm.hosted.pgo.IprofConditionalParser;
import com.oracle.svm.hosted.pgo.IprofConditionalParser.ConditionalEntry;
import com.oracle.svm.hosted.pgo.IprofConditionalParser.ParsedProfile;

public class BranchProfileIprofWriterTest {

    @Test
    public void emittedProfileRoundTripsThroughConsumerParser() throws Exception {
        BranchProfileCounter counter = BranchProfileRecorder.lookup(
                        new String[]{"Lexample/WriterTest;.run(I[Ljava/lang/String;)V", "Lexample/WriterTestRoot;.main([Ljava/lang/String;)V"},
                        new int[]{11, 29}, 17, 23);
        BranchProfileRecorder.increment(counter.getCounterIndex(), true);
        BranchProfileRecorder.increment(counter.getCounterIndex(), true);
        BranchProfileRecorder.increment(counter.getCounterIndex(), true);
        BranchProfileRecorder.increment(counter.getCounterIndex(), false);

        StringWriter output = new StringWriter();
        BranchProfileIprofWriter.DumpStatistics statistics = BranchProfileIprofWriter.write(output, List.of(counter));
        ParsedProfile parsed = new IprofConditionalParser().parse(new StringReader(output.toString()));

        Assert.assertEquals("1.1.0", parsed.version());
        Assert.assertEquals(2, parsed.methodsById().size());
        Assert.assertEquals(5, parsed.typeNamesById().size());
        Assert.assertEquals(1, parsed.conditionalEntries().size());
        ConditionalEntry entry = parsed.conditionalEntries().getFirst();
        Assert.assertEquals(2, entry.context().size());
        Assert.assertArrayEquals(new long[]{17, 0, 3, 23, 1, 1}, entry.records());
        Assert.assertEquals(1, statistics.conditionalProfiles());
        Assert.assertEquals(4, statistics.recordedEvents());
    }

    @Test
    public void outputIsDeterministicAndOmitsUnexecutedCounters() throws Exception {
        BranchProfileCounter active = BranchProfileRecorder.lookup(
                        new String[]{"Lexample/DeterminismTest;.active()I"}, new int[]{7}, 10, 20);
        BranchProfileCounter inactive = BranchProfileRecorder.lookup(
                        new String[]{"Lexample/DeterminismTest;.inactive()I"}, new int[]{8}, 30, 40);
        BranchProfileRecorder.increment(active.getCounterIndex(), false);

        StringWriter first = new StringWriter();
        StringWriter second = new StringWriter();
        BranchProfileIprofWriter.DumpStatistics statistics = BranchProfileIprofWriter.write(first, List.of(inactive, active));
        BranchProfileIprofWriter.write(second, List.of(inactive, active));

        Assert.assertEquals(first.toString(), second.toString());
        Assert.assertEquals(1, statistics.conditionalProfiles());
        ParsedProfile parsed = new IprofConditionalParser().parse(new StringReader(first.toString()));
        Assert.assertEquals(1, parsed.conditionalEntries().size());
        Assert.assertArrayEquals(new long[]{10, 0, 0, 20, 1, 1}, parsed.conditionalEntries().getFirst().records());
    }
}
