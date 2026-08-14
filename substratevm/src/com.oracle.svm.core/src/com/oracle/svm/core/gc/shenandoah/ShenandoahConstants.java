/*
 * Copyright (c) 2025, 2025, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.core.gc.shenandoah;

import static com.oracle.svm.core.gc.shenandoah.ShenandoahOptions.ShenandoahRegionSize;

import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;

import com.oracle.svm.core.gc.shared.NativeGCOptions;
import com.oracle.svm.core.threadlocal.VMThreadLocalOffsetProvider;

import jdk.graal.compiler.api.replacements.Fold;
import jdk.vm.ci.code.CodeUtil;

/**
 * Defines Shenandoah-specific constants that are used during code generation. If the value of a
 * constant depends on the debug-level of the linked Shenandoah library, the constant is defined as
 * an array of values (i.e., one value per debug-level).
 */
public class ShenandoahConstants {
    private static final int TLAB_TOP_OFFSET = 384;
    private static final int TLAB_END_OFFSET = 400;
    private static final byte DIRTY_CARD_VALUE = 0;
    private static final int[] JAVA_THREAD_SIZE = {560, 592, 592}; // product, fastdebug, debug

    /*
     * Byte offsets of the SATB mark queue's {@code _index} and {@code _buf} fields, relative to the
     * per-thread {@code gc_state} byte (see {@link #gcStateOffset()}). Both fields live in the same
     * ShenandoahThreadLocalData blob as gc_state, so the inlined SATB pre-write barrier can address
     * them off the thread register at {@code gcStateOffset() + <rel>}. These must match the C++
     * ShenandoahThreadLocalData layout; they are validated at startup against ShenandoahInitState
     * (see ShenandoahHeap validation).
     */
    private static final int SATB_INDEX_OFFSET_REL = 8;
    private static final int SATB_BUFFER_OFFSET_REL = 16;

    /*
     * Offset of the C++ Thread::_gc_data blob (which holds ShenandoahThreadLocalData, whose first
     * field is gc_state) within the embedded JavaThread. javaThreadTL points at the START of the
     * JavaThread, but gc_state lives at JavaThread+gc_data_offset, so gcStateOffset() must add this.
     * The value is validated at startup against ShenandoahInitState.gcStateOffset().
     */
    private static final int[] JAVA_THREAD_GC_STATE_OFFSET = {8, 8, 8}; // product, fastdebug, debug

    @Fold
    public static int javaThreadGcStateOffset() {
        return JAVA_THREAD_GC_STATE_OFFSET[debugLevelIndex()];
    }

    @Fold
    public static int tlabTopOffset() {
        return TLAB_TOP_OFFSET;
    }

    @Fold
    public static int tlabEndOffset() {
        return TLAB_END_OFFSET;
    }

    @Fold
    public static byte dirtyCardValue() {
        return DIRTY_CARD_VALUE;
    }

    @Fold
    public static int cardTableShift() {
        return CodeUtil.log2(NativeGCOptions.GCCardSizeInBytes.getValue());
    }

    @Fold
    public static int cardSize() {
        return NativeGCOptions.GCCardSizeInBytes.getValue();
    }

    @Fold
    public static int javaThreadSize() {
        return JAVA_THREAD_SIZE[debugLevelIndex()];
    }

    @Fold
    public static int logOfHeapRegionGrainBytes() {
        return CodeUtil.log2(ShenandoahRegionSize.getValue());
    }

    /*
     * Shenandoah per-thread gc_state bit masks. These must match the GCState enum in the C++
     * ShenandoahHeap (shenandoahHeap.hpp).
     */
    public static final int GC_STATE_HAS_FORWARDED = 1;
    public static final int GC_STATE_MARKING = 2;
    public static final int GC_STATE_EVACUATION = 4;
    public static final int GC_STATE_UPDATE_REFS = 8;
    public static final int GC_STATE_WEAK_ROOTS = 16;

    /*
     * Shenandoah concurrent-evacuation forwarding encoding in the object's mark word (see the C++
     * ShenandoahForwarding / markWord): a set low bit (marked_value) means the object has been
     * evacuated and the mark word holds the (lock-bit-tagged) forwardee pointer; the forwardee is
     * {@code markWord & ~MARK_FORWARDED_MASK}. The mark word is the first object field, hence at
     * offset 0 (validated against ShenandoahInitState at startup).
     */
    public static final int MARK_FORWARDED_MASK = 1;
    private static final int MARK_OFFSET = 0;

    @Fold
    public static int markOffset() {
        return MARK_OFFSET;
    }

    /**
     * Offset (from the current {@code IsolateThread}) of the per-thread Shenandoah {@code _gc_state}
     * byte. It is the first field of the {@code ShenandoahThreadLocalData} blob mirrored by
     * {@link ShenandoahHeap#javaThreadTL}, so its offset equals the offset of that thread local.
     */
    public static int gcStateOffset() {
        return VMThreadLocalOffsetProvider.getOffset(ShenandoahHeap.javaThreadTL) + javaThreadGcStateOffset();
    }

    /**
     * Offset (from the current {@code IsolateThread}) of the SATB mark queue's {@code _index} field,
     * used by the inlined SATB pre-write barrier's buffer write.
     */
    public static int satbIndexOffset() {
        return gcStateOffset() + SATB_INDEX_OFFSET_REL;
    }

    /**
     * Offset (from the current {@code IsolateThread}) of the SATB mark queue's {@code _buf} field.
     */
    public static int satbBufferOffset() {
        return gcStateOffset() + SATB_BUFFER_OFFSET_REL;
    }

    /**
     * Offset (from the current {@code IsolateThread}) of the per-thread word holding the base of the
     * Shenandoah collection-set fast-test map (see {@link ShenandoahHeap#csetMapAddressTL}). Used by
     * the inlined CAS heal barrier to test collection-set membership without a runtime call.
     */
    public static int csetMapAddressOffset() {
        return VMThreadLocalOffsetProvider.getOffset(ShenandoahHeap.csetMapAddressTL);
    }

    /** SATB {@code _index}/{@code _buf} offsets relative to gc_state (for startup validation). */
    @Fold
    public static int satbIndexOffsetRel() {
        return SATB_INDEX_OFFSET_REL;
    }

    @Fold
    public static int satbBufferOffsetRel() {
        return SATB_BUFFER_OFFSET_REL;
    }

    @Platforms(Platform.HOSTED_ONLY.class)
    private static int debugLevelIndex() {
        return ShenandoahOptions.getDebugLevel().getIndex();
    }
}
