/*
 * Copyright (c) 2024, Oracle and/or its affiliates. All rights reserved.
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
package jdk.graal.compiler.hotspot;

import static jdk.graal.compiler.hotspot.meta.HotSpotHostForeignCallsProvider.SHENANDOAH_NARROW_STRONG_LRB_CALL;
import static jdk.graal.compiler.hotspot.meta.HotSpotHostForeignCallsProvider.SHENANDOAH_STRONG_LRB_CALL;
import static jdk.graal.compiler.hotspot.meta.HotSpotHostForeignCallsProvider.SHENANDOAH_PHANTOM_LRB_CALL;
import static jdk.graal.compiler.hotspot.meta.HotSpotHostForeignCallsProvider.SHENANDOAH_NARROW_WEAK_LRB_CALL;
import static jdk.graal.compiler.hotspot.meta.HotSpotHostForeignCallsProvider.SHENANDOAH_WEAK_LRB_CALL;

import jdk.graal.compiler.core.common.spi.ForeignCallDescriptor;
import jdk.graal.compiler.core.common.spi.ForeignCallLinkage;
import jdk.graal.compiler.hotspot.meta.HotSpotProviders;
import jdk.graal.compiler.hotspot.replacements.HotSpotReplacementsUtil;
import jdk.graal.compiler.lir.gen.ShenandoahBarrierSetLIRTool;
import jdk.vm.ci.code.Register;
import jdk.vm.ci.meta.InvokeTarget;

/**
 * Shared HotSpot specific values required for Shenandoah assembler barrier emission.
 */
public abstract class HotSpotShenandoahBarrierSetLIRTool implements ShenandoahBarrierSetLIRTool {

    public HotSpotShenandoahBarrierSetLIRTool(GraalHotSpotVMConfig config, HotSpotProviders providers) {
        this.config = config;
        this.providers = providers;
        this.threadRegister = providers.getRegisters().getThreadRegister();
    }

    protected final Register threadRegister;
    protected final GraalHotSpotVMConfig config;
    protected final HotSpotProviders providers;

    @Override
    public int gcStateOffset() {
        return HotSpotReplacementsUtil.shenandoahGCStateOffset(config);
    }

    @Override
    public int satbQueueMarkingActiveOffset() {
        return HotSpotReplacementsUtil.g1SATBQueueMarkingActiveOffset(config);
    }

    @Override
    public int satbQueueBufferOffset() {
        return HotSpotReplacementsUtil.g1SATBQueueBufferOffset(config);
    }

    @Override
    public int satbQueueIndexOffset() {
        return HotSpotReplacementsUtil.g1SATBQueueIndexOffset(config);
    }

    @Override
    public int cardQueueBufferOffset() {
        return HotSpotReplacementsUtil.g1CardQueueBufferOffset(config);
    }

    @Override
    public int cardQueueIndexOffset() {
        return HotSpotReplacementsUtil.g1CardQueueIndexOffset(config);
    }

    @Override
    public byte dirtyCardValue() {
        return config.dirtyCardValue;
    }

    @Override
    public byte youngCardValue() {
        return HotSpotReplacementsUtil.g1YoungCardValue(config);
    }

    @Override
    public long cardTableAddress() {
        return HotSpotReplacementsUtil.cardTableStart(config);
    }

    @Override
    public int regionSizeBytesShift() {
        return HotSpotReplacementsUtil.shenandoahGCRegionSizeBytesShift(config);
    }

    @Override
    public long csetFastTestAddr() {
        return HotSpotReplacementsUtil.shenandoahGCCSetFastTestAddr(config);
    }

    @Override
    public ForeignCallDescriptor strongNarrowLRBDescriptor() {
        return SHENANDOAH_NARROW_STRONG_LRB_CALL;
    }

    @Override
    public ForeignCallDescriptor strongLRBDescriptor() {
        return SHENANDOAH_STRONG_LRB_CALL;
    }

    @Override
    public ForeignCallDescriptor weakNarrowLRBDescriptor() {
        return SHENANDOAH_NARROW_WEAK_LRB_CALL;
    }

    @Override
    public ForeignCallDescriptor weakLRBDescriptor() {
        return SHENANDOAH_WEAK_LRB_CALL;
    }

    @Override
    public ForeignCallDescriptor phantomLRBDescriptor() {
        return SHENANDOAH_PHANTOM_LRB_CALL;
    }

    @Override
    public InvokeTarget getCallTarget(ForeignCallLinkage callTarget) {
        return callTarget;
    }
}
