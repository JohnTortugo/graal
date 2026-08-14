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
package com.oracle.svm.core.gc.shared;

import org.graalvm.nativeimage.Isolate;
import org.graalvm.nativeimage.IsolateThread;
import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;
import org.graalvm.nativeimage.c.function.CEntryPoint;
import org.graalvm.nativeimage.c.function.CEntryPoint.Publish;
import org.graalvm.nativeimage.c.function.CEntryPointLiteral;
import org.graalvm.nativeimage.c.function.CFunctionPointer;

import com.oracle.svm.core.Uninterruptible;
import com.oracle.svm.core.c.function.CEntryPointOptions;
import com.oracle.svm.core.thread.ThreadsLock;

/**
 * Entry points that let the GC-related C++ code acquire and release the SVM {@link ThreadsLock}
 * with (unspecified-owner) read access.
 *
 * This is the SubstrateVM analog of HotSpot's {@code Threads_lock} for the GC's concurrent
 * (outside-of-safepoint) GC-state transitions: a thread that is attaching takes the
 * {@link ThreadsLock} with write access while it is added to the thread list and initialized (see
 * {@code ShenandoahBarrierSet::on_thread_attach}), so a GC thread holding read access is mutually
 * exclusive with attach/detach and cannot race the per-thread GC-state initialization of an
 * attaching thread.
 *
 * The unspecified-owner variants are used because the caller is a GC C++ thread that is not an
 * attached {@link IsolateThread} (the corresponding {@code IsolateThread} argument is null).
 */
public class NativeGCThreadsLock {
    public final CEntryPointLiteral<CFunctionPointer> funcLockThreadsRead;
    public final CEntryPointLiteral<CFunctionPointer> funcUnlockThreadsRead;

    @Platforms(Platform.HOSTED_ONLY.class)
    public NativeGCThreadsLock() {
        funcLockThreadsRead = CEntryPointLiteral.create(NativeGCThreadsLock.class, "lockThreadsRead", Isolate.class, IsolateThread.class);
        funcUnlockThreadsRead = CEntryPointLiteral.create(NativeGCThreadsLock.class, "unlockThreadsRead", Isolate.class, IsolateThread.class);
    }

    @Uninterruptible(reason = "GC may only call uninterruptible code; the critical section has no safepoint checks.")
    @CEntryPoint(include = UseNativeGC.class, publishAs = Publish.NotPublished)
    @CEntryPointOptions(prologue = InitializeReservedRegistersForPossiblyUnattachedThread.class, epilogue = CEntryPointOptions.NoEpilogue.class)
    public static void lockThreadsRead(@SuppressWarnings("unused") Isolate isolate, @SuppressWarnings("unused") IsolateThread thread) {
        ThreadsLock.lockReadNoTransitionUnspecifiedOwner();
    }

    @Uninterruptible(reason = "GC may only call uninterruptible code; the critical section has no safepoint checks.")
    @CEntryPoint(include = UseNativeGC.class, publishAs = Publish.NotPublished)
    @CEntryPointOptions(prologue = InitializeReservedRegistersForPossiblyUnattachedThread.class, epilogue = CEntryPointOptions.NoEpilogue.class)
    public static void unlockThreadsRead(@SuppressWarnings("unused") Isolate isolate, @SuppressWarnings("unused") IsolateThread thread) {
        ThreadsLock.unlockReadNoTransitionUnspecifiedOwner();
    }
}
