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

import java.util.ArrayList;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

import jdk.graal.compiler.core.common.GraalOptions;
import jdk.graal.compiler.graph.Node;
import jdk.graal.compiler.loop.phases.InjectLoopCounterStampsPhase;
import jdk.graal.compiler.nodes.ConstantNode;
import jdk.graal.compiler.nodes.FixedGuardNode;
import jdk.graal.compiler.nodes.GuardNode;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.StructuredGraph.AllowAssumptions;
import jdk.graal.compiler.nodes.spi.CoreProviders;
import jdk.graal.compiler.options.OptionValues;
import jdk.graal.compiler.phases.common.CanonicalizerPhase;
import jdk.graal.compiler.phases.common.FloatingReadPhase;
import jdk.graal.compiler.phases.common.IterativeConditionalEliminationPhase;
import jdk.vm.ci.meta.DeoptimizationReason;
import jdk.vm.ci.meta.JavaKind;

/**
 * Tests that conditional elimination proves {@code x < B} from a dominating {@code x < A} when
 * {@code A} is structurally never greater than {@code B}: a right shift, a mask, a minimum or a
 * non-positive constant offset of {@code B}. This is the shape of {@code String.charAt} inside a
 * loop over {@code String.length()}, where the loop bound is {@code value.length >> coder} and the
 * bounds check compares against {@code value.length}.
 */
public class ConditionalEliminationStructuralImplicationTest extends ConditionalEliminationTestBase {

    public static void unsignedShiftSnippet(byte[] value, int coder, int i) {
        int length = value.length >>> coder;
        if (Integer.compareUnsigned(i, length) < 0) {
            if (Integer.compareUnsigned(i, value.length) < 0) {
                sink1 = 0;
            } else {
                sink2 = -2;
            }
        } else {
            sink0 = -1;
        }
    }

    public static void signedShiftSnippet(byte[] value, int coder, int i) {
        int length = value.length >> coder;
        if (i < length) {
            if (i < value.length) {
                sink1 = 0;
            } else {
                sink2 = -2;
            }
        } else {
            sink0 = -1;
        }
    }

    public static void maskSnippet(byte[] value, int mask, int i) {
        int length = value.length & mask;
        if (i < length) {
            if (i < value.length) {
                sink1 = 0;
            } else {
                sink2 = -2;
            }
        } else {
            sink0 = -1;
        }
    }

    public static void minSnippet(byte[] value, int other, int i) {
        int length = other < value.length ? other : value.length;
        if (i < length) {
            if (i < value.length) {
                sink1 = 0;
            } else {
                sink2 = -2;
            }
        } else {
            sink0 = -1;
        }
    }

    public static void negativeOffsetSnippet(byte[] value, int i) {
        int length = value.length - 1;
        if (i < length) {
            if (i < value.length) {
                sink1 = 0;
            } else {
                sink2 = -2;
            }
        } else {
            sink0 = -1;
        }
    }

    public static void negatedSnippet(byte[] value, int coder, int i) {
        int length = value.length >>> coder;
        if (i >= value.length) {
            if (i >= length) {
                sink1 = 0;
            } else {
                sink2 = -2;
            }
        } else {
            sink0 = -1;
        }
    }

    public static void loopSnippet(byte[] value, int coder) {
        int length = value.length >>> coder;
        for (int i = 0; i < length; i++) {
            if (value[i] == 1) {
                sink1 = i;
            }
        }
    }

    public static void differentArraySnippet(byte[] value, byte[] other, int coder, int i) {
        int length = other.length >>> coder;
        if (i < length) {
            if (i < value.length) {
                sink1 = 0;
            } else {
                sink2 = -2;
            }
        } else {
            sink0 = -1;
        }
    }

    public static void positiveOffsetSnippet(byte[] value, int i) {
        int length = value.length + 1;
        if (i < length) {
            if (i < value.length) {
                sink1 = 0;
            } else {
                sink2 = -2;
            }
        } else {
            sink0 = -1;
        }
    }

    public static void signedShiftOfSignedValueSnippet(int bound, int shift, int i) {
        int length = bound >> shift;
        if (i < length) {
            if (i < bound) {
                sink1 = 0;
            } else {
                sink2 = -2;
            }
        } else {
            sink0 = -1;
        }
    }

    @Test
    public void testUnsignedShiftFolds() {
        assertOutOfBoundsBranchFolds("unsignedShiftSnippet", true);
    }

    @Test
    public void testSignedShiftOfArrayLengthFolds() {
        assertOutOfBoundsBranchFolds("signedShiftSnippet", true);
    }

    @Test
    public void testMaskFolds() {
        assertOutOfBoundsBranchFolds("maskSnippet", true);
    }

    @Test
    public void testMinFolds() {
        assertOutOfBoundsBranchFolds("minSnippet", true);
    }

    @Test
    public void testNegativeOffsetFolds() {
        assertOutOfBoundsBranchFolds("negativeOffsetSnippet", true);
    }

    @Test
    public void testNegatedComparisonFolds() {
        assertOutOfBoundsBranchFolds("negatedSnippet", true);
    }

    @Test
    public void testLoopBoundsCheckFolds() {
        StructuredGraph graph = parseEager("loopSnippet", AllowAssumptions.YES);
        CanonicalizerPhase canonicalizer = createCanonicalizerPhase();
        CoreProviders context = getProviders();
        prepareGraph(graph, canonicalizer, context, true);
        new FloatingReadPhase(canonicalizer).apply(graph, context);
        // The mid tier knows the loop counter is non-negative before conditional elimination runs.
        new InjectLoopCounterStampsPhase().apply(graph, context);
        Assert.assertEquals(1, countBoundsCheckGuards(graph));
        new IterativeConditionalEliminationPhase(canonicalizer, true).apply(graph, context);
        canonicalizer.apply(graph, context);
        Assert.assertEquals(0, countBoundsCheckGuards(graph));
    }

    /** The shift must be of the same array length to say anything about its bound. */
    @Test
    public void testDifferentArrayDoesNotFold() {
        assertOutOfBoundsBranchFolds("differentArraySnippet", false);
    }

    /** {@code B + 1 <= B} does not hold. */
    @Test
    public void testPositiveOffsetDoesNotFold() {
        assertOutOfBoundsBranchFolds("positiveOffsetSnippet", false);
    }

    /** A signed shift of a possibly negative value grows towards -1, so it may exceed the value. */
    @Test
    public void testSignedShiftOfSignedValueDoesNotFold() {
        assertOutOfBoundsBranchFolds("signedShiftOfSignedValueSnippet", false);
    }

    /** The proof is gated by {@link GraalOptions#StructuralCompareImplication}. */
    @Test
    public void testDisabledDoesNotFold() {
        OptionValues options = new OptionValues(getInitialOptions(), GraalOptions.StructuralCompareImplication, false);
        StructuredGraph graph = parseEager("unsignedShiftSnippet", AllowAssumptions.YES, options);
        Assert.assertFalse(runConditionalElimination(graph).isEmpty());
    }

    private void assertOutOfBoundsBranchFolds(String snippet, boolean folds) {
        StructuredGraph graph = parseEager(snippet, AllowAssumptions.YES);
        Assert.assertEquals(folds, runConditionalElimination(graph).isEmpty());
    }

    /** Returns the remaining uses of the out-of-bounds marker constant {@code -2}. */
    private List<Node> runConditionalElimination(StructuredGraph graph) {
        CanonicalizerPhase canonicalizer = createCanonicalizerPhase();
        CoreProviders context = getProviders();
        prepareGraph(graph, canonicalizer, context, true);
        // Array length reads are only value-numbered once they float, as in the mid tier.
        new FloatingReadPhase(canonicalizer).apply(graph, context);
        Assert.assertTrue(hasIntConstant(graph, -2));
        new IterativeConditionalEliminationPhase(canonicalizer, true).apply(graph, context);
        canonicalizer.apply(graph, context);
        canonicalizer.apply(graph, context);
        List<Node> remaining = new ArrayList<>();
        for (Node node : graph.getNodes()) {
            if (node instanceof ConstantNode constant && constant.hasUsages() && constant.asJavaConstant().getJavaKind() == JavaKind.Int && constant.asJavaConstant().asInt() == -2) {
                remaining.add(node);
            }
        }
        return remaining;
    }

    private static boolean hasIntConstant(StructuredGraph graph, int value) {
        for (Node node : graph.getNodes()) {
            if (node instanceof ConstantNode constant && constant.hasUsages() && constant.asJavaConstant().getJavaKind() == JavaKind.Int && constant.asJavaConstant().asInt() == value) {
                return true;
            }
        }
        return false;
    }

    private static long countBoundsCheckGuards(StructuredGraph graph) {
        return graph.getNodes(GuardNode.TYPE).filter(guard -> ((GuardNode) guard).getReason() == DeoptimizationReason.BoundsCheckException).count() +
                        graph.getNodes(FixedGuardNode.TYPE).filter(guard -> ((FixedGuardNode) guard).getReason() == DeoptimizationReason.BoundsCheckException).count();
    }
}
