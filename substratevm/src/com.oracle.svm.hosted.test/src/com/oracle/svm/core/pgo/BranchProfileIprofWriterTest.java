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
import java.util.Map;

import org.junit.Assert;
import org.junit.Test;

import com.oracle.svm.hosted.pgo.IprofConditionalParser;
import com.oracle.svm.hosted.pgo.IprofConditionalParser.ConditionalEntry;
import com.oracle.svm.hosted.pgo.IprofConditionalParser.ParsedProfile;
import com.oracle.svm.hosted.pgo.IprofConditionalParser.PreciseConditionalEntry;

public class BranchProfileIprofWriterTest {

    @Test
    public void emittedProfileRoundTripsThroughConsumerParser() throws Exception {
        BranchProfileCounter counter = BranchProfileRecorder.lookup(
                        new String[]{"Lexample/WriterTest;.run(I[Ljava/lang/String;)V", "Lexample/WriterTestRoot;.main([Ljava/lang/String;)V"},
                        new int[]{11, 29}, 17, 23);
        BranchProfileRecorder.incrementHosted(counter.getCounterIndex(), true);
        BranchProfileRecorder.incrementHosted(counter.getCounterIndex(), true);
        BranchProfileRecorder.incrementHosted(counter.getCounterIndex(), true);
        BranchProfileRecorder.incrementHosted(counter.getCounterIndex(), false);

        StringWriter output = new StringWriter();
        BranchProfileIprofWriter.DumpStatistics statistics = BranchProfileIprofWriter.write(output, List.of(counter));
        ParsedProfile parsed = new IprofConditionalParser().parse(new StringReader(output.toString()));

        Assert.assertEquals("1.1.0", parsed.version());
        Assert.assertFalse(parsed.monitorProfilesRecorded());
        Assert.assertEquals(2, parsed.methodsById().size());
        Assert.assertEquals(5, parsed.typeNamesById().size());
        Assert.assertEquals(1, parsed.conditionalEntries().size());
        ConditionalEntry entry = parsed.conditionalEntries().getFirst();
        Assert.assertEquals(2, entry.context().size());
        Assert.assertArrayEquals(new long[]{17, 0, 3, 23, 1, 1}, entry.records());
        Assert.assertEquals(1, statistics.conditionalProfiles());
        Assert.assertEquals(1, statistics.preciseConditionalProfiles());
        Assert.assertEquals(1, parsed.preciseConditionalEntries().size());
        PreciseConditionalEntry precise = parsed.preciseConditionalEntries().getFirst();
        Assert.assertEquals("POST_HIGH_TIER", precise.stage());
        Assert.assertArrayEquals(new int[]{17, 23}, precise.successorBcis());
        Assert.assertEquals(4, statistics.recordedEvents());
    }

    /* A loop header's peeled guard and its in-loop exit are two physical copies of one bytecode branch. */
    @Test
    public void sameSuccessorPhysicalCopiesSumIntoOneLegacyEntry() throws Exception {
        String[] methods = {"Lexample/LoopTest;.scan()V"};
        int[] bcis = {27};
        BranchProfileCounter guard = BranchProfileRecorder.create("POST_HIGH_TIER", methods, bcis, 71, 30, "IntegerLessThanNode", 5L, 0);
        BranchProfileCounter loopExit = BranchProfileRecorder.create("POST_HIGH_TIER", methods, bcis, 71, 30, "IntegerLessThanNode", 6L, 1);
        BranchProfileCounter negated = BranchProfileRecorder.create("POST_HIGH_TIER", methods, bcis, 30, 71, "IntegerLessThanNode", 7L, 0);
        BranchProfileRecorder.incrementHosted(guard.getCounterIndex(), false);           // entered once (71 = exit, 30 = body)
        for (int i = 0; i < 9; i++) {
            BranchProfileRecorder.incrementHosted(loopExit.getCounterIndex(), false);  // 9 more iterations
        }
        BranchProfileRecorder.incrementHosted(loopExit.getCounterIndex(), true);       // 1 exit
        BranchProfileRecorder.incrementHosted(negated.getCounterIndex(), true);        // swapped successors: true = body

        StringWriter output = new StringWriter();
        BranchProfileIprofWriter.DumpStatistics statistics = BranchProfileIprofWriter.write(output, List.of(guard, loopExit, negated));
        ParsedProfile parsed = new IprofConditionalParser().parse(new StringReader(output.toString()));

        Assert.assertEquals(1, statistics.conditionalProfiles());
        Assert.assertEquals(3, statistics.preciseConditionalProfiles());
        long[] records = parsed.conditionalEntries().getFirst().records();
        java.util.Map<Long, Long> countByBci = java.util.Map.of(records[0], records[2], records[3], records[5]);
        Assert.assertEquals(Long.valueOf(1), countByBci.get(71L));
        Assert.assertEquals(Long.valueOf(11), countByBci.get(30L));
    }

    /* Sampled stacks are written innermost first; hidden-class names round-trip through the binary name form. */
    @Test
    public void samplingProfilesRoundTripIncludingHiddenClassNames() throws Exception {
        BranchProfileCounter counter = BranchProfileRecorder.lookup(new String[]{"Lexample/SampleTest;.work()V"}, new int[]{3}, 5, 9);
        BranchProfileRecorder.incrementHosted(counter.getCounterIndex(), true);
        String lambdaRun = "Lexample/SampleTest$$Lambda.0xabc123;.run()V";
        StackSampleRecorder.DecodedSample sample = new StackSampleRecorder.DecodedSample(
                        new String[]{"Lexample/SampleTest;.work()V", lambdaRun}, new int[]{12, 4}, 77);

        StringWriter output = new StringWriter();
        BranchProfileIprofWriter.write(output, List.of(counter), List.of(sample));
        String json = output.toString();
        Assert.assertTrue(json.contains("\"samplingProfiles\""));
        Assert.assertTrue("binary name uses a slash inside the hidden class name", json.contains("example.SampleTest$$Lambda/0xabc123"));

        ParsedProfile parsed = new IprofConditionalParser().parse(new StringReader(json));
        Assert.assertEquals(1, parsed.samplingEntries().size());
        IprofConditionalParser.SamplingEntry entry = parsed.samplingEntries().getFirst();
        Assert.assertEquals(77, entry.count());
        Assert.assertEquals(2, entry.context().size());
        Assert.assertEquals(12, entry.context().get(0).bci());
        IprofConditionalParser.MethodDescriptor caller = parsed.methodsById().get(entry.context().get(1).methodId());
        Assert.assertEquals(lambdaRun, com.oracle.svm.hosted.pgo.profiles.ConditionalProfileContextResolver.descriptorForProfileMethod(caller, parsed.typeNamesById()));
    }

    @Test
    public void conflictingPhysicalSitesRemainDistinctAndAreOmittedFromLegacyOutput() throws Exception {
        String[] methods = {"Lexample/CollisionTest;.branch()V"};
        int[] bcis = {12};
        BranchProfileCounter first = BranchProfileRecorder.create("POST_HIGH_TIER", methods, bcis, 20, 40, "IntegerEqualsNode", 11L, 0);
        BranchProfileCounter second = BranchProfileRecorder.create("POST_HIGH_TIER", methods, bcis, 30, 50, "IntegerEqualsNode", 22L, 0);
        BranchProfileRecorder.incrementHosted(first.getCounterIndex(), true);
        BranchProfileRecorder.incrementHosted(second.getCounterIndex(), false);

        StringWriter output = new StringWriter();
        BranchProfileIprofWriter.DumpStatistics statistics = BranchProfileIprofWriter.write(output, List.of(first, second));
        ParsedProfile parsed = new IprofConditionalParser().parse(new StringReader(output.toString()));

        Assert.assertEquals(0, statistics.conditionalProfiles());
        Assert.assertEquals(2, statistics.preciseConditionalProfiles());
        Assert.assertTrue(parsed.conditionalEntries().isEmpty());
        Assert.assertEquals(2, parsed.preciseConditionalEntries().size());
        Assert.assertArrayEquals(new int[]{20, 40}, parsed.preciseConditionalEntries().get(0).successorBcis());
        Assert.assertArrayEquals(new int[]{30, 50}, parsed.preciseConditionalEntries().get(1).successorBcis());
    }

    @Test
    public void differentConditionsWithUnknownSuccessorsGetNoLegacyEntry() throws Exception {
        /* Branches inside an inlined intrinsic: the successors carry no bytecode position. */
        String[] methods = {"Ljava/lang/String;.equals(Ljava/lang/Object;)Z", "Lexample/UnknownSuccessorTest;.parse()V"};
        int[] bcis = {-1, 300};
        BranchProfileCounter lengthCheck = BranchProfileRecorder.create("POST_HIGH_TIER", methods, bcis, -1, -1, "IntegerEqualsNode", 1L, 0);
        BranchProfileCounter nullCheck = BranchProfileRecorder.create("POST_HIGH_TIER", methods, bcis, -1, -1, "IsNullNode", 2L, 0);
        for (int i = 0; i < 3; i++) {
            BranchProfileRecorder.incrementHosted(lengthCheck.getCounterIndex(), true);
            BranchProfileRecorder.incrementHosted(nullCheck.getCounterIndex(), false);
        }

        StringWriter output = new StringWriter();
        BranchProfileIprofWriter.DumpStatistics statistics = BranchProfileIprofWriter.write(output, List.of(lengthCheck, nullCheck));
        ParsedProfile parsed = new IprofConditionalParser().parse(new StringReader(output.toString()));

        /* Summing them would say 3:3 for a branch that is really 3:0 and another that is 0:3. */
        Assert.assertEquals(0, statistics.conditionalProfiles());
        Assert.assertEquals(2, statistics.preciseConditionalProfiles());
        Assert.assertTrue(parsed.conditionalEntries().isEmpty());
    }

    @Test
    public void sameConditionCopiesWithUnknownSuccessorsStillSum() throws Exception {
        String[] methods = {"Ljava/lang/String;.equals(Ljava/lang/Object;)Z", "Lexample/UnknownSuccessorTest;.scan()V"};
        int[] bcis = {-1, 44};
        BranchProfileCounter first = BranchProfileRecorder.create("POST_HIGH_TIER", methods, bcis, -1, -1, "IntegerEqualsNode", 7L, 0);
        BranchProfileCounter copy = BranchProfileRecorder.create("POST_HIGH_TIER", methods, bcis, -1, -1, "IntegerEqualsNode", 7L, 1);
        BranchProfileRecorder.incrementHosted(first.getCounterIndex(), true);
        BranchProfileRecorder.incrementHosted(copy.getCounterIndex(), true);
        BranchProfileRecorder.incrementHosted(copy.getCounterIndex(), false);

        StringWriter output = new StringWriter();
        BranchProfileIprofWriter.DumpStatistics statistics = BranchProfileIprofWriter.write(output, List.of(first, copy));
        ParsedProfile parsed = new IprofConditionalParser().parse(new StringReader(output.toString()));

        Assert.assertEquals(1, statistics.conditionalProfiles());
        Assert.assertEquals(1, parsed.conditionalEntries().size());
    }

    @Test
    public void identicalPhysicalSiteIdentitiesAggregateDeterministically() throws Exception {
        String[] methods = {"Lexample/AggregateTest;.branch()V"};
        int[] bcis = {7};
        BranchProfileCounter first = BranchProfileRecorder.create("ROOT_PRE_INLINE", methods, bcis, 10, 20, "IntegerLessThanNode", 33L, 0);
        BranchProfileCounter second = BranchProfileRecorder.create("ROOT_PRE_INLINE", methods, bcis, 10, 20, "IntegerLessThanNode", 33L, 0);
        BranchProfileRecorder.incrementHosted(first.getCounterIndex(), true);
        BranchProfileRecorder.incrementHosted(second.getCounterIndex(), true);
        BranchProfileRecorder.incrementHosted(second.getCounterIndex(), false);

        StringWriter output = new StringWriter();
        BranchProfileIprofWriter.DumpStatistics statistics = BranchProfileIprofWriter.write(output, List.of(first, second));
        ParsedProfile parsed = new IprofConditionalParser().parse(new StringReader(output.toString()));

        Assert.assertEquals(1, statistics.conditionalProfiles());
        Assert.assertEquals(1, statistics.preciseConditionalProfiles());
        Assert.assertArrayEquals(new long[]{10, 0, 2, 20, 1, 1}, parsed.preciseConditionalEntries().getFirst().records());
    }

    @Test
    public void outputIsDeterministicAndOmitsUnexecutedCounters() throws Exception {
        BranchProfileCounter active = BranchProfileRecorder.lookup(
                        new String[]{"Lexample/DeterminismTest;.active()I"}, new int[]{7}, 10, 20);
        BranchProfileCounter inactive = BranchProfileRecorder.lookup(
                        new String[]{"Lexample/DeterminismTest;.inactive()I"}, new int[]{8}, 30, 40);
        BranchProfileRecorder.incrementHosted(active.getCounterIndex(), false);

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

    @Test
    public void monitorProfilesRoundTripThroughConsumerParser() throws Exception {
        StringWriter output = new StringWriter();
        BranchProfileIprofWriter.DumpStatistics statistics = BranchProfileIprofWriter.write(output, List.of(), List.of(), List.of(), List.of(), List.of(),
                        Map.of("Ljava/lang/String;", 7L, "Ljava/lang/Integer;", 3L));
        ParsedProfile parsed = new IprofConditionalParser().parse(new StringReader(output.toString()));

        Assert.assertTrue(parsed.monitorProfilesRecorded());
        Assert.assertEquals(1, parsed.monitorEntries().size());
        IprofConditionalParser.MonitorEntry entry = parsed.monitorEntries().getFirst();
        Map<String, Long> counts = new java.util.HashMap<>();
        for (int i = 0; i < entry.records().length; i += 2) {
            counts.put(parsed.typeNamesById().get((int) entry.records()[i]), entry.records()[i + 1]);
        }
        Assert.assertEquals(Map.of("java.lang.String", 7L, "java.lang.Integer", 3L), counts);
        Assert.assertEquals(2, statistics.monitorTypes());
        Assert.assertEquals(10, statistics.monitorEvents());
    }

    @Test
    public void emptyMonitorProfilesSectionPreservesRecordedCategory() throws Exception {
        StringWriter output = new StringWriter();
        BranchProfileIprofWriter.DumpStatistics statistics = BranchProfileIprofWriter.write(output, List.of(), List.of(), List.of(), List.of(), List.of(), Map.of());
        ParsedProfile parsed = new IprofConditionalParser().parse(new StringReader(output.toString()));

        Assert.assertTrue(parsed.monitorProfilesRecorded());
        Assert.assertTrue(parsed.monitorEntries().isEmpty());
        Assert.assertEquals(0, statistics.monitorTypes());
        Assert.assertEquals(0, statistics.monitorEvents());
    }

    @Test
    public void receiverRecorderSeparatesMonitorAndInvokeTypes() {
        String method = "Lexample/RecorderSeparationTest;.invoke()V";
        int receiverTypeId = 0x6f000001;
        int monitorTypeId = 0x6f000002;
        ReceiverProfileSite receiverSite = ReceiverProfileRecorder.createSite(new String[]{method}, new int[]{17}, new int[]{receiverTypeId}, new String[]{"Lexample/InvokeType;"});
        ReceiverProfileSite monitorSite = ReceiverProfileRecorder.createMonitorSite();
        Assert.assertSame(monitorSite, ReceiverProfileRecorder.createMonitorSite());
        ReceiverProfileRecorder.registerTypeDescriptor(monitorTypeId, "Lexample/MonitorType;");
        ReceiverProfileRecorder.recordType(receiverSite.siteIndex(), receiverTypeId);
        ReceiverProfileRecorder.recordType(receiverSite.siteIndex(), receiverTypeId);
        ReceiverProfileRecorder.recordType(monitorSite.siteIndex(), monitorTypeId);
        ReceiverProfileRecorder.recordType(monitorSite.siteIndex(), monitorTypeId);
        ReceiverProfileRecorder.recordType(monitorSite.siteIndex(), monitorTypeId);

        ReceiverProfileRecorder.DecodedReceiverProfile receivers = ReceiverProfileRecorder.decodeProfiles().stream()
                        .filter(profile -> profile.methodDescriptors().length == 1 && method.equals(profile.methodDescriptors()[0]))
                        .findFirst().orElseThrow();
        Map<String, Long> monitors = ReceiverProfileRecorder.decodeMonitorProfiles();
        Assert.assertEquals(Map.of("Lexample/InvokeType;", 2L), receivers.countsByTypeDescriptor());
        Assert.assertEquals(Long.valueOf(3), monitors.get("Lexample/MonitorType;"));
        Assert.assertFalse(receivers.countsByTypeDescriptor().containsKey("Lexample/MonitorType;"));
        Assert.assertFalse(monitors.containsKey("Lexample/InvokeType;"));
    }

    @Test
    public void receiverProfilesRoundTripThroughConsumerParser() throws Exception {
        BranchProfileCounter counter = BranchProfileRecorder.lookup(new String[]{"Lexample/ReceiverTest;.run()V"}, new int[]{3}, 5, 9);
        ReceiverProfileRecorder.DecodedReceiverProfile receivers = new ReceiverProfileRecorder.DecodedReceiverProfile(
                        new String[]{"Lexample/ReceiverTest;.run()V"}, new int[]{17}, Map.of("Ljava/lang/String;", 7L, "Ljava/lang/Integer;", 3L));

        StringWriter output = new StringWriter();
        BranchProfileIprofWriter.DumpStatistics statistics = BranchProfileIprofWriter.write(output, List.of(counter), List.of(), List.of(receivers));
        ParsedProfile parsed = new IprofConditionalParser().parse(new StringReader(output.toString()));

        Assert.assertEquals(1, statistics.receiverProfiles());
        Assert.assertEquals(1, parsed.virtualInvokeEntries().size());
        IprofConditionalParser.VirtualInvokeEntry entry = parsed.virtualInvokeEntries().getFirst();
        Map<String, Long> counts = new java.util.HashMap<>();
        for (int i = 0; i < entry.records().length; i += 2) {
            counts.put(parsed.typeNamesById().get((int) entry.records()[i]), entry.records()[i + 1]);
        }
        Assert.assertEquals(Map.of("java.lang.String", 7L, "java.lang.Integer", 3L), counts);
    }

    @Test
    public void callCountProfilesRoundTripAndAggregatePhysicalCopies() throws Exception {
        String[] methods = {"Lexample/CallCountTest;.callee()V", "Lexample/CallCountTest;.caller()V"};
        int[] bcis = {0, 14};
        CallCountProfileCounter first = CallCountProfileRecorder.create(methods, bcis);
        CallCountProfileCounter second = CallCountProfileRecorder.create(methods, bcis);
        CallCountProfileRecorder.increment(first.counterIndex());
        CallCountProfileRecorder.increment(second.counterIndex());
        CallCountProfileRecorder.increment(second.counterIndex());
        CallCountProfileRecorder.refreshHostedSnapshotForTesting();

        StringWriter output = new StringWriter();
        BranchProfileIprofWriter.DumpStatistics statistics = BranchProfileIprofWriter.write(output, List.of(), List.of(), List.of(), List.of(first, second));
        ParsedProfile parsed = new IprofConditionalParser().parse(new StringReader(output.toString()));

        Assert.assertEquals(1, statistics.callCountProfiles());
        Assert.assertEquals(1, parsed.callCountEntries().size());
        Assert.assertEquals(3, parsed.callCountEntries().getFirst().count());
        Assert.assertEquals(2, parsed.callCountEntries().getFirst().context().size());
    }

    @Test
    public void switchSuccessorsRoundTripThroughConditionalProfiles() throws Exception {
        SwitchProfileCounter profile = SwitchProfileRecorder.create(new String[]{"Lexample/SwitchTest;.choose(I)I"}, new int[]{4}, new int[]{10, 20, 30});
        CallCountProfileRecorder.increment(profile.counterIndex(0));
        CallCountProfileRecorder.increment(profile.counterIndex(1));
        CallCountProfileRecorder.increment(profile.counterIndex(1));
        CallCountProfileRecorder.increment(profile.counterIndex(2));
        CallCountProfileRecorder.increment(profile.counterIndex(2));
        CallCountProfileRecorder.increment(profile.counterIndex(2));
        CallCountProfileRecorder.refreshHostedSnapshotForTesting();

        StringWriter output = new StringWriter();
        BranchProfileIprofWriter.DumpStatistics statistics = BranchProfileIprofWriter.write(output, List.of(), List.of(), List.of(), List.of(), List.of(profile));
        ParsedProfile parsed = new IprofConditionalParser().parse(new StringReader(output.toString()));

        Assert.assertEquals(1, statistics.switchProfiles());
        Assert.assertEquals(1, parsed.conditionalEntries().size());
        Assert.assertArrayEquals(new long[]{10, 0, 1, 20, 1, 2, 30, 2, 3}, parsed.conditionalEntries().getFirst().records());
    }

    @Test
    public void rewiredSwitchCopiesAreOmittedAsAmbiguous() throws Exception {
        String[] methods = {"Lexample/AmbiguousSwitchTest;.choose(I)I"};
        int[] bcis = {4};
        SwitchProfileCounter first = SwitchProfileRecorder.create(methods, bcis, new int[]{10, 20, 30});
        SwitchProfileCounter rewired = SwitchProfileRecorder.create(methods, bcis, new int[]{10, 25, 30});
        CallCountProfileRecorder.increment(first.counterIndex(0));
        CallCountProfileRecorder.increment(rewired.counterIndex(0));
        CallCountProfileRecorder.refreshHostedSnapshotForTesting();

        StringWriter output = new StringWriter();
        BranchProfileIprofWriter.DumpStatistics statistics = BranchProfileIprofWriter.write(output, List.of(), List.of(), List.of(), List.of(), List.of(first, rewired));
        ParsedProfile parsed = new IprofConditionalParser().parse(new StringReader(output.toString()));

        Assert.assertEquals(0, statistics.switchProfiles());
        Assert.assertTrue(parsed.conditionalEntries().isEmpty());
    }
}
