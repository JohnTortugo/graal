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
package pgoworkload;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.graalvm.collections.EconomicMap;

import jdk.graal.compiler.graphio.parsing.BinaryReader;
import jdk.graal.compiler.graphio.parsing.ModelBuilder;
import jdk.graal.compiler.graphio.parsing.StreamSource;
import jdk.graal.compiler.graphio.parsing.model.Folder;
import jdk.graal.compiler.graphio.parsing.model.FolderElement;
import jdk.graal.compiler.graphio.parsing.model.GraphDocument;
import jdk.graal.compiler.graphio.parsing.model.InputEdge;
import jdk.graal.compiler.graphio.parsing.model.InputGraph;
import jdk.graal.compiler.graphio.parsing.model.InputNode;
import jdk.graal.compiler.nodes.extended.BranchProbabilityNode;
import jdk.graal.compiler.util.json.JsonParser;
import jdk.vm.ci.code.BytecodeFrame;

/**
 * Checks what PGO did to the compiled graphs of {@link PgoWorkload}, from the graph dumps a build
 * wrote with {@code -H:Dump=:1 -H:MethodFilter=PgoWorkload.*}. Three modes:
 *
 * <ul>
 * <li>{@code profiled <dumpDir> <iprof> <records> <circles> <squares> <triangles>}: every branch
 * probability the compiler marked as profiled equals what the profile file records for that
 * branch (through the same context lookup the consumer performs); the profile file itself records
 * exactly the branch, switch, receiver, and call-edge counts the program reports; the monomorphic
 * interface call is guarded and direct; and both workload switches carry their recorded
 * distributions.</li>
 * <li>{@code unprofiled <dumpDir>}: no branch carries a profiled probability and the interface call
 * stays an interface call.</li>
 * <li>{@code instrumented <dumpDir>}: right after the last instrumentation phase that runs before
 * the inliner, the graphs carry the counter nodes.</li>
 * </ul>
 *
 * The {@code records}, {@code circles}, {@code squares} and {@code triangles} arguments are what
 * the program printed for the training input, so the profile is checked against ground truth.
 */
public final class PgoEffectsCheck {
    private static final double TOLERANCE = 1e-6;

    private static final Pattern PROBABILITY = Pattern.compile("^(\\w+) designatedSuccessorProbability: (\\S+)$");
    private static final Pattern KEY_PROBABILITIES = Pattern.compile("^(\\w+) keyProbabilities: \\[(.*)\\]$");
    /** {@code pkg.Class.method(File.java:12) [bci:34]}; a frame of a node source position. */
    private static final Pattern FRAME = Pattern.compile("^(\\S+)\\(.*\\) \\[bci:(-?\\d+)\\]$");
    /** A frame of an inlined {@code area()} implementation; group 1 is the shape type. */
    private static final Pattern AREA_FRAME = Pattern.compile("^pgoworkload\\.PgoWorkload\\$(\\w+)\\.area:-?\\d+$");
    /**
     * Instrumentation runs before the inliner and later phases add and fold branches, so the
     * instrumented image is checked right after its last instrumentation phase. The gate dumps the
     * instrumentation phases at level 3 ({@code -H:Dump=:1,ProfileInstrumentation:3}).
     */
    private static final String AFTER_INSTRUMENTATION = "After phase CallCountProfileInstrumentation";

    private final List<String> failures = new ArrayList<>();
    private final Map<String, Integer> checked = new TreeMap<>();

    public static void main(String[] args) throws IOException {
        PgoEffectsCheck check = new PgoEffectsCheck();
        String mode = args[0];
        Map<String, InputGraph> graphs = check.loadGraphs(Path.of(args[1]), mode.equals("instrumented") ? AFTER_INSTRUMENTATION : "After high tier");
        switch (mode) {
            case "profiled" -> check.checkProfiled(args, graphs, true, true, true);
            case "profiled-receivers-disabled" -> check.checkProfiled(args, graphs, false, true, true);
            case "profiled-switches-disabled" -> check.checkProfiled(args, graphs, true, false, true);
            case "profiled-branches-disabled" -> check.checkBranchesDisabled(args, graphs);
            case "unprofiled" -> {
                check.checkNoProfiledBranches(graphs);
                check.checkDevirtualized(graphs, false);
            }
            case "instrumented" -> check.checkInstrumentation(graphs);
            default -> throw new IllegalArgumentException("unknown mode " + mode);
        }
        System.out.println("PGO effects (" + mode + "): checked " + check.checked);
        if (!check.failures.isEmpty()) {
            System.out.println(check.failures.size() + " check(s) failed:");
            check.failures.forEach(f -> System.out.println("  " + f));
            System.exit(1);
        }
    }

    private void fail(String message) {
        failures.add(message);
    }

    private void count(String what) {
        checked.merge(what, 1, Integer::sum);
    }

    private void checkProfiled(String[] args, Map<String, InputGraph> graphs, boolean expectReceiverEffects, boolean expectSwitchEffects,
                    boolean expectRecordedSwitch) throws IOException {
        Profile profile = Profile.read(Path.of(args[2]));
        long records = Long.parseLong(args[3]);
        long circles = Long.parseLong(args[4]);
        long squares = Long.parseLong(args[5]);
        long triangles = Long.parseLong(args[6]);
        long splitCalls = Long.parseLong(args[7]);
        long switchZero = Long.parseLong(args[8]);
        long switchOne = Long.parseLong(args[9]);
        long switchTwo = Long.parseLong(args[10]);
        long threadTrue = Long.parseLong(args[11]);
        long threadFalse = Long.parseLong(args[12]);
        long liveTrue = Long.parseLong(args[13]);
        long liveFalse = Long.parseLong(args[14]);
        checkProfiledBranches(graphs, profile);
        checkGroundTruth(profile, records, circles, squares, triangles, splitCalls, switchZero, switchOne, switchTwo, threadTrue, threadFalse, liveTrue, liveFalse, expectRecordedSwitch);
        checkDistributionBranches(graphs, records, circles, squares, triangles);
        checkDevirtualized(graphs, expectReceiverEffects);
        if (expectSwitchEffects) {
            checkSwitch(graphs, profile);
        } else {
            checkNoProfiledSwitches(graphs);
        }
    }

    // ------------------------------------------------------------------ graph access

    /** Loads the named graph of every compiled workload method; keyed by {@code Class.method}. */
    private Map<String, InputGraph> loadGraphs(Path dumpDir, String graphName) throws IOException {
        Map<String, InputGraph> result = new LinkedHashMap<>();
        try (Stream<Path> files = Files.list(dumpDir)) {
            for (Path file : files.sorted().toList()) {
                String name = file.getFileName().toString();
                if (!name.startsWith("SubstrateHostedCompilation-") || !name.endsWith(".bgv")) {
                    continue;
                }
                String method = name.substring(name.indexOf('[') + 1, name.indexOf('('));
                GraphDocument document = new GraphDocument();
                try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
                    new BinaryReader(new StreamSource(in), new ModelBuilder(document, null)).parse();
                }
                List<InputGraph> graphs = new ArrayList<>();
                collect(document, graphs);
                graphs.stream().filter(g -> g.getName().endsWith(graphName)).reduce((a, b) -> b).ifPresent(g -> result.put(method, g));
            }
        }
        if (result.isEmpty()) {
            fail("no '" + graphName + "' graph dumps found in " + dumpDir);
        }
        return result;
    }

    private static void collect(Folder folder, List<InputGraph> out) {
        for (FolderElement element : folder.getElements()) {
            if (element instanceof InputGraph graph) {
                out.add(graph);
            }
            if (element instanceof Folder sub) {
                collect(sub, out);
            }
        }
    }

    private static String nodeClass(InputNode node) {
        String name = String.valueOf(node.getProperties().get("class"));
        return name.substring(name.lastIndexOf('.') + 1);
    }

    private static String property(InputNode node, String name) {
        Object value = node.getProperties().get(name);
        return value == null ? null : String.valueOf(value);
    }

    private static double frequency(InputNode node) {
        String value = property(node, "relativeFrequency");
        return value == null ? Double.NaN : Double.parseDouble(value);
    }

    /** The node's inlining context as {@code Class.method:bci} frames, innermost first. */
    private static List<String> context(InputNode node) {
        String position = property(node, "nodeSourcePosition");
        if (position == null) {
            return List.of();
        }
        List<String> frames = new ArrayList<>();
        for (String line : position.split("\n")) {
            Matcher matcher = FRAME.matcher(line.trim());
            if (matcher.matches()) {
                frames.add(matcher.group(1) + ":" + matcher.group(2));
            }
        }
        return frames;
    }

    private static int bci(InputNode node) {
        List<String> context = context(node);
        return context.isEmpty() ? Integer.MIN_VALUE : Integer.parseInt(context.getFirst().substring(context.getFirst().lastIndexOf(':') + 1));
    }

    private static Map<Integer, InputNode> nodesById(InputGraph graph) {
        Map<Integer, InputNode> nodes = new HashMap<>();
        for (InputNode node : graph.getNodes()) {
            nodes.put(node.getId(), node);
        }
        return nodes;
    }

    private static InputNode successor(InputGraph graph, Map<Integer, InputNode> nodes, InputNode from, String label) {
        for (InputEdge edge : graph.getEdges()) {
            if (edge.getFrom() == from.getId() && edge.getLabel().equals(label)) {
                return nodes.get(edge.getTo());
            }
        }
        return null;
    }

    private static InputNode callTarget(InputGraph graph, Map<Integer, InputNode> nodes, InputNode invoke) {
        for (InputEdge edge : graph.getEdges()) {
            if (edge.getTo() == invoke.getId() && edge.getLabel().equals("callTarget")) {
                return nodes.get(edge.getFrom());
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ the profile file

    /** The parts of an iprof file the checks need, with contexts as {@code Class.method:bci} chains. */
    private static final class Profile {
        /** Conditional entries: context chain to successor records [bci, index, count]*. */
        final Map<List<String>, List<long[]>> conditionals = new HashMap<>();
        /** Receiver profiles: context chain to type name to count. */
        final Map<List<String>, Map<String, Long>> receivers = new HashMap<>();
        /** Exact target-first call contexts to their execution counts. */
        final Map<List<String>, Long> calls = new HashMap<>();

        @SuppressWarnings("unchecked")
        static Profile read(Path file) throws IOException {
            EconomicMap<String, Object> root = (EconomicMap<String, Object>) new JsonParser(Files.readString(file)).parse();
            Map<Long, String> types = new HashMap<>();
            for (Object type : (List<Object>) root.get("types")) {
                EconomicMap<String, Object> entry = (EconomicMap<String, Object>) type;
                types.put(((Number) entry.get("id")).longValue(), (String) entry.get("name"));
            }
            Map<Long, String> methods = new HashMap<>();
            for (Object method : (List<Object>) root.get("methods")) {
                EconomicMap<String, Object> entry = (EconomicMap<String, Object>) method;
                List<Object> signature = (List<Object>) entry.get("signature");
                String declaring = types.get(((Number) signature.getFirst()).longValue());
                methods.put(((Number) entry.get("id")).longValue(), declaring + "." + entry.get("name"));
            }
            Profile profile = new Profile();
            for (Object record : (List<Object>) root.get("conditionalProfiles")) {
                EconomicMap<String, Object> entry = (EconomicMap<String, Object>) record;
                profile.conditionals.computeIfAbsent(context(methods, (String) entry.get("ctx")), _ -> new ArrayList<>()).add(longs(entry.get("records")));
            }
            for (Object record : (List<Object>) section(root, "virtualInvokeProfiles")) {
                EconomicMap<String, Object> entry = (EconomicMap<String, Object>) record;
                long[] pairs = longs(entry.get("records"));
                Map<String, Long> byType = new HashMap<>();
                for (int i = 0; i < pairs.length; i += 2) {
                    byType.merge(types.get(pairs[i]), pairs[i + 1], Long::sum);
                }
                profile.receivers.put(context(methods, (String) entry.get("ctx")), byType);
            }
            for (Object record : (List<Object>) section(root, "callCountProfiles")) {
                EconomicMap<String, Object> entry = (EconomicMap<String, Object>) record;
                long[] counts = longs(entry.get("records"));
                if (counts.length != 1) {
                    throw new IllegalArgumentException("callCountProfiles entry must contain exactly one count");
                }
                profile.calls.merge(context(methods, (String) entry.get("ctx")), counts[0], Math::addExact);
            }
            return profile;
        }

        private static Object section(EconomicMap<String, Object> root, String name) {
            Object value = root.get(name);
            return value == null ? List.of() : value;
        }

        private static List<String> context(Map<Long, String> methods, String ctx) {
            List<String> frames = new ArrayList<>();
            for (String frame : ctx.split("<")) {
                int colon = frame.indexOf(':');
                frames.add(methods.get(Long.parseLong(frame.substring(0, colon))) + ":" + frame.substring(colon + 1));
            }
            return frames;
        }

        @SuppressWarnings("unchecked")
        private static long[] longs(Object list) {
            List<Object> values = (List<Object>) list;
            long[] result = new long[values.size()];
            for (int i = 0; i < result.length; i++) {
                result[i] = ((Number) values.get(i)).longValue();
            }
            return result;
        }

        /**
         * The records the consumer would use for this context: the exact chain, else the sum of all
         * entries whose chain starts with the longest matching part of it, outermost frames dropped
         * first (the shortened-context fallback, {@code IprofProfilesLookup}).
         */
        List<long[]> conditionalFor(List<String> context) {
            List<long[]> exact = conditionals.get(context);
            if (exact != null) {
                return exact;
            }
            for (int depth = context.size(); depth >= 1; depth--) {
                List<String> prefix = context.subList(0, depth);
                Map<Long, Long> counts = new LinkedHashMap<>();
                for (Map.Entry<List<String>, List<long[]>> entry : conditionals.entrySet()) {
                    if (entry.getKey().size() < depth || !entry.getKey().subList(0, depth).equals(prefix)) {
                        continue;
                    }
                    for (long[] records : entry.getValue()) {
                        for (int i = 0; i + 2 < records.length; i += 3) {
                            /* A record is (successor bci, successor index, count); bci and index identify the arm. */
                            counts.merge((records[i] << 32) | (records[i + 1] & 0xFFFF_FFFFL), records[i + 2], Long::sum);
                        }
                    }
                }
                if (!counts.isEmpty()) {
                    long[] sum = new long[counts.size() * 3];
                    int i = 0;
                    for (Map.Entry<Long, Long> arm : counts.entrySet()) {
                        sum[i++] = arm.getKey() >> 32;
                        sum[i++] = (int) (long) arm.getKey();
                        sum[i++] = arm.getValue();
                    }
                    return List.of(sum);
                }
            }
            return null;
        }
    }

    /**
     * The true-successor probability the consumer sets from a record on a branch whose successors
     * start at {@code trueBci} and {@code falseBci}, following
     * {@code PGOApplyProfilesPhase.aggregatedProbabilities} and its caller: each record entry gets
     * its key's share of the counts, zero shares are clamped, the last entry that names a valid
     * bytecode index gives that index its key's probability, and the successors that start at a
     * profiled index are set in order, true then false. So when both successors start at one index
     * (both entries name it), the false successor's entry is applied last and the true successor
     * gets the true key's share; when one successor has no index, the other carries the profile.
     * {@code null} when no successor starts at a profiled index.
     */
    private static Double expectedTrueProbability(long[] records, int trueBci, int falseBci) {
        int entries = records.length / 3;
        Map<Long, Long> countByKey = new HashMap<>();
        Map<Long, Integer> entriesByKey = new HashMap<>();
        long total = 0;
        for (int i = 0; i < records.length; i += 3) {
            countByKey.merge(records[i + 1], records[i + 2], Long::sum);
            entriesByKey.merge(records[i + 1], 1, Integer::sum);
            total += records[i + 2];
        }
        if (total == 0) {
            return null;
        }
        double[] shares = new double[entries];
        for (int e = 0; e < entries; e++) {
            long key = records[3 * e + 1];
            shares[e] = (double) countByKey.get(key) / (entriesByKey.get(key) * total);
        }
        long zeros = Arrays.stream(shares).filter(p -> p == 0.0).count();
        if (zeros > 0) {
            double adjustment = zeros * BranchProbabilityNode.EXTREMELY_SLOW_PATH_PROBABILITY;
            double scale = 1 / (1 - (entries - zeros) * BranchProbabilityNode.EXTREMELY_SLOW_PATH_PROBABILITY);
            for (int e = 0; e < entries; e++) {
                shares[e] = shares[e] == 0.0 ? BranchProbabilityNode.EXTREMELY_SLOW_PATH_PROBABILITY : shares[e] - adjustment * (shares[e] - BranchProbabilityNode.EXTREMELY_SLOW_PATH_PROBABILITY) * scale;
            }
        }
        Map<Long, Double> byKey = new HashMap<>();
        for (int e = 0; e < entries; e++) {
            byKey.merge(records[3 * e + 1], shares[e], Double::sum);
        }
        Map<Integer, Double> byBci = new HashMap<>();
        for (int e = 0; e < entries; e++) {
            if (records[3 * e] >= 0) {
                byBci.put((int) records[3 * e], byKey.get(records[3 * e + 1]));
            }
        }
        Double result = null;
        if (byBci.containsKey(trueBci)) {
            result = byBci.get(trueBci);
        }
        if (byBci.containsKey(falseBci)) {
            result = 1 - byBci.get(falseBci);
        }
        return result;
    }

    /** Per-successor-bci probabilities the consumer derives from a record, including its clamping of zero counts. */
    private static Map<Integer, Double> expectedProbabilities(long[] records) {
        Map<Integer, Long> countByBci = new LinkedHashMap<>();
        long total = 0;
        for (int i = 0; i < records.length; i += 3) {
            countByBci.merge((int) records[i], records[i + 2], Long::sum);
            total += records[i + 2];
        }
        if (total == 0) {
            return Map.of();
        }
        final long sum = total;
        double[] probabilities = countByBci.values().stream().mapToDouble(c -> (double) c / sum).toArray();
        long zeros = Arrays.stream(probabilities).filter(p -> p == 0.0).count();
        if (zeros > 0) {
            double adjustment = zeros * BranchProbabilityNode.EXTREMELY_SLOW_PATH_PROBABILITY;
            double scale = 1 / (1 - (probabilities.length - zeros) * BranchProbabilityNode.EXTREMELY_SLOW_PATH_PROBABILITY);
            for (int i = 0; i < probabilities.length; i++) {
                probabilities[i] = probabilities[i] == 0.0 ? BranchProbabilityNode.EXTREMELY_SLOW_PATH_PROBABILITY : probabilities[i] - adjustment * (probabilities[i] - BranchProbabilityNode.EXTREMELY_SLOW_PATH_PROBABILITY) * scale;
            }
        }
        Map<Integer, Double> result = new LinkedHashMap<>();
        int i = 0;
        for (Integer bci : countByBci.keySet()) {
            result.put(bci, probabilities[i++]);
        }
        return result;
    }

    // ------------------------------------------------------------------ checks

    /**
     * Every profiled branch probability is what the profile file records for that branch under the
     * consumer's context lookup, assigned to the right successor.
     */
    private void checkProfiledBranches(Map<String, InputGraph> graphs, Profile profile) {
        for (Map.Entry<String, InputGraph> entry : graphs.entrySet()) {
            InputGraph graph = entry.getValue();
            Map<Integer, InputNode> nodes = nodesById(graph);
            for (InputNode node : graph.getNodes()) {
                if (!nodeClass(node).equals("IfNode")) {
                    continue;
                }
                Matcher matcher = PROBABILITY.matcher(String.valueOf(property(node, "profileData")));
                if (!matcher.matches() || !matcher.group(1).equals("PROFILED")) {
                    continue;
                }
                double probability = Double.parseDouble(matcher.group(2));
                List<String> context = context(node);
                String where = entry.getKey() + " If " + node.getId() + " at " + context;
                List<long[]> records = profile.conditionalFor(context);
                if (records == null) {
                    fail(where + ": profiled probability " + probability + " but the profile has no conditional record for this context");
                    continue;
                }
                InputNode trueSuccessor = successor(graph, nodes, node, "trueSuccessor");
                InputNode falseSuccessor = successor(graph, nodes, node, "falseSuccessor");
                int trueBci = trueSuccessor == null ? Integer.MIN_VALUE : bci(trueSuccessor);
                int falseBci = falseSuccessor == null ? Integer.MIN_VALUE : bci(falseSuccessor);
                if (trueBci < 0 && falseBci < 0) {
                    fail(where + ": profiled probability " + probability + " on a branch whose successors have no bytecode position (" + trueBci + ", " + falseBci +
                                    "); such a branch cannot be matched to a record and must keep its prior");
                    continue;
                }
                boolean matched = false;
                List<String> candidates = new ArrayList<>();
                for (long[] record : records) {
                    Double expected = expectedTrueProbability(record, trueBci, falseBci);
                    candidates.add(Arrays.toString(record) + " -> " + expected);
                    if (expected != null && Math.abs(expected - probability) <= TOLERANCE) {
                        matched = true;
                        break;
                    }
                }
                count("profiled branches matched to the profile");
                if (!matched) {
                    fail(where + ": probability " + probability + " of successor bci " + trueBci + " matches no record " + candidates);
                }
            }
        }
        if (checked.getOrDefault("profiled branches matched to the profile", 0) < 10) {
            fail("fewer than 10 profiled branches found in the workload graphs: " + checked);
        }
    }

    /** The profile file records exactly the counts the program itself reports for the training run. */
    private void checkGroundTruth(Profile profile, long records, long circles, long squares, long triangles, long splitCalls,
                    long switchZero, long switchOne, long switchTwo, long threadTrue, long threadFalse, long liveTrue, long liveFalse, boolean expectRecordedSwitch) {
        long shapeRounds = Math.max(1, records / 100);
        long circlesInMixed = circles - 64 * shapeRounds;
        expectBranchCounts(profile, "PgoWorkload.parseRecord", 1, records, "the end-of-input check: one exit after " + records + " records");
        expectBranchCounts(profile, "PgoWorkload.mixed", squares, triangles + circlesInMixed, "squares against the other shapes");
        expectBranchCounts(profile, "PgoWorkload.mixed", triangles, circlesInMixed, "triangles against circles");
        expectReceivers(profile, List.of("PgoWorkload.areaOf", "PgoWorkload.sumAreas"), Map.of("Circle", circles, "Square", squares, "Triangle", triangles));
        expectReceivers(profile, List.of("PgoWorkload.areaOf", "PgoWorkload.unitAreas"), Map.of(records % 2 == 0 ? "Square" : "Circle", 20L * records));
        expectReceivers(profile, List.of("PgoWorkload.threadedReceiver"), Map.of("Square", threadTrue + threadFalse, "Circle", liveTrue + liveFalse));
        expectCallCount(profile, "PgoWorkload.splitFields", splitCalls);
        expectCallCount(profile, "PgoWorkload.threadedBranch", threadTrue + threadFalse);
        expectBranchCounts(profile, "PgoWorkload.threadedBranch", threadTrue, threadFalse, "short-lived thread branch outcomes merged at thread exit");
        expectCallCount(profile, "PgoWorkload.liveSnapshotBranch", liveTrue + liveFalse);
        expectBranchCounts(profile, "PgoWorkload.liveSnapshotBranch", liveTrue, liveFalse, "live daemon-thread outcomes collected by the teardown snapshot");
        if (expectRecordedSwitch) {
            expectSwitchCounts(profile, "PgoWorkload.profiledSwitch", switchZero, switchOne, switchTwo);
        }
    }

    private void expectCallCount(Profile profile, String method, long expected) {
        count("recorded call-edge counts equal to the program's own counts");
        long recorded = 0;
        int entries = 0;
        for (Map.Entry<List<String>, Long> entry : profile.calls.entrySet()) {
            if (entry.getKey().getFirst().startsWith("pgoworkload." + method + ":0")) {
                recorded = Math.addExact(recorded, entry.getValue());
                entries++;
            }
        }
        if (entries == 0) {
            fail("no call-count record targets " + method);
        } else if (recorded != expected) {
            fail(method + " call-count records total " + recorded + " across " + entries + " contexts, but the program made " + expected + " calls");
        }
    }

    private void expectSwitchCounts(Profile profile, String method, long... expectedCounts) {
        count("recorded switch counts equal to the program's own counts");
        long[] expected = expectedCounts.clone();
        List<String> seen = new ArrayList<>();
        for (Map.Entry<List<String>, List<long[]>> entry : profile.conditionals.entrySet()) {
            if (!entry.getKey().getFirst().contains(method + ":")) {
                continue;
            }
            for (long[] records : entry.getValue()) {
                if (records.length != expected.length * 3) {
                    continue;
                }
                long[] actual = new long[expected.length];
                for (int i = 0; i < actual.length; i++) {
                    actual[i] = records[i * 3 + 2];
                }
                seen.add(Arrays.toString(actual));
                if (Arrays.equals(actual, expected)) {
                    return;
                }
            }
        }
        fail("no switch record in " + method + " has successor counts " + Arrays.toString(expected) + "; found " + seen);
    }

    private void expectBranchCounts(Profile profile, String method, long first, long second, String description) {
        count("recorded counts equal to the program's own counts");
        for (Map.Entry<List<String>, List<long[]>> entry : profile.conditionals.entrySet()) {
            if (!entry.getKey().getFirst().contains(method + ":")) {
                continue;
            }
            for (long[] record : entry.getValue()) {
                if (record.length == 6 && ((record[2] == first && record[5] == second) || (record[2] == second && record[5] == first))) {
                    return;
                }
            }
        }
        fail("no record in " + method + " has the counts {" + first + ", " + second + "} (" + description + ")");
    }

    private void expectReceivers(Profile profile, List<String> frames, Map<String, Long> expected) {
        count("recorded receivers equal to the program's own counts");
        /*
         * The same call can be recorded under several contexts: the method holding it may also be
         * inlined into its callers, and each expansion has its own counters. What the program
         * counted is the sum over every context that starts with the call's frames.
         */
        List<String> site = null;
        Map<String, Long> recorded = new TreeMap<>();
        for (Map.Entry<List<String>, Map<String, Long>> entry : profile.receivers.entrySet()) {
            List<String> context = entry.getKey();
            if (context.size() < frames.size()) {
                continue;
            }
            boolean matches = true;
            for (int i = 0; i < frames.size(); i++) {
                matches &= context.get(i).contains(frames.get(i) + ":");
            }
            if (!matches) {
                continue;
            }
            List<String> prefix = context.subList(0, frames.size());
            if (site == null) {
                site = List.copyOf(prefix);
            } else if (!site.equals(prefix)) {
                /* Another call in the same methods. */
                continue;
            }
            entry.getValue().forEach((type, n) -> recorded.merge(type.substring(type.lastIndexOf('$') + 1), n, Long::sum));
        }
        if (site == null) {
            fail("no receiver record for the call under " + frames);
        } else if (!recorded.equals(new TreeMap<>(expected))) {
            fail("receiver records under " + site + " sum to " + recorded + ", the program built " + expected);
        }
    }

    /**
     * The two profiled branches of the shape distribution carry the distribution the program built,
     * on the successor the source puts the squares and triangles on.
     */
    private void checkDistributionBranches(Map<String, InputGraph> graphs, long records, long circles, long squares, long triangles) {
        long shapeRounds = Math.max(1, records / 100);
        long circlesInMixed = circles - 64 * shapeRounds;
        double squareShare = (double) squares / (squares + triangles + circlesInMixed);
        double triangleShare = (double) triangles / (triangles + circlesInMixed);
        expectProfiledBranch(graphs, List.of("PgoWorkload.mixed"), squareShare, "the square share of the mixed shapes");
        expectProfiledBranch(graphs, List.of("PgoWorkload.mixed"), triangleShare, "the triangle share of the non-square mixed shapes");
        /*
         * parseRecord tests for the end of input and returns Long.MIN_VALUE, which main tests again.
         * Once parseRecord is inlined into main the two tests fold into one, at either position.
         */
        expectProfiledBranch(graphs, List.of("PgoWorkload.parseRecord", "PgoWorkload.main"), 1.0 / (records + 1), "the end-of-input exit");
    }

    /** A profiled branch of one of {@code methods} has {@code expected}, in its own graph or in one it was inlined into. */
    private void expectProfiledBranch(Map<String, InputGraph> graphs, List<String> methods, double expected, String description) {
        count("distribution branches with the program's own probability");
        List<String> seen = new ArrayList<>();
        for (InputGraph graph : graphs.values()) {
            for (InputNode node : graph.getNodes()) {
                if (!nodeClass(node).equals("IfNode") || context(node).isEmpty()) {
                    continue;
                }
                Matcher matcher = PROBABILITY.matcher(String.valueOf(property(node, "profileData")));
                String innermost = context(node).getFirst();
                if (!matcher.matches() || !matcher.group(1).equals("PROFILED") || methods.stream().noneMatch(method -> innermost.startsWith("pgoworkload." + method + ":"))) {
                    continue;
                }
                double probability = Double.parseDouble(matcher.group(2));
                seen.add(Double.toString(probability));
                if (Math.abs(probability - expected) <= TOLERANCE) {
                    return;
                }
            }
        }
        fail(methods + ": no profiled branch has probability " + expected + " (" + description + "); profiled branches: " + seen);
    }

    /**
     * With a monomorphic receiver profile the interface call in the hot loop is guarded and
     * devirtualized to the recorded implementation, which is then called directly or, at
     * {@code -O3}, inlined. Without one the recorded type is not singled out: the call stays an
     * interface call, or, at {@code -O3}, the inliner dispatches over every implementation the
     * closed-world analysis allows.
     */
    private void checkDevirtualized(Map<String, InputGraph> graphs, boolean profiled) {
        count("devirtualization of the monomorphic interface call");
        InputGraph graph = graphs.get("PgoWorkload.unitAreas");
        if (graph == null) {
            fail("no graph for PgoWorkload.unitAreas");
            return;
        }
        Map<Integer, InputNode> nodes = nodesById(graph);
        double hotInterface = 0;
        double hotDirect = 0;
        boolean inlinedImplementation = false;
        java.util.Set<String> inlinedTypes = new java.util.TreeSet<>();
        for (InputNode node : graph.getNodes()) {
            for (String frame : context(node)) {
                Matcher implementation = AREA_FRAME.matcher(frame);
                if (implementation.matches()) {
                    inlinedTypes.add(implementation.group(1));
                    inlinedImplementation |= implementation.group(1).equals("Square") || implementation.group(1).equals("Circle");
                }
            }
            if (!nodeClass(node).startsWith("Invoke")) {
                continue;
            }
            InputNode target = callTarget(graph, nodes, node);
            String method = target == null ? "" : String.valueOf(property(target, "targetMethod"));
            String kind = target == null ? "" : String.valueOf(property(target, "invokeKind"));
            double invokeFrequency = frequency(node);
            if ((method.endsWith("Shape.area()") || method.endsWith("Square.area()") || method.endsWith("Circle.area()")) && !Double.isFinite(invokeFrequency)) {
                fail("unitAreas: invoke " + node.getId() + " of " + method + " has no finite relativeFrequency metadata");
                continue;
            }
            if (method.endsWith("Shape.area()") && kind.endsWith("Interface")) {
                hotInterface = Math.max(hotInterface, invokeFrequency);
            } else if (method.endsWith("Square.area()") || method.endsWith("Circle.area()")) {
                hotDirect = Math.max(hotDirect, invokeFrequency);
            }
        }
        if (profiled) {
            if (hotDirect < 1000 && !inlinedImplementation) {
                fail("unitAreas: the recorded receiver's area() is neither called directly in the hot loop (max frequency " + hotDirect + ") nor inlined");
            }
            if (hotInterface > 1e-3) {
                fail("unitAreas: the interface call survives on a hot path (frequency " + hotInterface + ") despite a monomorphic receiver profile");
            }
        } else {
            if (hotInterface < 1000 && !inlinedTypes.containsAll(List.of("Circle", "Square", "Triangle"))) {
                fail("unitAreas: without a receiver profile the call is neither an interface call in the hot loop (max frequency " + hotInterface +
                                ") nor a dispatch over every area() implementation (inlined: " + inlinedTypes + ")");
            }
            if (hotDirect > 0) {
                fail("unitAreas: a direct call to an area() implementation (frequency " + hotDirect + ") appeared without a receiver profile");
            }
        }
    }

    /** Both switches carry distributions derived from their exact recorded successor counts. */
    private void checkSwitch(Map<String, InputGraph> graphs, Profile profile) {
        checkSwitch(graphs, profile, "parseStruct");
        checkSwitch(graphs, profile, "profiledSwitch");
    }

    private void checkSwitch(Map<String, InputGraph> graphs, Profile profile, String method) {
        count("profiled switch distributions");
        int matchedSwitches = 0;
        for (Map.Entry<String, InputGraph> graphEntry : graphs.entrySet()) {
            Map<Integer, InputNode> graphNodes = nodesById(graphEntry.getValue());
            for (InputNode node : graphEntry.getValue().getNodes()) {
                if (!nodeClass(node).equals("IntegerSwitchNode")) {
                    continue;
                }
                List<String> context = context(node);
                if (context.isEmpty() || !context.getFirst().startsWith("pgoworkload.PgoWorkload." + method + ":")) {
                    continue;
                }
                Matcher matcher = KEY_PROBABILITIES.matcher(String.valueOf(property(node, "profileData")));
                if (!matcher.matches() || !matcher.group(1).equals("PROFILED")) {
                    continue;
                }
                double[] keys = Arrays.stream(matcher.group(2).split(",\\s*")).mapToDouble(Double::parseDouble).toArray();
                List<long[]> records = profile.conditionalFor(context);
                if (records == null) {
                    fail(method + " switch " + node.getId() + " in " + graphEntry.getKey() + " is profiled but has no record at " + context);
                    continue;
                }
                /*
                 * The record counts per successor; the switch distributes a successor's share over
                 * the keys that lead to it. The dump lists the successor probabilities when the
                 * compiler computed them; otherwise some grouping of the keys must reproduce the
                 * record.
                 */
                Map<Integer, Double> successors = successorProbabilities(graphEntry.getValue(), node, graphNodes);
                if (successors == null) {
                    fail(method + " switch " + node.getId() + " in " + graphEntry.getKey() + " has no successor-probability cache for target-mapped validation");
                    continue;
                }
                boolean matched = false;
                boolean pruned = false;
                for (long[] record : records) {
                    Map<Integer, Double> expected = expectedProbabilities(record);
                    matched |= sameDistribution(successors, expected);
                    pruned |= successors.size() < expected.size() && expected.keySet().containsAll(successors.keySet());
                }
                if (!matched && pruned) {
                    /*
                     * A copy, for example in an unrolled loop, in which keys that cannot occur there
                     * were removed after the profile was applied; the remaining keys keep their
                     * shares, renormalized, so the distribution is no longer the record's.
                     */
                    count("profiled switch copies with unreachable keys removed");
                    continue;
                }
                matchedSwitches++;
                if (!matched) {
                    fail(method + " switch " + node.getId() + " in " + graphEntry.getKey() + ": key probabilities " + Arrays.toString(keys) + " (successors by bci " +
                                    successors + ") do not match the record " + records.stream().map(Arrays::toString).toList());
                }
            }
        }
        if (matchedSwitches == 0) {
            fail(method + ": no switch carries a profiled distribution");
        }
    }

    private void checkNoProfiledSwitches(Map<String, InputGraph> graphs) {
        count("disabled switch consumer leaves switch distributions unprofiled");
        Map<String, Boolean> found = new HashMap<>();
        found.put("parseStruct", false);
        found.put("profiledSwitch", false);
        for (Map.Entry<String, InputGraph> graphEntry : graphs.entrySet()) {
            for (InputNode node : graphEntry.getValue().getNodes()) {
                if (!nodeClass(node).equals("IntegerSwitchNode") || context(node).isEmpty()) {
                    continue;
                }
                for (String method : found.keySet()) {
                    if (!context(node).getFirst().startsWith("pgoworkload.PgoWorkload." + method + ":")) {
                        continue;
                    }
                    found.put(method, true);
                    String data = property(node, "profileData");
                    if (data != null && data.startsWith("PROFILED")) {
                        fail(method + " switch " + node.getId() + " in " + graphEntry.getKey() + " is profiled although switch consumption is disabled: " + data);
                    }
                }
            }
        }
        found.forEach((method, present) -> {
            if (!present) {
                fail(method + ": no switch found while checking disabled switch consumption");
            }
        });
    }

    /**
     * Successor bytecode index to probability from the dumped cache {@code (id|Name,probability)}.
     * The cache can keep entries for successors the switch had before it was copied or simplified;
     * only the switch's current successors count.
     */
    private static Map<Integer, Double> successorProbabilities(InputGraph graph, InputNode node, Map<Integer, InputNode> nodes) {
        String cache = property(node, "successorProbabilityCache");
        if (cache == null) {
            return null;
        }
        java.util.Set<Integer> current = new java.util.HashSet<>();
        for (InputEdge edge : graph.getEdges()) {
            if (edge.getFrom() == node.getId() && edge.getLabel().equals("successors")) {
                current.add(edge.getTo());
            }
        }
        Map<Integer, Double> byBci = new TreeMap<>();
        Matcher matcher = Pattern.compile("\\((\\d+)\\|[^,]*,([0-9.Ee+-]+)\\)").matcher(cache);
        while (matcher.find()) {
            int successorId = Integer.parseInt(matcher.group(1));
            if (!current.contains(successorId)) {
                continue;
            }
            InputNode successor = nodes.get(successorId);
            int successorBci = successor == null ? Integer.MIN_VALUE : bci(successor);
            double probability = Double.parseDouble(matcher.group(2));
            Double previous = successorBci < 0 ? null : byBci.putIfAbsent(successorBci, probability);
            if (successorBci < 0 || previous != null && Math.abs(previous - probability) > TOLERANCE) {
                return null;
            }
        }
        return byBci.isEmpty() ? null : byBci;
    }

    private static boolean sameDistribution(Map<Integer, Double> actual, Map<Integer, Double> expected) {
        if (!actual.keySet().equals(expected.keySet())) {
            return false;
        }
        for (Map.Entry<Integer, Double> entry : expected.entrySet()) {
            if (Math.abs(actual.get(entry.getKey()) - entry.getValue()) > TOLERANCE) {
                return false;
            }
        }
        return true;
    }

    /**
     * With {@code -H:-PGOUseBranchProfiles} no {@code IfNode} carries a profiled probability, while
     * the same build still applies the switch and receiver profiles.
     */
    private void checkBranchesDisabled(String[] args, Map<String, InputGraph> graphs) throws IOException {
        Profile profile = Profile.read(Path.of(args[2]));
        count("builds without profiled IfNode probabilities");
        int ifNodes = 0;
        for (Map.Entry<String, InputGraph> entry : graphs.entrySet()) {
            for (InputNode node : entry.getValue().getNodes()) {
                if (!nodeClass(node).equals("IfNode")) {
                    continue;
                }
                ifNodes++;
                String data = property(node, "profileData");
                if (data != null && data.startsWith("PROFILED")) {
                    fail(entry.getKey() + " If " + node.getId() + " at " + context(node) + " carries a profiled probability although branch profiles are off: " + data);
                }
            }
        }
        if (ifNodes < 10 || !hasCodeOf(graphs, "PgoWorkload.mixed")) {
            fail("branch-profile check inspected only " + ifNodes + " IfNodes or lacked the mixed graph");
        }
        checkDevirtualized(graphs, true);
        checkSwitch(graphs, profile);
    }

    private void checkNoProfiledBranches(Map<String, InputGraph> graphs) {
        count("unprofiled graphs without profiled branches");
        int controlSplits = 0;
        for (Map.Entry<String, InputGraph> entry : graphs.entrySet()) {
            for (InputNode node : entry.getValue().getNodes()) {
                if (nodeClass(node).equals("IfNode") || nodeClass(node).equals("IntegerSwitchNode")) {
                    controlSplits++;
                }
                String data = property(node, "profileData");
                if (data != null && data.startsWith("PROFILED")) {
                    fail(entry.getKey() + " node " + node.getId() + " carries a profiled probability in a build without a profile: " + data);
                }
            }
        }
        if (controlSplits < 10 || !hasCodeOf(graphs, "PgoWorkload.mixed") || !hasCodeOf(graphs, "PgoWorkload.parseRecord")) {
            fail("unprofiled check inspected only " + controlSplits + " control splits or lacked named workload graphs");
        }
    }

    /** Whether {@code method} was compiled, as its own graph or inlined into another one. */
    private static boolean hasCodeOf(Map<String, InputGraph> graphs, String method) {
        if (graphs.containsKey(method)) {
            return true;
        }
        for (InputGraph graph : graphs.values()) {
            for (InputNode node : graph.getNodes()) {
                for (String frame : context(node)) {
                    if (frame.startsWith("pgoworkload." + method + ":")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Right after the instrumentation phases, before the inliner: every branch the producer selects
     * owns a counter at the start of each successor and no other branch has one, every indirect
     * call has one receiver counter, and every graph with a direct call at least one call-count
     * counter. The producer selects a branch as {@code ProfilingUtilities.relevantConditionalSitesFromGraph}
     * does: not an implicit-exception check, and every frame of its position has a bytecode index.
     */
    private void checkInstrumentation(Map<String, InputGraph> graphs) {
        int counters = 0;
        for (Map.Entry<String, InputGraph> entry : graphs.entrySet()) {
            if (!entry.getKey().startsWith("PgoWorkload")) {
                /* A Native Image runtime root, dumped because the optimized build inlines main into it; the producer skips such roots. */
                continue;
            }
            InputGraph graph = entry.getValue();
            Map<Integer, InputNode> nodes = nodesById(graph);
            int branchCounters = 0;
            int siteBranches = 0;
            int receiverCounters = 0;
            int callCounters = 0;
            int indirectInvokes = 0;
            int directInvokes = 0;
            for (InputNode node : graph.getNodes()) {
                switch (nodeClass(node)) {
                    case "BranchProfileCounterNode" -> branchCounters++;
                    case "IfNode" -> {
                        InputNode trueNext = next(graph, nodes, successor(graph, nodes, node, "trueSuccessor"));
                        InputNode falseNext = next(graph, nodes, successor(graph, nodes, node, "falseSuccessor"));
                        boolean counted = isClass(trueNext, "BranchProfileCounterNode") && isClass(falseNext, "BranchProfileCounterNode");
                        boolean site = !isClass(trueNext, "BytecodeExceptionNode") && !isClass(falseNext, "BytecodeExceptionNode") && hasBytecodePositions(node);
                        if (site) {
                            siteBranches++;
                        }
                        if (site != counted) {
                            fail(entry.getKey() + " If " + node.getId() + " at " + context(node) + (site ? ": a profile site without a counter on both successors"
                                            : ": a counter on a branch the producer does not select"));
                        }
                    }
                    case "TypeProfileCounterNode" -> {
                        if (!Boolean.parseBoolean(property(node, "monitorProfile"))) {
                            receiverCounters++;
                        }
                    }
                    case "CallCountProfileCounterNode" -> callCounters++;
                    case "InvokeNode", "InvokeWithExceptionNode" -> {
                        InputNode target = callTarget(graph, nodes, node);
                        String kind = target == null ? "" : String.valueOf(property(target, "invokeKind"));
                        if (kind.endsWith("Interface") || kind.endsWith("Virtual")) {
                            indirectInvokes++;
                        } else {
                            directInvokes++;
                        }
                    }
                    default -> {
                    }
                }
            }
            counters += branchCounters;
            if (branchCounters != 2 * siteBranches) {
                fail(entry.getKey() + ": " + siteBranches + " profile-site branches but " + branchCounters + " branch counters; every site owns one per successor");
            }
            if (receiverCounters != indirectInvokes) {
                fail(entry.getKey() + ": " + indirectInvokes + " indirect calls but " + receiverCounters + " receiver counters");
            }
            if (callCounters < directInvokes) {
                fail(entry.getKey() + ": " + directInvokes + " direct calls but only " + callCounters + " call-count/switch counters");
            }
            count("instrumented graphs");
        }
        if (counters < 20) {
            fail("only " + counters + " branch counters in all workload graphs");
        }
    }

    private static InputNode next(InputGraph graph, Map<Integer, InputNode> nodes, InputNode node) {
        return node == null ? null : successor(graph, nodes, node, "next");
    }

    private static boolean isClass(InputNode node, String simpleName) {
        return node != null && nodeClass(node).equals(simpleName);
    }

    /**
     * The producer skips a branch whose position has a frame with {@link BytecodeFrame#UNKNOWN_BCI}
     * or {@link BytecodeFrame#INVALID_FRAMESTATE_BCI}.
     */
    private static boolean hasBytecodePositions(InputNode node) {
        List<String> context = context(node);
        String unknown = String.valueOf(BytecodeFrame.UNKNOWN_BCI);
        String invalid = String.valueOf(BytecodeFrame.INVALID_FRAMESTATE_BCI);
        return !context.isEmpty() && context.stream().map(frame -> frame.substring(frame.lastIndexOf(':') + 1)).noneMatch(bci -> bci.equals(unknown) || bci.equals(invalid));
    }
}
