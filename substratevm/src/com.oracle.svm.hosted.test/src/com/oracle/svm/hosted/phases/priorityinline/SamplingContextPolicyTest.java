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
package com.oracle.svm.hosted.phases.priorityinline;

import org.junit.Assert;
import org.junit.Test;

public class SamplingContextPolicyTest {

    @Test
    public void createsRootRelativeSamplingContext() {
        SubstrateInliningProvider.SamplingContext context = SubstrateInliningProvider.createSamplingContext(200, 50);
        Assert.assertEquals(0.25, context.rootRelativeHotness(), 0.0);
        Assert.assertEquals(50, context.samples());
        Assert.assertSame(SubstrateInliningProvider.SamplingContext.COLD, SubstrateInliningProvider.createSamplingContext(0, 50));
        Assert.assertSame(SubstrateInliningProvider.SamplingContext.COLD, SubstrateInliningProvider.createSamplingContext(200, 0));
    }

    @Test
    public void callCountHotnessIsStoredWithoutSamplingSupport() {
        SubstrateInliningProvider.SamplingContext callOnly = new SubstrateInliningProvider.SamplingContext(1.0, 0);
        Assert.assertTrue(SamplingCallTreeState.shouldStore(callOnly));
        Assert.assertFalse(SamplingCallTreeState.shouldStore(SubstrateInliningProvider.SamplingContext.COLD));
    }

    @Test
    public void selectedBonusComposesWithPriorSmoothBonus() {
        Assert.assertEquals(1.25, SubstratePolicyFactory.SubstrateExpanderPolicy.hotnessMultiplier(0.25, 1, 0), 0.0);
        Assert.assertEquals(2.25, SubstratePolicyFactory.SubstrateExpanderPolicy.hotnessMultiplier(0.25, 1, 1), 0.0);
        Assert.assertEquals(2.0, SubstratePolicyFactory.SubstrateExpanderPolicy.hotnessMultiplier(0.25, 0, 1), 0.0);
    }
}
