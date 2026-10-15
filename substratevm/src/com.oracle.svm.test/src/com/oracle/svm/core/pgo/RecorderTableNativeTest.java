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
        Assert.assertEquals(8_192, result.occupied());
        Assert.assertEquals(8_193, result.privateCount());
        Assert.assertEquals(8_193, result.snapshotPrivateCount());
        Assert.assertEquals(CallCountProfileRecorder.isEnabled(), result.routedThroughRuntime());
        Assert.assertTrue(result.slowPathPreserved());
        Assert.assertTrue(result.snapshotCleared());
        Assert.assertTrue(result.clearReused());
    }

    @Test
    public void stackSampleTableAccountsForArenaDropTruncationAndDuplicate() {
        StackSampleRecorder.TableTestResult result = StackSampleRecorder.exerciseTableForTesting();
        Assert.assertEquals(1_026, result.samples());
        Assert.assertEquals(1, result.dropped());
        Assert.assertEquals(1, result.truncated());
        Assert.assertEquals(2, result.largestBucketCount());
    }

    @Test
    public void receiverTableHandlesCollisionFullDuplicateAndClear() {
        ReceiverProfileRecorder.TableTestResult result = ReceiverProfileRecorder.exerciseTableForTesting();
        Assert.assertEquals(16_384, result.occupied());
        Assert.assertEquals(16_385, result.privateCount());
        Assert.assertEquals(16_385, result.snapshotPrivateCount());
        Assert.assertEquals(ReceiverProfileRecorder.isEnabled(), result.routedThroughRuntime());
        Assert.assertEquals(1, result.fullTableDrops());
        Assert.assertEquals(1, result.allocationDrops());
        Assert.assertTrue(result.mergeCleared());
        Assert.assertTrue(result.clearReused());
    }
}
