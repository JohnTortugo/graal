/*
 * Copyright (c) 2026, Oracle and/or its affiliates. All rights reserved.
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
package jdk.graal.compiler.core.test;

import org.junit.Assert;
import org.junit.Test;

import jdk.graal.compiler.loop.phases.InjectLoopCounterStampsPhase;
import jdk.graal.compiler.loop.phases.LoopRangeCheckVersioningPhase;
import jdk.graal.compiler.nodes.IfNode;
import jdk.graal.compiler.nodes.LoopBeginNode;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.StructuredGraph.AllowAssumptions;
import jdk.graal.compiler.nodes.calc.IntegerBelowNode;
import jdk.graal.compiler.nodes.graphbuilderconf.GraphBuilderConfiguration;
import jdk.graal.compiler.nodes.graphbuilderconf.GraphBuilderConfiguration.BytecodeExceptionMode;
import jdk.graal.compiler.options.OptionValues;
import jdk.graal.compiler.phases.OptimisticOptimizations;
import jdk.graal.compiler.phases.common.CanonicalizerPhase;
import jdk.graal.compiler.phases.common.FloatingReadPhase;
import jdk.graal.compiler.phases.common.HighTierLoweringPhase;
import jdk.graal.compiler.phases.common.IterativeConditionalEliminationPhase;
import jdk.graal.compiler.phases.tiers.MidTierContext;

/**
 * Tests {@link LoopRangeCheckVersioningPhase} with explicit (fixed) bounds checks, the shape they
 * have in ahead-of-time compiled code: the versioned method must keep every exception semantics and
 * the check-free copy must not contain the hoisted checks.
 */
public class LoopRangeCheckVersioningTest extends GraalCompilerTest {

    @Override
    protected GraphBuilderConfiguration editGraphBuilderConfiguration(GraphBuilderConfiguration conf) {
        return super.editGraphBuilderConfiguration(conf).withBytecodeExceptionMode(BytecodeExceptionMode.CheckAll);
    }

    /**
     * Runs the mid-tier prefix up to and including the versioning phase and asserts the resulting
     * number of loops and of bounds checks (ifs on an unsigned comparison) in the whole graph.
     */
    private void assertStructure(String snippet, int expectedLoops, int expectedChecks, OptionValues options) {
        StructuredGraph graph = parseEager(getResolvedJavaMethod(snippet), AllowAssumptions.YES, options);
        CanonicalizerPhase canonicalizer = createCanonicalizerPhase();
        MidTierContext context = new MidTierContext(getProviders(), getTargetProvider(), OptimisticOptimizations.ALL, graph.getProfilingInfo());
        new HighTierLoweringPhase(canonicalizer).apply(graph, context);
        canonicalizer.apply(graph, context);
        new FloatingReadPhase(canonicalizer).apply(graph, context);
        new InjectLoopCounterStampsPhase().apply(graph, context);
        new IterativeConditionalEliminationPhase(canonicalizer, true).apply(graph, context);
        Assert.assertEquals("loops before", 1, graph.getNodes(LoopBeginNode.TYPE).count());
        new LoopRangeCheckVersioningPhase(canonicalizer).apply(graph, context);
        canonicalizer.apply(graph, context);
        Assert.assertEquals("loops", expectedLoops, graph.getNodes(LoopBeginNode.TYPE).count());
        Assert.assertEquals("bounds checks", expectedChecks, countChecks(graph));
    }

    private static long countChecks(StructuredGraph graph) {
        return graph.getNodes(IfNode.TYPE).filter(ifNode -> ((IfNode) ifNode).condition() instanceof IntegerBelowNode).count();
    }

    /** The parsed-only graphs of the structural tests have no profile of their own. */
    private OptionValues unprofiledOptions() {
        return new OptionValues(getInitialOptions(), LoopRangeCheckVersioningPhase.Options.LoopRangeCheckVersioningRequireProfile, false);
    }

    private void assertVersioned(String snippet, int remainingChecks) {
        assertStructure(snippet, 2, remainingChecks, unprofiledOptions());
    }

    public static int sumSnippet(int[] a, int n) {
        if (a == null) {
            return -1;
        }
        int sum = 0;
        for (int i = 0; i < n; i++) {
            sum += a[i];
        }
        return sum;
    }

    public static int offsetSnippet(int[] a, int n) {
        if (a == null) {
            return -1;
        }
        int sum = 0;
        for (int i = 0; i < n; i++) {
            sum += a[i + 1];
        }
        return sum;
    }

    public static int startSnippet(int[] a, int start, int n) {
        if (a == null) {
            return -1;
        }
        int sum = 0;
        for (int i = start; i < n; i++) {
            sum += a[i];
        }
        return sum;
    }

    public static int twoArraysSnippet(int[] a, int[] b, int n) {
        if (a == null || b == null) {
            return -1;
        }
        int sum = 0;
        for (int i = 0; i < n; i++) {
            sum += a[i] * b[i];
        }
        return sum;
    }

    public static int scaledSnippet(int[] a, int n) {
        if (a == null) {
            return -1;
        }
        int sum = 0;
        for (int i = 0; i < n; i++) {
            sum += a[2 * i];
        }
        return sum;
    }

    public static int reverseSnippet(int[] a, int n) {
        if (a == null) {
            return -1;
        }
        int sum = 0;
        for (int i = 0; i < n; i++) {
            sum += a[a.length - 1 - i];
        }
        return sum;
    }

    public static int shiftedLengthSnippet(byte[] value, int coder, int n) {
        if (value == null) {
            return -1;
        }
        int sum = 0;
        int length = value.length >> coder;
        for (int i = 0; i < n && i < length; i++) {
            sum += value[i];
        }
        return sum;
    }

    public static int indirectSnippet(int[] a, int[] idx, int n) {
        if (a == null || idx == null) {
            return -1;
        }
        int sum = 0;
        for (int i = 0; i < n; i++) {
            sum += a[idx[i]];
        }
        return sum;
    }

    public static int nullableSnippet(int[] a, int n) {
        int sum = 0;
        for (int i = 0; i < n; i++) {
            sum += a[i];
        }
        return sum;
    }

    public static int storeSnippet(int[] a, int n, int v) {
        if (a == null) {
            return -1;
        }
        for (int i = 0; i < n; i++) {
            a[i] = v;
        }
        return a.length;
    }

    private static int[] array(int length) {
        int[] a = new int[length];
        for (int i = 0; i < length; i++) {
            a[i] = i + 1;
        }
        return a;
    }


    @Test
    public void testSum() {
        assertVersioned("sumSnippet", 1);
        int[] a = array(100);
        test("sumSnippet", a, 100);
        test("sumSnippet", a, 50);
        test("sumSnippet", a, 0);
        test("sumSnippet", a, 101);
        test("sumSnippet", new int[0], 1);
    }

    @Test
    public void testOffset() {
        assertVersioned("offsetSnippet", 1);
        int[] a = array(100);
        test("offsetSnippet", a, 99);
        test("offsetSnippet", a, 100);
    }

    @Test
    public void testStart() {
        assertVersioned("startSnippet", 1);
        int[] a = array(100);
        test("startSnippet", a, 0, 100);
        test("startSnippet", a, 10, 100);
        test("startSnippet", a, -1, 100);
        test("startSnippet", a, 100, 50);
        test("startSnippet", a, Integer.MAX_VALUE - 2, Integer.MAX_VALUE);
        test("startSnippet", a, Integer.MIN_VALUE, Integer.MIN_VALUE + 3);
    }

    @Test
    public void testTwoArrays() {
        assertVersioned("twoArraysSnippet", 2);
        int[] a = array(100);
        test("twoArraysSnippet", a, array(100), 100);
        test("twoArraysSnippet", a, array(50), 100);
        test("twoArraysSnippet", array(50), a, 100);
    }

    @Test
    public void testScaled() {
        assertVersioned("scaledSnippet", 1);
        int[] a = array(100);
        test("scaledSnippet", a, 50);
        test("scaledSnippet", a, 51);
        test("scaledSnippet", a, Integer.MAX_VALUE / 2 + 2);
    }

    @Test
    public void testReverse() {
        assertVersioned("reverseSnippet", 1);
        int[] a = array(100);
        test("reverseSnippet", a, 100);
        test("reverseSnippet", a, 101);
        test("reverseSnippet", new int[0], 1);
    }

    /**
     * Conditional elimination already proves this check from the exit test {@code i < length} with
     * {@code length = value.length >> coder}, so only that exit remains and nothing is versioned.
     */
    @Test
    public void testShiftedLength() {
        assertStructure("shiftedLengthSnippet", 1, 1, unprofiledOptions());
        byte[] value = new byte[64];
        test("shiftedLengthSnippet", value, 0, 64);
        test("shiftedLengthSnippet", value, 1, 64);
        test("shiftedLengthSnippet", value, 0, 65);
    }

    /** Only the check on the indirection array depends on the counter; the other one must stay in both copies. */
    @Test
    public void testIndirect() {
        assertVersioned("indirectSnippet", 3);
        int[] a = array(100);
        int[] idx = array(100);
        test("indirectSnippet", a, idx, 99);
        test("indirectSnippet", a, idx, 100);
        test("indirectSnippet", a, array(10), 100);
    }

    @Test
    public void testStore() {
        assertVersioned("storeSnippet", 1);
        test("storeSnippet", array(100), 100, 7);
        test("storeSnippet", array(100), 101, 7);
    }

    /**
     * The array length is only read behind the null check inside the loop, so a null check is
     * hoisted in front of the version test and a null array takes the original loop (which throws
     * the NullPointerException, or nothing if the loop is not entered).
     */
    @Test
    public void testNullableBase() {
        assertVersioned("nullableSnippet", 1);
        test("nullableSnippet", array(100), 100);
        test("nullableSnippet", array(100), 101);
        test("nullableSnippet", null, 100);
        test("nullableSnippet", null, 0);
    }

    public static int twoNullableSnippet(int[] a, int[] b, int n) {
        int sum = 0;
        for (int i = 0; i < n; i++) {
            sum += a[i] - b[i];
        }
        return sum;
    }

    @Test
    public void testTwoNullableBases() {
        assertVersioned("twoNullableSnippet", 2);
        test("twoNullableSnippet", array(100), array(100), 100);
        test("twoNullableSnippet", array(100), array(99), 100);
        test("twoNullableSnippet", null, array(100), 100);
        test("twoNullableSnippet", array(100), null, 100);
        test("twoNullableSnippet", null, null, 0);
    }

    @Test
    public void testDisabled() {
        OptionValues options = new OptionValues(getInitialOptions(), LoopRangeCheckVersioningPhase.Options.LoopRangeCheckVersioning, false);
        test(options, getResolvedJavaMethod("sumSnippet"), null, array(100), 100);
        // loops with a low trip count or without a profile of the compiled program are refused
        assertStructure("sumSnippet", 1, 1, new OptionValues(unprofiledOptions(), LoopRangeCheckVersioningPhase.Options.LoopRangeCheckVersioningMinFrequency, 1e9));
        assertStructure("sumSnippet", 1, 1, getInitialOptions());
    }
}
