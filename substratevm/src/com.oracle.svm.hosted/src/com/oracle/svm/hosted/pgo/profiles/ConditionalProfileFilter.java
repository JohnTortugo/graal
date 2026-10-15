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

import static com.oracle.svm.hosted.pgo.phases.PGOApplyProfilesPhase.CONDITIONAL_RECORD_COUNTER_POSITION;
import static com.oracle.svm.hosted.pgo.phases.PGOApplyProfilesPhase.CONDITIONAL_RECORD_SIZE;

/**
 * Decides whether a matched conditional profile carries enough information to override the
 * compiler's static probability. A site that ran a handful of times, or whose successors were taken
 * about equally often, says little; applying it replaces a heuristic with noise and, worse, replaces
 * an "unlikely" injected probability with an extreme value derived from one or two observations.
 *
 * @param minEvents minimum total successor events; {@code 0} disables the check
 * @param minBias minimum share of the dominant successor in {@code [0, 1]}; {@code 0} disables
 */
public record ConditionalProfileFilter(long minEvents, double minBias) {

    public static final ConditionalProfileFilter NONE = new ConditionalProfileFilter(0, 0.0);

    public enum Verdict {
        ACCEPT,
        TOO_FEW_EVENTS,
        TOO_EVEN
    }

    public boolean isActive() {
        return minEvents > 0 || minBias > 0.0;
    }

    public Verdict classify(long[] records) {
        long total = totalEvents(records);
        if (minEvents > 0 && total < minEvents) {
            return Verdict.TOO_FEW_EVENTS;
        }
        if (minBias > 0.0 && total > 0 && dominantShare(records, total) < minBias) {
            return Verdict.TOO_EVEN;
        }
        return Verdict.ACCEPT;
    }

    public static long totalEvents(long[] records) {
        long total = 0;
        for (int i = CONDITIONAL_RECORD_COUNTER_POSITION; i < records.length; i += CONDITIONAL_RECORD_SIZE) {
            total += records[i];
        }
        return total;
    }

    /** Share of the most frequent successor record; switch-case aggregation is not needed for IfNodes. */
    public static double dominantShare(long[] records, long total) {
        long max = 0;
        for (int i = CONDITIONAL_RECORD_COUNTER_POSITION; i < records.length; i += CONDITIONAL_RECORD_SIZE) {
            max = Math.max(max, records[i]);
        }
        return total == 0 ? 0.0 : (double) max / total;
    }
}
