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
package com.oracle.svm.core.gc.shenandoah.graal;

import static com.oracle.svm.core.Uninterruptible.CALLED_FROM_UNINTERRUPTIBLE_CODE;
import static jdk.graal.compiler.core.common.spi.ForeignCallDescriptor.CallSideEffect.HAS_SIDE_EFFECT;

import org.graalvm.word.Pointer;

import com.oracle.svm.core.Uninterruptible;
import com.oracle.svm.core.gc.shenandoah.nativelib.ShenandoahLibrary;
import com.oracle.svm.core.snippets.SnippetRuntime;
import com.oracle.svm.core.snippets.SnippetRuntime.SubstrateForeignCallDescriptor;
import com.oracle.svm.core.snippets.SubstrateForeignCallTarget;

import jdk.graal.compiler.word.Word;

/**
 * Foreign call bridges for the Shenandoah barriers emitted by the Graal AOT compiler (see
 * {@link com.oracle.svm.core.graal.amd64.AMD64SubstrateShenandoahBarrierSetLIRGenerator}).
 *
 * The AOT-generated barrier code inlines a fast-path that tests the per-thread {@code gc_state} and
 * only calls these (uninterruptible, leaf) stubs on the slow path, i.e. when a barrier is actually
 * required. Each stub forwards into the Shenandoah C++ library, which additionally re-checks the
 * relevant {@code gc_state} condition defensively, so invoking a stub when no barrier is needed is
 * still harmless.
 */
public final class ShenandoahBarrierSupport {

    public static final SubstrateForeignCallDescriptor PRE_WRITE_BARRIER_NARROW = SnippetRuntime.findForeignCall(ShenandoahBarrierSupport.class, "preWriteBarrierNarrow", HAS_SIDE_EFFECT);
    public static final SubstrateForeignCallDescriptor PRE_WRITE_BARRIER_WIDE = SnippetRuntime.findForeignCall(ShenandoahBarrierSupport.class, "preWriteBarrierWide", HAS_SIDE_EFFECT);
    public static final SubstrateForeignCallDescriptor LOAD_REFERENCE_BARRIER = SnippetRuntime.findForeignCall(ShenandoahBarrierSupport.class, "loadReferenceBarrier", HAS_SIDE_EFFECT);
    public static final SubstrateForeignCallDescriptor LOAD_REFERENCE_BARRIER_WEAK = SnippetRuntime.findForeignCall(ShenandoahBarrierSupport.class, "loadReferenceBarrierWeak", HAS_SIDE_EFFECT);
    public static final SubstrateForeignCallDescriptor LOAD_REFERENCE_BARRIER_PHANTOM = SnippetRuntime.findForeignCall(ShenandoahBarrierSupport.class, "loadReferenceBarrierPhantom", HAS_SIDE_EFFECT);
    public static final SubstrateForeignCallDescriptor LOAD_REFERENCE_BARRIER_HEAL = SnippetRuntime.findForeignCall(ShenandoahBarrierSupport.class, "loadReferenceBarrierHeal", HAS_SIDE_EFFECT);

    public static final SubstrateForeignCallDescriptor[] FOREIGN_CALLS = {
                    PRE_WRITE_BARRIER_NARROW, PRE_WRITE_BARRIER_WIDE, LOAD_REFERENCE_BARRIER, LOAD_REFERENCE_BARRIER_WEAK, LOAD_REFERENCE_BARRIER_PHANTOM, LOAD_REFERENCE_BARRIER_HEAL};

    public static void registerForeignCalls(com.oracle.svm.core.graal.meta.SubstrateForeignCallsProvider foreignCalls) {
        foreignCalls.register(FOREIGN_CALLS);
    }

    /**
     * SATB pre-write barrier for a compressed reference field. {@code narrowPreviousValue} is the
     * previous (compressed) value of the field that is about to be overwritten; it is enqueued for
     * concurrent marking (if marking is active). The value is pointer-width: on Graal CE a narrow
     * reference is an 8-byte heap-base-relative offset, so it must not be truncated to 32 bits.
     */
    @SubstrateForeignCallTarget(stubCallingConvention = true, fullyUninterruptible = true)
    @Uninterruptible(reason = CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    public static void preWriteBarrierNarrow(Word narrowPreviousValue) {
        ShenandoahLibrary.preWriteBarrierNarrowStub(narrowPreviousValue);
    }

    /**
     * SATB pre-write barrier for an uncompressed reference field. {@code previousValue} is the
     * previous (uncompressed) value of the field that is about to be overwritten.
     */
    @SubstrateForeignCallTarget(stubCallingConvention = true, fullyUninterruptible = true)
    @Uninterruptible(reason = CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    public static void preWriteBarrierWide(Word previousValue) {
        ShenandoahLibrary.preWriteBarrierStub(previousValue);
    }

    /**
     * Load-reference barrier. Given the (uncompressed) reference {@code obj} that was just loaded,
     * returns the canonical (to-space) reference. {@code loadAddr} is the address of the memory
     * location the reference was loaded from (or null if unknown); if the reference was stale
     * (from-space), the runtime additionally self-heals that location by CAS-ing in the to-space
     * value, so subsequent loads of the same slot take the inline fast path.
     */
    @SubstrateForeignCallTarget(stubCallingConvention = true, fullyUninterruptible = true)
    @Uninterruptible(reason = CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    public static Object loadReferenceBarrier(Object obj, Word loadAddr) {
        Word resolved = ShenandoahLibrary.loadReferenceBarrierStub(Word.objectToUntrackedPointer(obj), loadAddr);
        return ((Pointer) resolved).toObject();
    }

    /**
     * Load-reference barrier for a referent loaded from a WEAK reference. Besides canonicalizing a
     * from-space reference, it prevents resurrection: during the concurrent weak-roots phase a load
     * of an unreachable (unmarked) referent returns null, mirroring the decorated C++ barrier
     * (ON_WEAK_OOP_REF). See {@code svm_gc_load_reference_barrier_weak}.
     */
    @SubstrateForeignCallTarget(stubCallingConvention = true, fullyUninterruptible = true)
    @Uninterruptible(reason = CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    public static Object loadReferenceBarrierWeak(Object obj, Word loadAddr) {
        Word resolved = ShenandoahLibrary.loadReferenceBarrierWeakStub(Word.objectToUntrackedPointer(obj), loadAddr);
        return ((Pointer) resolved).toObject();
    }

    /**
     * Load-reference barrier for a referent loaded from a PHANTOM reference (weak-native access).
     * Like the weak variant, but filters unreachable referents with is_marked (any strength). See
     * {@code svm_gc_load_reference_barrier_phantom}.
     */
    @SubstrateForeignCallTarget(stubCallingConvention = true, fullyUninterruptible = true)
    @Uninterruptible(reason = CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    public static Object loadReferenceBarrierPhantom(Object obj, Word loadAddr) {
        Word resolved = ShenandoahLibrary.loadReferenceBarrierPhantomStub(Word.objectToUntrackedPointer(obj), loadAddr);
        return ((Pointer) resolved).toObject();
    }

    /**
     * Self-healing load-reference barrier for an oop field that is about to be atomically updated
     * (compare-and-swap / getAndSet). Given the field {@code address}, the stub reads the current
     * value, resolves it to its canonical (to-space) location and heals the field in place, so that a
     * subsequent plain atomic sees the to-space value and cannot suffer a concurrent-evacuation false
     * negative. Only invoked on the slow path, i.e. when the heap has forwarded objects.
     */
    @SubstrateForeignCallTarget(stubCallingConvention = true, fullyUninterruptible = true)
    @Uninterruptible(reason = CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    public static void loadReferenceBarrierHeal(Word address) {
        ShenandoahLibrary.loadReferenceBarrierHealStub(address);
    }
}
