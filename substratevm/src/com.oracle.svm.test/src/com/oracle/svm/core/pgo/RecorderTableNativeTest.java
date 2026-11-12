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

import org.graalvm.nativeimage.ImageInfo;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

public class RecorderTableNativeTest {
    @Before
    public void requireNativeImageRuntime() {
        Assume.assumeTrue(ImageInfo.inImageCode());
    }

    @Test
    public void callCountTableHandlesCollisionFullDuplicateAndClear() {
        CallCountProfileRecorder.TableTestResult result = CallCountProfileRecorder.exerciseTableForTesting();
        /* 24,576 keys into a table that starts at 8,192 buckets and doubles at half load. */
        Assert.assertEquals(result.toString(), 24_576, result.occupied());
        Assert.assertEquals(result.toString(), 65_536, result.capacity());
        Assert.assertTrue("long chains: " + result.longChains(), result.longChains() < 24_576 / 100);
        Assert.assertEquals(result.toString(), 24_577 + 24_576 - result.longChains(), result.privateCount());
        Assert.assertEquals(result.toString(), result.privateCount(), result.snapshotPrivateCount());
        Assert.assertEquals(result.toString(), CallCountProfileRecorder.isEnabled(), result.routedThroughRuntime());
        Assert.assertTrue(result.toString(), result.slowPathPreserved());
        Assert.assertTrue(result.toString(), result.snapshotCleared());
        Assert.assertTrue(result.toString(), result.clearReused());
    }

    @Test
    public void stackSampleTableAccountsForArenaDropTruncationAndDuplicate() {
        StackSampleRecorder.TableTestResult result = StackSampleRecorder.exerciseTableForTesting();
        Assert.assertEquals(result.toString(), 1_026, result.samples());
        Assert.assertEquals(result.toString(), 1, result.dropped());
        Assert.assertEquals(result.toString(), 1, result.truncated());
        Assert.assertEquals(result.toString(), 2, result.largestBucketCount());
    }

    @Test
    public void receiverTableHandlesCollisionFullDuplicateAndClear() {
        ReceiverProfileRecorder.TableTestResult result = ReceiverProfileRecorder.exerciseTableForTesting();
        /* 49,152 keys into a table that starts at 16,384 buckets and doubles at half load. */
        Assert.assertEquals(result.toString(), 49_152, result.occupied());
        Assert.assertEquals(result.toString(), 131_072, result.capacity());
        Assert.assertTrue("long chains: " + result.longChains(), result.longChains() < 49_152 / 100);
        Assert.assertEquals(result.toString(), 49_153 + 49_152 - result.longChains(), result.privateCount());
        Assert.assertEquals(result.toString(), result.privateCount(), result.snapshotPrivateCount());
        Assert.assertEquals(result.toString(), ReceiverProfileRecorder.isEnabled(), result.routedThroughRuntime());
        Assert.assertTrue(result.toString(), result.spillPreserved());
        Assert.assertTrue(result.toString(), result.mergeCleared());
        Assert.assertTrue(result.toString(), result.clearReused());
    }
}
