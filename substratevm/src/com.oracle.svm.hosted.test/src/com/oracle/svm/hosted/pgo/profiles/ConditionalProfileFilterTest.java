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

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.Assert;
import org.junit.Test;

import com.oracle.svm.hosted.pgo.profiles.ConditionalProfileFilter.Verdict;
import com.oracle.svm.hosted.pgo.profiles.PGOProfilesLookup.ProfiledValue;
import com.oracle.svm.hosted.pgo.profiles.SimpleConditionalProfilesLookup.FrameKey;

import jdk.vm.ci.code.BytecodePosition;

public class ConditionalProfileFilterTest {

    /* records: [bci, key, count] per successor. */
    private static final long[] HOT_SKEWED = {10, 0, 9_000, 20, 1, 1_000};
    private static final long[] HOT_EVEN = {10, 0, 5_100, 20, 1, 4_900};
    private static final long[] COLD_SKEWED = {10, 0, 3, 20, 1, 0};
    private static final long[] NEVER = {10, 0, 0, 20, 1, 0};

    @Test
    public void disabledFilterAcceptsEverything() {
        Assert.assertFalse(ConditionalProfileFilter.NONE.isActive());
        for (long[] records : List.of(HOT_SKEWED, HOT_EVEN, COLD_SKEWED, NEVER)) {
            Assert.assertEquals(Verdict.ACCEPT, ConditionalProfileFilter.NONE.classify(records));
        }
    }

    @Test
    public void minimumEventsWithholdsColdSites() {
        ConditionalProfileFilter filter = new ConditionalProfileFilter(100, 0.0);
        Assert.assertTrue(filter.isActive());
        Assert.assertEquals(Verdict.ACCEPT, filter.classify(HOT_SKEWED));
        Assert.assertEquals(Verdict.ACCEPT, filter.classify(HOT_EVEN));
        Assert.assertEquals(Verdict.TOO_FEW_EVENTS, filter.classify(COLD_SKEWED));
        Assert.assertEquals(Verdict.TOO_FEW_EVENTS, filter.classify(NEVER));
    }

    @Test
    public void minimumBiasWithholdsNearEvenSites() {
        ConditionalProfileFilter filter = new ConditionalProfileFilter(0, 0.6);
        Assert.assertEquals(Verdict.ACCEPT, filter.classify(HOT_SKEWED));
        Assert.assertEquals(Verdict.TOO_EVEN, filter.classify(HOT_EVEN));
        /* An all-true cold site is maximally biased; only the event threshold can withhold it. */
        Assert.assertEquals(Verdict.ACCEPT, filter.classify(COLD_SKEWED));
        /* No events: nothing to judge bias on; left to the event threshold. */
        Assert.assertEquals(Verdict.ACCEPT, filter.classify(NEVER));
    }

    @Test
    public void eventThresholdIsCheckedBeforeBias() {
        ConditionalProfileFilter filter = new ConditionalProfileFilter(100, 0.6);
        Assert.assertEquals(Verdict.TOO_FEW_EVENTS, filter.classify(COLD_SKEWED));
        Assert.assertEquals(Verdict.TOO_EVEN, filter.classify(HOT_EVEN));
        Assert.assertEquals(10_000, ConditionalProfileFilter.totalEvents(HOT_SKEWED));
        Assert.assertEquals(0.9, ConditionalProfileFilter.dominantShare(HOT_SKEWED, 10_000), 1e-9);
    }

    @Test
    public void lookupWithholdsFilteredSitesAndAccountsForThemSeparately() {
        String bar = ConditionalProfileContextResolverTest.BAR_DESC;
        List<FrameKey> hot = List.of(new FrameKey(bar, 5));
        List<FrameKey> cold = List.of(new FrameKey(bar, 7));
        SimpleConditionalProfilesLookup lookup = new SimpleConditionalProfilesLookup(Map.of(hot, HOT_SKEWED, cold, COLD_SKEWED), null);
        lookup.setFilter(new ConditionalProfileFilter(100, 0.0));

        Optional<ProfiledValue<long[]>> hotResult = lookup.getConditionalProfile(new BytecodePosition(null, ConditionalProfileContextResolverTest.mockBarMethod(), 5));
        Optional<ProfiledValue<long[]>> coldResult = lookup.getConditionalProfile(new BytecodePosition(null, ConditionalProfileContextResolverTest.mockBarMethod(), 7));

        Assert.assertTrue(hotResult.isPresent());
        Assert.assertTrue(coldResult.isEmpty());
        Assert.assertEquals(1, lookup.hitCount());
        Assert.assertEquals(0, lookup.missCount());
        Assert.assertEquals(1, lookup.filteredFewEventsCount());
        Assert.assertEquals(0, lookup.filteredEvenCount());
        Assert.assertEquals(1, lookup.filteredContextCount());
        Assert.assertEquals(1, lookup.matchedContextCount());
    }

    @Test
    public void priorComparisonBucketsByEventDecade() {
        SimpleConditionalProfilesLookup lookup = new SimpleConditionalProfilesLookup(Map.of(), null);
        lookup.recordPriorComparison(0, false, false);
        lookup.recordPriorComparison(5, true, true);
        lookup.recordPriorComparison(5, false, false);
        lookup.recordPriorComparison(12_345, true, false);
        Assert.assertEquals("0: 1/0(0), 1e0-1e1: 1/1(1), 1e4-1e5: 0/1(0)", lookup.priorComparisonSummary());
    }
}
