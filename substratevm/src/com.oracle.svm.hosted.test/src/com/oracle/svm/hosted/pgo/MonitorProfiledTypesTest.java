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

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Assert;
import org.junit.Test;

import com.oracle.svm.hosted.pgo.IprofConditionalParser.MonitorEntry;
import com.oracle.svm.hosted.pgo.IprofConditionalParser.ParsedProfile;

public class MonitorProfiledTypesTest {
    @Test
    public void retainsExactlyObservedTypesAndReportsDecisions() {
        Set<String> observed = new HashSet<>(Set.of("Lexample/Locked;", "Lexample/AlsoLocked;"));
        MonitorProfiledTypes profiles = new MonitorProfiledTypes(observed, 1234);
        observed.clear(); // Constructor must own an immutable snapshot.

        Assert.assertTrue(profiles.retainMonitorField("Lexample/Locked;"));
        Assert.assertFalse(profiles.retainMonitorField("Lexample/NeverLocked;"));
        Assert.assertTrue(profiles.retainMonitorField("Lexample/AlsoLocked;"));

        Assert.assertEquals(2, profiles.observedTypes());
        Assert.assertEquals(1234, profiles.recordedEvents());
        Assert.assertEquals(3, profiles.synchronizedTypes());
        Assert.assertEquals(2, profiles.retainedTypes());
        Assert.assertEquals(1, profiles.omittedTypes());
    }

    @Test
    public void requiresMonitorCategoryInEveryDistinctProfile() {
        ParsedProfile absent = profile(false, false);
        ParsedProfile empty = profile(true, false);
        ParsedProfile present = profile(true, true);

        Assert.assertTrue(PGOConditionalProfilesFeature.completeMonitorProfiles(absent, "early.iprof", present, "post.iprof").isEmpty());
        Assert.assertTrue(PGOConditionalProfilesFeature.completeMonitorProfiles(present, "early.iprof", absent, "post.iprof").isEmpty());
        Assert.assertEquals(List.of(empty, present), PGOConditionalProfilesFeature.completeMonitorProfiles(empty, "early.iprof", present, "post.iprof"));
        Assert.assertEquals(List.of(present), PGOConditionalProfilesFeature.completeMonitorProfiles(present, "same.iprof", present, "same.iprof"));
    }

    private static ParsedProfile profile(boolean monitorProfilesRecorded, boolean withEntry) {
        List<MonitorEntry> entries = withEntry ? List.of(new MonitorEntry(new long[]{0, 1})) : List.of();
        return new ParsedProfile("1.1.0", Map.of(0, "java.lang.Object"), Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(), entries, monitorProfilesRecorded);
    }
}
