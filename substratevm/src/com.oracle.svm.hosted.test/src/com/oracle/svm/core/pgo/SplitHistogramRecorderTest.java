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

import java.util.ArrayList;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

public class SplitHistogramRecorderTest {

    @Test
    public void lengthBucketsArePowerOfTwoRanges() {
        Assert.assertEquals(0, SplitHistogramRecorder.lengthBucket(0));
        Assert.assertEquals(1, SplitHistogramRecorder.lengthBucket(1));
        Assert.assertEquals(2, SplitHistogramRecorder.lengthBucket(2));
        Assert.assertEquals(2, SplitHistogramRecorder.lengthBucket(3));
        Assert.assertEquals(3, SplitHistogramRecorder.lengthBucket(4));
        Assert.assertEquals(3, SplitHistogramRecorder.lengthBucket(7));
        Assert.assertEquals(4, SplitHistogramRecorder.lengthBucket(8));
        Assert.assertEquals(11, SplitHistogramRecorder.lengthBucket(2047));
        Assert.assertEquals(12, SplitHistogramRecorder.lengthBucket(2048));
        Assert.assertEquals(12, SplitHistogramRecorder.lengthBucket(Integer.MAX_VALUE));
    }

    @Test
    public void outputBucketsFollowArrayListGrowth() {
        Assert.assertEquals(0, SplitHistogramRecorder.outputBucket(0));
        Assert.assertEquals(10, SplitHistogramRecorder.outputBucket(10));
        Assert.assertEquals(15, SplitHistogramRecorder.outputBucket(15));
        Assert.assertEquals(16, SplitHistogramRecorder.outputBucket(16));
        Assert.assertEquals(16, SplitHistogramRecorder.outputBucket(22));
        Assert.assertEquals(17, SplitHistogramRecorder.outputBucket(23));
        Assert.assertEquals(17, SplitHistogramRecorder.outputBucket(33));
        Assert.assertEquals(18, SplitHistogramRecorder.outputBucket(63));
        Assert.assertEquals(19, SplitHistogramRecorder.outputBucket(64));
    }

    @Test
    public void recordsPerDelimiterDistributions() {
        SplitHistogramRecorder.enable();
        SplitHistogramRecorder.recordInvocation("a;b;c", ';', new ArrayList<>(List.of("a", "b", "c")));
        SplitHistogramRecorder.recordInvocation("abcdefgh", ';', new ArrayList<>(List.of("abcdefgh")));
        SplitHistogramRecorder.recordInvocation(null, 0x2603, null);
        CallCountProfileRecorder.refreshHostedSnapshotForTesting();

        int lengths = 1;
        int outputs = lengths + SplitHistogramRecorder.LENGTH_BUCKETS;
        int coders = outputs + SplitHistogramRecorder.OUTPUT_BUCKETS;
        Assert.assertEquals(2, SplitHistogramRecorder.count(';', 0));
        Assert.assertEquals(1, SplitHistogramRecorder.count(';', lengths + 3));
        Assert.assertEquals(1, SplitHistogramRecorder.count(';', lengths + 4));
        Assert.assertEquals(1, SplitHistogramRecorder.count(';', outputs + 1));
        Assert.assertEquals(1, SplitHistogramRecorder.count(';', outputs + 3));
        Assert.assertEquals(2, SplitHistogramRecorder.count(';', coders));
        Assert.assertEquals(1, SplitHistogramRecorder.count(0x2603, 0));
        Assert.assertEquals(1, SplitHistogramRecorder.count(0x2603, coders + 2));
    }
}
