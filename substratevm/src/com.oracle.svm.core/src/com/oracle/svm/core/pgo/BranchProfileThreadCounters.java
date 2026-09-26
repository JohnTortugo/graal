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

import org.graalvm.nativeimage.CurrentIsolate;
import org.graalvm.nativeimage.IsolateThread;
import org.graalvm.word.Pointer;
import org.graalvm.word.impl.Word;

import com.oracle.svm.core.heap.VMOperationInfos;
import com.oracle.svm.core.thread.JavaVMOperation;
import com.oracle.svm.core.thread.VMThreads;
import com.oracle.svm.guest.staging.core.memory.UntrackedNullableNativeMemory;
import com.oracle.svm.guest.staging.core.thread.ThreadListener;
import com.oracle.svm.guest.staging.core.threadlocal.FastThreadLocalFactory;
import com.oracle.svm.guest.staging.core.threadlocal.FastThreadLocalWord;
import com.oracle.svm.shared.Uninterruptible;

/**
 * Per-thread storage for conditional branch counters.
 *
 * Instrumented code increments a private native block owned by the executing thread, so hot
 * branches executed concurrently on many cores never write the same cache line. The block is
 * allocated when the thread is attached, folded into the shared totals when the thread exits, and
 * read in place for still-running threads when a profile snapshot is taken.
 */
public final class BranchProfileThreadCounters implements ThreadListener {

    /** Native block of {@code registeredSlots} longs, or null when the fallback array is used. */
    static final FastThreadLocalWord<Pointer> COUNTERS = FastThreadLocalFactory.createWord("BranchProfileThreadCounters.counters");

    private BranchProfileThreadCounters() {
    }

    public static BranchProfileThreadCounters create() {
        return new BranchProfileThreadCounters();
    }

    @Override
    @Uninterruptible(reason = "Only uninterruptible code may be executed before the thread is fully started.")
    public void afterThreadStart(IsolateThread isolateThread, Thread javaThread) {
        allocate(isolateThread);
    }

    @Override
    public void beforeThreadRun() {
        /* Covers threads whose IsolateThread changed after afterThreadStart (main thread handoff). */
        allocate(CurrentIsolate.getCurrentThread());
    }

    @Override
    @Uninterruptible(reason = "Only uninterruptible code may be executed after Thread.exit.")
    public void afterThreadExit(IsolateThread isolateThread, Thread javaThread) {
        Pointer block = COUNTERS.get(isolateThread);
        if (block.isNonNull()) {
            BranchProfileRecorder.mergeIntoShared(block);
            COUNTERS.set(isolateThread, Word.nullPointer());
            UntrackedNullableNativeMemory.free(block);
        }
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static void allocate(IsolateThread isolateThread) {
        if (COUNTERS.get(isolateThread).isNonNull()) {
            return;
        }
        int slots = BranchProfileRecorder.registeredSlots();
        if (slots <= 0) {
            return;
        }
        /* calloc keeps untouched pages unmapped, so sparse counter use costs little memory. */
        Pointer block = UntrackedNullableNativeMemory.calloc(Word.unsigned(slots).multiply(Long.BYTES));
        COUNTERS.set(isolateThread, block);
    }

    /** Adds every live thread's private counters into {@code target} at a safepoint. */
    public static void collectLiveThreads(long[] target) {
        CollectOperation operation = new CollectOperation(target);
        operation.enqueue();
    }

    private static final class CollectOperation extends JavaVMOperation {
        private final long[] target;

        CollectOperation(long[] target) {
            super(VMOperationInfos.get(CollectOperation.class, "Collect PGO branch counters", SystemEffect.SAFEPOINT));
            this.target = target;
        }

        @Override
        protected void operate() {
            int slots = BranchProfileRecorder.registeredSlots();
            for (IsolateThread thread = VMThreads.firstThread(); thread.isNonNull(); thread = VMThreads.nextThread(thread)) {
                Pointer block = COUNTERS.get(thread);
                if (block.isNull()) {
                    continue;
                }
                for (int slot = 0; slot < slots; slot++) {
                    target[slot] += block.readLong(slot * Long.BYTES);
                }
            }
        }
    }
}
