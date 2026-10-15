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

import org.junit.Assert;
import org.junit.Test;

import com.oracle.svm.hosted.pgo.phases.PGOApplyProfilesPhase;

/**
 * The flow check withdraws one record per round. These tests pin the selection rule on the shape
 * that motivated it: a residual loop-exit record with 60 events that makes the whole loop body look
 * 100x hotter than it is, next to legitimate records with millions of events in the same inflated
 * region.
 */
public class FlowCheckSelectionTest {

    private static final double RATIO = 0.1;
    private static final long MIN_EVENTS = 1000;

    @Test
    public void culpritWithFewestEventsGoesFirstEvenWhenCoverageTies() {
        /* Three records in a loop the graph believes runs 43 000 times per entry (3 M entries). */
        long[] events = {3_000_121, 60, 3_000_121, 60};
        double[] implied = {1.3e11, 2.6e6, 1.3e11, 1.3e11};
        /* Both 60-event records have lower coverage than the big ones; the lowest coverage wins among them. */
        Assert.assertEquals(3, PGOApplyProfilesPhase.selectUndercoveredRecord(events, implied, RATIO, MIN_EVENTS));
    }

    @Test
    public void legitimateRecordsSurviveOnceTheLoopIsRederived() {
        /* After the culprit is gone the loop runs ~2x: the 3 M records now cover half of their implied executions. */
        long[] events = {3_000_121, 3_000_121};
        double[] implied = {6.0e6, 6.0e6};
        Assert.assertEquals(-1, PGOApplyProfilesPhase.selectUndercoveredRecord(events, implied, RATIO, MIN_EVENTS));
    }

    @Test
    public void smallImpliedCountsAreNeverWithdrawn() {
        long[] events = {0, 1};
        double[] implied = {999, 500};
        Assert.assertEquals(-1, PGOApplyProfilesPhase.selectUndercoveredRecord(events, implied, RATIO, MIN_EVENTS));
        /* At the minimum, a record with no events is withdrawn. */
        Assert.assertEquals(0, PGOApplyProfilesPhase.selectUndercoveredRecord(events, new double[]{1000, 500}, RATIO, MIN_EVENTS));
    }

    @Test
    public void ratioBoundaryIsExclusive() {
        long[] events = {100};
        Assert.assertEquals(-1, PGOApplyProfilesPhase.selectUndercoveredRecord(events, new double[]{1000}, RATIO, MIN_EVENTS));
        Assert.assertEquals(0, PGOApplyProfilesPhase.selectUndercoveredRecord(events, new double[]{1001}, RATIO, MIN_EVENTS));
    }

    @Test
    public void emptyInput() {
        Assert.assertEquals(-1, PGOApplyProfilesPhase.selectUndercoveredRecord(new long[0], new double[0], RATIO, MIN_EVENTS));
    }

    @Test
    public void recordedEventsSumsAllSuccessorCounts() {
        /* Records are (successor bci, successor index, count) triples. */
        Assert.assertEquals(78_047_520L, PGOApplyProfilesPhase.recordedEvents(new long[]{45, 0, 0, 4, 1, 78_047_520}));
        Assert.assertEquals(3_000_121L, PGOApplyProfilesPhase.recordedEvents(new long[]{97, 0, 3_000_061, 70, 1, 60}));
        Assert.assertEquals(0L, PGOApplyProfilesPhase.recordedEvents(new long[0]));
    }
}
