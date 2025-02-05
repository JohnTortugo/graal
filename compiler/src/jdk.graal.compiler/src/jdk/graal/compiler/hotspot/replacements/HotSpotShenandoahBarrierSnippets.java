/*
 * Copyright (c) 2025, Oracle and/or its affiliates. All rights reserved.
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
package jdk.graal.compiler.hotspot.replacements;

import jdk.vm.ci.code.Register;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.ResolvedJavaType;
import jdk.graal.compiler.core.common.CompressEncoding;
import jdk.graal.compiler.core.common.spi.ForeignCallDescriptor;
import jdk.graal.compiler.hotspot.GraalHotSpotVMConfig;
import jdk.graal.compiler.hotspot.meta.HotSpotForeignCallDescriptor;
import jdk.graal.compiler.hotspot.meta.HotSpotForeignCallsProviderImpl;
import jdk.graal.compiler.hotspot.meta.HotSpotProviders;
import jdk.graal.compiler.hotspot.meta.HotSpotRegistersProvider;
import jdk.graal.compiler.hotspot.nodes.HotSpotCompressionNode;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.gc.ShenandoahArrayRangePreWriteBarrier;
import jdk.graal.compiler.nodes.gc.ShenandoahLoadReferenceBarrierNode;
import jdk.graal.compiler.nodes.gc.ShenandoahPreWriteBarrier;
import jdk.graal.compiler.nodes.gc.ShenandoahPosWriteBarrier;
import jdk.graal.compiler.nodes.gc.ShenandoahReferentFieldReadBarrier;
import jdk.graal.compiler.nodes.spi.LoweringTool;
import jdk.graal.compiler.options.OptionValues;
import jdk.graal.compiler.replacements.ReplacementsUtil;
import jdk.graal.compiler.replacements.SnippetCounter;
import jdk.graal.compiler.replacements.SnippetTemplate;
import jdk.graal.compiler.replacements.gc.ShenandoahBarrierSnippets;
import jdk.graal.compiler.word.Word;

import static jdk.graal.compiler.hotspot.GraalHotSpotVMConfig.INJECTED_METAACCESS;
import static jdk.graal.compiler.hotspot.GraalHotSpotVMConfig.INJECTED_VMCONFIG;
import static jdk.graal.compiler.core.common.spi.ForeignCallDescriptor.CallSideEffect.NO_SIDE_EFFECT;
import static jdk.graal.compiler.hotspot.meta.HotSpotForeignCallDescriptor.Transition.LEAF_NO_VZERO;
import static jdk.graal.compiler.hotspot.meta.HotSpotForeignCallsProviderImpl.NO_LOCATIONS;

public final class HotSpotShenandoahBarrierSnippets extends ShenandoahBarrierSnippets {
    public static final HotSpotForeignCallDescriptor SHENANDOAHWBPRECALL = new HotSpotForeignCallDescriptor(LEAF_NO_VZERO, NO_SIDE_EFFECT, KILLED_PRE_WRITE_BARRIER_STUB_LOCATIONS,
                    "shenandoah_concmark_barrier", void.class, Object.class);
    public static final HotSpotForeignCallDescriptor SHENANDOAHLRBCALL = new HotSpotForeignCallDescriptor(LEAF_NO_VZERO, NO_SIDE_EFFECT, NO_LOCATIONS, "shenandoah_load_reference_barrier",
                    Object.class,
                    Object.class);
    public static final HotSpotForeignCallDescriptor VALIDATE_OBJECT = new HotSpotForeignCallDescriptor(LEAF_NO_VZERO, NO_SIDE_EFFECT, NO_LOCATIONS, "validate_object", boolean.class, Word.class,
                    Word.class);

    private final GraalHotSpotVMConfig config;
    private final Register threadRegister;

    public HotSpotShenandoahBarrierSnippets(GraalHotSpotVMConfig config, HotSpotRegistersProvider registers) {
        this.config = config;
        this.threadRegister = registers.getThreadRegister();
    }

    @Override
    protected Word getThread() {
        return HotSpotReplacementsUtil.registerAsWord(threadRegister);
    }

    @Override
    protected int wordSize() {
        return HotSpotReplacementsUtil.wordSize();
    }

    @Override
    protected int objectArrayIndexScale() {
        return HotSpotReplacementsUtil.arrayIndexScale(INJECTED_METAACCESS, JavaKind.Object);
    }

    @Override
    protected int satbQueueMarkingOffset() {
        return HotSpotReplacementsUtil.shenandoahSATBQueueMarkingOffset(INJECTED_VMCONFIG);
    }

    @Override
    protected int satbQueueBufferOffset() {
        return HotSpotReplacementsUtil.shenandoahSATBQueueBufferOffset(INJECTED_VMCONFIG);
    }

    @Override
    protected int satbQueueIndexOffset() {
        return HotSpotReplacementsUtil.shenandoahSATBQueueIndexOffset(INJECTED_VMCONFIG);
    }

    @Override
    protected int gcStateOffset() {
        return HotSpotReplacementsUtil.shenandoahGCStateOffset(INJECTED_VMCONFIG);
    }

    @Override
    protected ForeignCallDescriptor preWriteBarrierCallDescriptor() {
        return SHENANDOAHWBPRECALL;
    }

    @Override
    protected ForeignCallDescriptor loadReferenceBarrierCallDescriptor() {
        return SHENANDOAHLRBCALL;
    }

    @Override
    protected boolean verifyOops() {
        return HotSpotReplacementsUtil.verifyOops(INJECTED_VMCONFIG);
    }

    @Override
    protected boolean verifyBarrier() {
        return ReplacementsUtil.REPLACEMENTS_ASSERTIONS_ENABLED || config.verifyBeforeGC || config.verifyAfterGC;
    }

    @Override
    protected long gcTotalCollectionsAddress() {
        return HotSpotReplacementsUtil.gcTotalCollectionsAddress(INJECTED_VMCONFIG);
    }

    @Override
    protected ForeignCallDescriptor verifyOopCallDescriptor() {
        return HotSpotForeignCallsProviderImpl.VERIFY_OOP;
    }

    @Override
    protected ForeignCallDescriptor validateObjectCallDescriptor() {
        return VALIDATE_OBJECT;
    }

    @Override
    protected ForeignCallDescriptor printfCallDescriptor() {
        return Log.LOG_PRINTF;
    }

    @Override
    protected ResolvedJavaType referenceType() {
        return HotSpotReplacementsUtil.referenceType(INJECTED_METAACCESS);
    }

    @Override
    protected long referentOffset() {
        return HotSpotReplacementsUtil.referentOffset(INJECTED_METAACCESS);
    }

    public static class Templates extends SnippetTemplate.AbstractTemplates {
        private final SnippetTemplate.SnippetInfo shenandoahPreWriteBarrier;
        private final SnippetTemplate.SnippetInfo shenandoahPosWriteBarrier;
        private final SnippetTemplate.SnippetInfo shenandoahReferentReadBarrier;
        private final SnippetTemplate.SnippetInfo shenandoahArrayRangePreWriteBarrier;
        private final SnippetTemplate.SnippetInfo shenandoahLoadReferenceBarrier;

        private final ShenandoahBarrierLowerer lowerer;

        public Templates(OptionValues options, SnippetCounter.Group.Factory factory, HotSpotProviders providers, GraalHotSpotVMConfig config) {
            super(options, providers);
            this.lowerer = new HotSpotShenandoahBarrierSnippets.HotspotShenandoahBarrierLowerer(config, factory);

            //@formatter:off
            HotSpotShenandoahBarrierSnippets receiver = new HotSpotShenandoahBarrierSnippets(config, providers.getRegisters());
            shenandoahPreWriteBarrier = snippet(providers, ShenandoahBarrierSnippets.class, "shenandoahPreWriteBarrier", null, receiver, SATB_QUEUE_LOG_LOCATION, SATB_QUEUE_MARKING_ACTIVE_LOCATION, SATB_QUEUE_INDEX_LOCATION, SATB_QUEUE_BUFFER_LOCATION);
            shenandoahPosWriteBarrier = snippet(providers, ShenandoahBarrierSnippets.class, "shenandoahPosWriteBarrier", null, receiver, SATB_QUEUE_LOG_LOCATION, SATB_QUEUE_MARKING_ACTIVE_LOCATION, SATB_QUEUE_INDEX_LOCATION, SATB_QUEUE_BUFFER_LOCATION);
            shenandoahReferentReadBarrier = snippet(providers, ShenandoahBarrierSnippets.class, "shenandoahReferentReadBarrier", null, receiver, SATB_QUEUE_LOG_LOCATION, SATB_QUEUE_MARKING_ACTIVE_LOCATION, SATB_QUEUE_INDEX_LOCATION, SATB_QUEUE_BUFFER_LOCATION);
            shenandoahArrayRangePreWriteBarrier = snippet(providers, ShenandoahBarrierSnippets.class, "shenandoahArrayRangePreWriteBarrier", null, receiver, SATB_QUEUE_LOG_LOCATION, SATB_QUEUE_MARKING_ACTIVE_LOCATION, SATB_QUEUE_INDEX_LOCATION, SATB_QUEUE_BUFFER_LOCATION);
            shenandoahLoadReferenceBarrier = snippet(providers, ShenandoahBarrierSnippets.class, "shenandoahLoadReferenceBarrier", null, receiver, GC_STATE_LOCATION);
            //@formatter:on
        }

        public void lower(ShenandoahPreWriteBarrier barrier, LoweringTool tool) {
            lowerer.lower(this, shenandoahPreWriteBarrier, barrier, tool);
        }

        public void lower(ShenandoahPosWriteBarrier barrier, LoweringTool tool) {
            lowerer.lower(this, shenandoahPosWriteBarrier, barrier, tool);
        }

        public void lower(ShenandoahReferentFieldReadBarrier barrier, LoweringTool tool) {
            lowerer.lower(this, shenandoahReferentReadBarrier, barrier, tool);
        }

        public void lower(ShenandoahArrayRangePreWriteBarrier barrier, LoweringTool tool) {
            lowerer.lower(this, shenandoahArrayRangePreWriteBarrier, barrier, tool);
        }

        public void lower(ShenandoahLoadReferenceBarrierNode barrier, LoweringTool tool) {
            lowerer.lower(this, shenandoahLoadReferenceBarrier, barrier, tool);
        }
    }

    static final class HotspotShenandoahBarrierLowerer extends ShenandoahBarrierLowerer {
        private final CompressEncoding oopEncoding;

        HotspotShenandoahBarrierLowerer(GraalHotSpotVMConfig config, SnippetCounter.Group.Factory factory) {
            super(factory);
            oopEncoding = config.useCompressedOops ? config.getOopEncoding() : null;
        }

        @Override
        public ValueNode uncompress(ValueNode expected) {
            assert oopEncoding != null;
            return HotSpotCompressionNode.uncompress(expected.graph(), expected, oopEncoding);
        }
    }
}