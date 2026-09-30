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
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.graalvm.nativeimage.IsolateThread;
import org.graalvm.word.Pointer;
import org.graalvm.word.impl.Word;

import com.oracle.svm.core.heap.VMOperationInfos;
import com.oracle.svm.core.thread.JavaVMOperation;
import com.oracle.svm.core.thread.VMThreads;
import com.oracle.svm.guest.staging.c.CGlobalData;
import com.oracle.svm.guest.staging.c.CGlobalDataFactory;
import com.oracle.svm.guest.staging.core.memory.UntrackedNullableNativeMemory;
import com.oracle.svm.guest.staging.core.thread.ThreadListener;
import com.oracle.svm.guest.staging.core.threadlocal.FastThreadLocalFactory;
import com.oracle.svm.guest.staging.core.threadlocal.FastThreadLocalWord;
import com.oracle.svm.shared.Uninterruptible;
import com.oracle.svm.shared.util.SubstrateUtil;

import jdk.internal.misc.Unsafe;

/** Sparse per-thread runtime storage for exact post-inlining call-edge counts. */
public final class CallCountProfileRecorder implements ThreadListener {
    private static final int MAX_COUNTERS = 1_000_000;
    private static final int THREAD_BUCKETS = 8_192;
    private static final int SHARED_BUCKETS = 262_144;
    private static final int HEADER_BYTES = 16;
    private static final int TOTAL_OFFSET = 0;
    private static final int DROPPED_OFFSET = 8;
    private static final int BUCKET_BYTES = 16;
    private static final int BUCKET_KEY = 0;
    private static final int BUCKET_COUNT = 8;
    private static final int THREAD_TABLE_BYTES = HEADER_BYTES + THREAD_BUCKETS * BUCKET_BYTES;
    private static final int SHARED_TABLE_BYTES = HEADER_BYTES + SHARED_BUCKETS * BUCKET_BYTES;

    private static final Unsafe UNSAFE = Unsafe.getUnsafe();
    private static final FastThreadLocalWord<Pointer> TABLE = FastThreadLocalFactory.createWord("CallCountProfileRecorder.table");
    private static final CGlobalData<Pointer> SHARED = CGlobalDataFactory.createBytes(() -> SHARED_TABLE_BYTES, "__svm_pgo_call_counts");
    private static final CGlobalData<Pointer> SHARED_LOCK = CGlobalDataFactory.createWord();
    private static final ConcurrentMap<Integer, CallCountProfileCounter> COUNTERS = new ConcurrentHashMap<>();
    private static final AtomicInteger NEXT_INDEX = new AtomicInteger();
    private static final ConcurrentMap<Integer, Long> HOSTED_COUNTS = SubstrateUtil.HOSTED ? new ConcurrentHashMap<>() : null;
    private static final CallCountProfileCounter UNUSED_COUNTER = create(new String[]{"Lcom/oracle/svm/core/pgo/CallCountProfileRecorder;.__unused__()V"}, new int[]{0});

    private static boolean enabled;
    private static Map<Integer, Long> snapshot;
    private static long snapshotTotal;
    private static long snapshotDropped;

    private CallCountProfileRecorder() {
    }

    public static CallCountProfileRecorder createRecorder() {
        enabled = true;
        return new CallCountProfileRecorder();
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static int allocateRawCounter() {
        int index = NEXT_INDEX.getAndIncrement();
        if (index >= MAX_COUNTERS) {
            throw new IllegalStateException("Profile instrumentation exceeds the " + MAX_COUNTERS + "-counter capacity");
        }
        return index;
    }

    public static long getRawCount(int counterIndex) {
        return getCount(counterIndex);
    }

    public static CallCountProfileCounter create(String[] methodDescriptors, int[] contextBcis) {
        if (methodDescriptors.length == 0 || methodDescriptors.length != contextBcis.length) {
            throw new IllegalArgumentException("Call-count context must contain matching non-empty arrays");
        }
        int index = NEXT_INDEX.getAndIncrement();
        if (index >= MAX_COUNTERS) {
            throw new IllegalStateException("Call-count instrumentation exceeds the " + MAX_COUNTERS + "-site capacity");
        }
        CallCountProfileCounter counter = new CallCountProfileCounter(index, methodDescriptors, contextBcis);
        COUNTERS.put(index, counter);
        return counter;
    }

    @Override
    @Uninterruptible(reason = "Thread state not set up.")
    public void afterThreadAttach(IsolateThread isolateThread) {
        if (TABLE.get(isolateThread).isNull()) {
            TABLE.set(isolateThread, UntrackedNullableNativeMemory.calloc(Word.unsigned(THREAD_TABLE_BYTES)));
        }
    }

    @Override
    @Uninterruptible(reason = "Only uninterruptible code may be executed after Thread.exit.")
    public void afterThreadExit(IsolateThread isolateThread, Thread javaThread) {
        Pointer table = TABLE.get(isolateThread);
        if (table.isNonNull()) {
            mergeIntoShared(table);
            TABLE.set(isolateThread, Word.nullPointer());
            UntrackedNullableNativeMemory.free(table);
        }
    }

    public static void increment(int counterIndex) {
        if (SubstrateUtil.HOSTED) {
            HOSTED_COUNTS.merge(counterIndex, 1L, Long::sum);
            return;
        }
        incrementRuntime(counterIndex);
    }

    @Uninterruptible(reason = "Counter key and total publication must be observed atomically by a safepoint snapshot.")
    static void incrementRuntime(int counterIndex) {
        /* Inlined unsigned widening: Integer.toUnsignedLong is not @Uninterruptible. */
        long key = (counterIndex + 1) & 0xffffffffL;
        Pointer table = TABLE.get();
        if (table.isNonNull() && addToTable(table, THREAD_BUCKETS, key, 1)) {
            table.writeLong(TOTAL_OFFSET, table.readLong(TOTAL_OFFSET) + 1);
        } else {
            addDirectlyToShared(key, 1);
        }
    }

    @Uninterruptible(reason = "Operates only on native counter tables.")
    private static boolean addToTable(Pointer table, int buckets, long key, long count) {
        int index = mix(key) & (buckets - 1);
        for (int probe = 0; probe < buckets; probe++) {
            int bucket = HEADER_BYTES + index * BUCKET_BYTES;
            long existing = table.readLong(bucket + BUCKET_KEY);
            if (existing == key) {
                table.writeLong(bucket + BUCKET_COUNT, table.readLong(bucket + BUCKET_COUNT) + count);
                return true;
            }
            if (existing == 0) {
                table.writeLong(bucket + BUCKET_COUNT, count);
                table.writeLong(bucket + BUCKET_KEY, key);
                return true;
            }
            index = (index + 1) & (buckets - 1);
        }
        return false;
    }

    @Uninterruptible(reason = "Operates only on native counter tables.")
    private static int mix(long key) {
        long value = key;
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdL;
        value ^= value >>> 33;
        return (int) value;
    }

    @Uninterruptible(reason = "Shared fallback must not be stopped while holding the native merge lock.")
    private static void addDirectlyToShared(long key, long count) {
        lockShared();
        Pointer shared = SHARED.get();
        if (addToTable(shared, SHARED_BUCKETS, key, count)) {
            addSharedHeader(TOTAL_OFFSET, count);
        } else {
            addSharedHeader(TOTAL_OFFSET, count);
            addSharedHeader(DROPPED_OFFSET, count);
        }
        unlockShared();
    }

    @Uninterruptible(reason = "Called after Thread.exit or at a safepoint.")
    private static void mergeIntoShared(Pointer source) {
        lockShared();
        Pointer shared = SHARED.get();
        addSharedHeader(TOTAL_OFFSET, source.readLong(TOTAL_OFFSET));
        for (int index = 0; index < THREAD_BUCKETS; index++) {
            int bucket = HEADER_BYTES + index * BUCKET_BYTES;
            long key = source.readLong(bucket + BUCKET_KEY);
            if (key != 0) {
                long count = source.readLong(bucket + BUCKET_COUNT);
                if (!addToTable(shared, SHARED_BUCKETS, key, count)) {
                    addSharedHeader(DROPPED_OFFSET, count);
                }
            }
        }
        unlockShared();
    }

    @Uninterruptible(reason = "Native spin lock for rare fallback, exit, and snapshot merges.")
    private static void lockShared() {
        Pointer lock = SHARED_LOCK.get();
        while (!UNSAFE.compareAndSetInt(null, lock.rawValue(), 0, 1)) {
            // Private-table increments never acquire this lock.
        }
    }

    @Uninterruptible(reason = "Native spin lock for rare fallback, exit, and snapshot merges.")
    private static void unlockShared() {
        UNSAFE.putIntRelease(null, SHARED_LOCK.get().rawValue(), 0);
    }

    @Uninterruptible(reason = "May be called while a thread is attaching or exiting.")
    private static void addSharedHeader(int offset, long count) {
        if (count != 0) {
            UNSAFE.getAndAddLong(null, SHARED.get().add(offset).rawValue(), count);
        }
    }

    private static void takeSnapshot() {
        if (SubstrateUtil.HOSTED) {
            snapshot = new HashMap<>(HOSTED_COUNTS);
            snapshotTotal = HOSTED_COUNTS.values().stream().mapToLong(Long::longValue).sum();
            snapshotDropped = 0;
            return;
        }
        long[] keys = new long[SHARED_BUCKETS];
        long[] counts = new long[SHARED_BUCKETS];
        SnapshotOperation operation = new SnapshotOperation(keys, counts);
        operation.enqueue();
        Map<Integer, Long> copied = new HashMap<>();
        for (int index = 0; index < SHARED_BUCKETS; index++) {
            if (keys[index] != 0) {
                copied.put((int) keys[index] - 1, counts[index]);
            }
        }
        snapshotTotal = operation.total;
        snapshotDropped = operation.dropped;
        snapshot = copied;
    }

    private static final class SnapshotOperation extends JavaVMOperation {
        private final long[] keys;
        private final long[] counts;
        private long total;
        private long dropped;

        SnapshotOperation(long[] keys, long[] counts) {
            super(VMOperationInfos.get(SnapshotOperation.class, "Collect PGO call counts", SystemEffect.SAFEPOINT));
            this.keys = keys;
            this.counts = counts;
        }

        @Override
        protected void operate() {
            for (IsolateThread thread = VMThreads.firstThread(); thread.isNonNull(); thread = VMThreads.nextThread(thread)) {
                Pointer table = TABLE.get(thread);
                if (table.isNonNull()) {
                    mergeIntoShared(table);
                    table.writeLong(TOTAL_OFFSET, 0);
                    for (int index = 0; index < THREAD_BUCKETS; index++) {
                        int bucket = HEADER_BYTES + index * BUCKET_BYTES;
                        table.writeLong(bucket + BUCKET_KEY, 0);
                        table.writeLong(bucket + BUCKET_COUNT, 0);
                    }
                }
            }
            Pointer shared = SHARED.get();
            total = shared.readLong(TOTAL_OFFSET);
            dropped = shared.readLong(DROPPED_OFFSET);
            for (int index = 0; index < SHARED_BUCKETS; index++) {
                int bucket = HEADER_BYTES + index * BUCKET_BYTES;
                keys[index] = shared.readLong(bucket + BUCKET_KEY);
                counts[index] = shared.readLong(bucket + BUCKET_COUNT);
            }
        }
    }

    static long getCount(int counterIndex) {
        if (snapshot == null) {
            takeSnapshot();
        }
        return snapshot.getOrDefault(counterIndex, 0L);
    }

    public static List<CallCountProfileCounter> getCounters() {
        List<CallCountProfileCounter> result = new ArrayList<>();
        for (CallCountProfileCounter counter : COUNTERS.values()) {
            if (counter != UNUSED_COUNTER) {
                result.add(counter);
            }
        }
        result.sort(Comparator.comparing((CallCountProfileCounter counter) -> String.join("\u0000", counter.methodDescriptors()))
                        .thenComparing(counter -> java.util.Arrays.toString(counter.contextBcis())));
        return result;
    }

    static void refreshHostedSnapshotForTesting() {
        if (SubstrateUtil.HOSTED) {
            snapshot = null;
        }
    }

    public static void prepareSnapshot() {
        takeSnapshot();
    }

    public static Statistics statistics() {
        if (snapshot == null) {
            takeSnapshot();
        }
        long attributed = 0;
        int mappedKeys = 0;
        for (Map.Entry<Integer, Long> entry : snapshot.entrySet()) {
            if (COUNTERS.containsKey(entry.getKey())) {
                mappedKeys++;
                attributed += entry.getValue();
            }
        }
        return new Statistics(COUNTERS.size() - 1, snapshotTotal, snapshotDropped, snapshot.size(), mappedKeys, attributed);
    }

    public record Statistics(int physicalSites, long events, long dropped, int keys, int mappedKeys, long attributedEvents) {
    }
}
