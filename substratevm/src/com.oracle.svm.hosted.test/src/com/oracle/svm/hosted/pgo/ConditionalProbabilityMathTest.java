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

import java.util.Map;
import java.util.Optional;

import org.junit.Assert;
import org.junit.Test;

import com.oracle.svm.hosted.pgo.phases.PGOApplyProfilesPhase;

/**
 * Exercises the public conditional-probability math on {@link PGOApplyProfilesPhase} that the
 * conditional-only consumer relies on. These are pure functions over the {@code long[]} record
 * arrays produced by {@link IprofConditionalParser}, so no image build is required.
 */
public class ConditionalProbabilityMathTest {

    private static final double EPS = 1e-9;

    /** The documented worked example: BCI 20 taken 10x, BCI 53 taken 1x. */
    @Test
    public void distributesDocumentedVectorTenToOne() {
        Optional<double[]> probabilities = PGOApplyProfilesPhase.distributeConditionalProbabilities(new long[]{20, 0, 10, 53, 1, 1});
        Assert.assertTrue(probabilities.isPresent());
        double[] p = probabilities.get();
        Assert.assertEquals(2, p.length);
        Assert.assertEquals(1.0, p[0] + p[1], EPS);
        Assert.assertEquals(10.0, p[0] / p[1], 1e-6);
    }

    @Test
    public void aggregatesByValidBci() {
        Optional<Map<Integer, Double>> aggregated = PGOApplyProfilesPhase.aggregatedProbabilities(new long[]{20, 0, 10, 53, 1, 1});
        Assert.assertTrue(aggregated.isPresent());
        Map<Integer, Double> byBci = aggregated.get();
        Assert.assertEquals(2, byBci.size());
        Assert.assertTrue(byBci.containsKey(20));
        Assert.assertTrue(byBci.containsKey(53));
        Assert.assertEquals(1.0, byBci.get(20) + byBci.get(53), EPS);
        Assert.assertTrue("hot branch (bci 20) must dominate", byBci.get(20) > byBci.get(53));
    }

    /**
     * The core correctness property: swapping which branch is hot must swap which BCI receives the
     * higher probability. Opposite profiles produce opposite probabilities.
     */
    @Test
    public void oppositeBranchProfilesProduceOppositeProbabilities() {
        Map<Integer, Double> takeFirst = PGOApplyProfilesPhase.aggregatedProbabilities(new long[]{20, 0, 10, 53, 1, 1}).orElseThrow();
        Map<Integer, Double> takeSecond = PGOApplyProfilesPhase.aggregatedProbabilities(new long[]{20, 0, 1, 53, 1, 10}).orElseThrow();

        // When the first branch is hot, BCI 20 dominates.
        Assert.assertTrue(takeFirst.get(20) > takeFirst.get(53));
        // When the second branch is hot, BCI 53 dominates -- the opposite assignment.
        Assert.assertTrue(takeSecond.get(53) > takeSecond.get(20));

        // The two distributions are mirror images of each other.
        Assert.assertEquals(takeFirst.get(20), takeSecond.get(53), 1e-6);
        Assert.assertEquals(takeFirst.get(53), takeSecond.get(20), 1e-6);
    }

    @Test
    public void bytecodeIndicesAndMappingsAreExtracted() {
        long[] records = {20, 0, 10, 53, 1, 1};
        Assert.assertArrayEquals(new int[]{20, 53}, PGOApplyProfilesPhase.bytecodeIndicesForConditionals(records));
        Assert.assertArrayEquals(new int[]{0, 1}, PGOApplyProfilesPhase.conditionalMappings(records));
        Assert.assertEquals(2, PGOApplyProfilesPhase.conditionalRecordCount(records.length));
    }

    /** A branch with total count zero yields no probabilities (not-executed guard). */
    @Test
    public void allZeroCountsYieldNoProbabilities() {
        Assert.assertTrue(PGOApplyProfilesPhase.distributeConditionalProbabilities(new long[]{20, 0, 0, 53, 1, 0}).isEmpty());
        Assert.assertTrue(PGOApplyProfilesPhase.aggregatedProbabilities(new long[]{20, 0, 0, 53, 1, 0}).isEmpty());
    }

    /**
     * Switch-style records where several branches share a target (marked by an invalid alias BCI)
     * aggregate their counts onto the single valid BCI.
     */
    @Test
    public void invalidAliasBciFoldsIntoSharedKey() {
        // Two records with key 1 share a target: one carries the valid BCI 53, the alias uses -5.
        long[] records = {20, 0, 10, 53, 1, 5, -5, 1, 5};
        Optional<Map<Integer, Double>> aggregated = PGOApplyProfilesPhase.aggregatedProbabilities(records);
        Assert.assertTrue(aggregated.isPresent());
        Map<Integer, Double> byBci = aggregated.get();
        // Only valid BCIs appear as keys; -5 is folded away.
        Assert.assertFalse(byBci.containsKey(-5));
        Assert.assertTrue(byBci.containsKey(20));
        Assert.assertTrue(byBci.containsKey(53));
    }
}
