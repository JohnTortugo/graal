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

import static com.oracle.svm.core.snippets.SnippetRuntime.findForeignCall;
import static jdk.graal.compiler.core.common.spi.ForeignCallDescriptor.CallSideEffect.NO_SIDE_EFFECT;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.graalvm.nativeimage.IsolateThread;
import org.graalvm.word.LocationIdentity;
import org.graalvm.word.Pointer;
import org.graalvm.word.impl.Word;

import com.oracle.svm.core.heap.VMOperationInfos;
import com.oracle.svm.core.snippets.SnippetRuntime.SubstrateForeignCallDescriptor;
import com.oracle.svm.core.thread.JavaVMOperation;
import com.oracle.svm.core.thread.VMThreads;
import com.oracle.svm.guest.staging.c.CGlobalData;
import com.oracle.svm.guest.staging.c.CGlobalDataFactory;
import com.oracle.svm.guest.staging.core.memory.UntrackedNullableNativeMemory;
import com.oracle.svm.guest.staging.core.thread.ThreadListener;
import com.oracle.svm.guest.staging.core.threadlocal.FastThreadLocalFactory;
import com.oracle.svm.guest.staging.core.threadlocal.FastThreadLocalWord;
import com.oracle.svm.guest.staging.snippets.SubstrateForeignCallTarget;
import com.oracle.svm.shared.Uninterruptible;
import com.oracle.svm.shared.util.SubstrateUtil;

import jdk.graal.compiler.core.common.spi.ForeignCallDescriptor;
import jdk.graal.compiler.graph.Node.ConstantNodeParameter;
import jdk.graal.compiler.graph.Node.NodeIntrinsic;
import jdk.graal.compiler.nodes.NamedLocationIdentity;
import jdk.graal.compiler.nodes.extended.ForeignCallNode;
import jdk.internal.misc.Unsafe;

/**
 * Sparse per-thread runtime storage for exact call-edge counts.
 *
 * Every thread owns an open-addressing table (key, count) that starts at {@link #INITIAL_BUCKETS}
 * and doubles whenever it is half full, up to {@link #MAX_BUCKETS}. The fast path probes at most
 * {@link #MAX_PROBES} buckets and never allocates; everything else (growing, a long probe chain, a
 * table at its cap, a thread without a table) goes through one foreign call. Exited threads and the
 * dump-time snapshot fold private tables into one shared table of the same layout that grows under a
 * lock, so no key is dropped for lack of space.
 */
public final class CallCountProfileRecorder implements ThreadListener {
    private static final int MAX_COUNTERS = 1_000_000;
    /** Buckets of a fresh per-thread table (128 KB). */
    private static final int INITIAL_BUCKETS = 8_192;
    /** Largest per-thread table (4 MB); keys that do not fit beyond this go to the shared table. */
    private static final int MAX_BUCKETS = 262_144;
    /** Buckets of a fresh shared table. */
    private static final int INITIAL_SHARED_BUCKETS = 262_144;
    /** Longest probe chain the inlined fast path follows before it calls the slow path. */
    private static final int MAX_PROBES = 8;
    private static final int HEADER_BYTES = 32;
    private static final int TOTAL_OFFSET = 0;
    private static final int DROPPED_OFFSET = 8;
    /** Bucket count minus one; swapped together with the table pointer on growth. */
    private static final int MASK_OFFSET = 16;
    /** Occupied buckets; a table grows when this reaches half the bucket count. */
    private static final int SIZE_OFFSET = 24;
    private static final int BUCKET_BYTES = 16;
    private static final int BUCKET_KEY = 0;
    private static final int BUCKET_COUNT = 8;

    private static final Unsafe UNSAFE = Unsafe.getUnsafe();
    private static final FastThreadLocalWord<Pointer> TABLE = FastThreadLocalFactory.createWord("CallCountProfileRecorder.table");

    /** Location of the per-thread table pointer; a snippet that reads it must declare it private. */
    public static LocationIdentity tablePointerLocation() {
        return TABLE.getLocationIdentity();
    }

    /**
     * Location of the table contents. The tables are native memory that is never aliased with any
     * Java-visible memory, so the fast path accesses them under this name and a snippet that contains
     * the fast path declares it private. The slow path runs as {@link #INCREMENT_SLOW_PATH}, which
     * kills only this location and {@link #tablePointerLocation()} (it may replace the table).
     */
    public static final LocationIdentity TABLE_LOCATION = NamedLocationIdentity.mutable("PGOCallCountTables");

    public static final SubstrateForeignCallDescriptor INCREMENT_SLOW_PATH = findForeignCall(CallCountProfileRecorder.class, "incrementSlowPath", NO_SIDE_EFFECT, TABLE_LOCATION,
                    TABLE.getLocationIdentity());
    /** Pointer to the shared table; allocated lazily and replaced on growth, always under {@link #SHARED_LOCK}. */
    private static final CGlobalData<Pointer> SHARED = CGlobalDataFactory.createWord();
    private static final CGlobalData<Pointer> SHARED_LOCK = CGlobalDataFactory.createWord();
    /** Events that could not be placed anywhere (table allocation failed). */
    private static final CGlobalData<Pointer> SHARED_DROPPED = CGlobalDataFactory.createWord();
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
            TABLE.set(isolateThread, allocateTable(INITIAL_BUCKETS));
        }
    }

    @Override
    @Uninterruptible(reason = "Only uninterruptible code may be executed after Thread.exit.")
    public void afterThreadExit(IsolateThread isolateThread, Thread javaThread) {
        Pointer table = TABLE.get(isolateThread);
        if (table.isNonNull()) {
            mergeIntoShared(table, false);
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

    /**
     * Snippet body: no hosted branch, nothing that can throw, no allocation, and memory accesses only
     * to {@link #tablePointerLocation()} and {@link #TABLE_LOCATION}, so that the node lowering to it
     * need not be a memory kill. Everything rare is the separately compiled foreign call.
     */
    @Uninterruptible(reason = "Counter key and total publication must be observed atomically by a safepoint snapshot.")
    public static void incrementRuntime(int counterIndex) {
        /* Inlined unsigned widening: Integer.toUnsignedLong is not @Uninterruptible. */
        long key = (counterIndex + 1) & 0xffffffffL;
        Pointer table = TABLE.get();
        if (table.isNonNull() && addBounded(table, key)) {
            table.writeLong(TOTAL_OFFSET, table.readLong(TOTAL_OFFSET, TABLE_LOCATION) + 1, TABLE_LOCATION);
        } else {
            callSlowPath(INCREMENT_SLOW_PATH, key);
        }
    }

    @NodeIntrinsic(value = ForeignCallNode.class)
    private static native void callSlowPath(@ConstantNodeParameter ForeignCallDescriptor descriptor, long key);

    /**
     * Rare path: grows the private table when it is half full, inserts with an unbounded probe, and
     * spills to the shared table only when the private table is at its cap or missing.
     */
    @SubstrateForeignCallTarget(stubCallingConvention = false, fullyUninterruptible = true)
    @Uninterruptible(reason = "May replace the thread's table and writes native counter memory; needs no deoptimization state.")
    private static void incrementSlowPath(long key) {
        Pointer table = TABLE.get();
        if (table.isNonNull()) {
            table = growIfHalfFull(table);
            if (addUnbounded(table, key, 1)) {
                table.writeLong(TOTAL_OFFSET, table.readLong(TOTAL_OFFSET, TABLE_LOCATION) + 1, TABLE_LOCATION);
                return;
            }
        }
        addDirectlyToShared(key, 1);
    }

    /**
     * Fast-path insert: follows at most {@link #MAX_PROBES} buckets and refuses to fill the table past
     * half, so chains stay short and the slow path grows the table in time.
     */
    @Uninterruptible(reason = "Operates only on native counter tables.")
    private static boolean addBounded(Pointer table, long key) {
        int mask = (int) table.readLong(MASK_OFFSET, TABLE_LOCATION);
        int index = mix(key) & mask;
        for (int probe = 0; probe < MAX_PROBES; probe++) {
            int bucket = HEADER_BYTES + index * BUCKET_BYTES;
            long existing = table.readLong(bucket + BUCKET_KEY, TABLE_LOCATION);
            if (existing == key) {
                table.writeLong(bucket + BUCKET_COUNT, table.readLong(bucket + BUCKET_COUNT, TABLE_LOCATION) + 1, TABLE_LOCATION);
                return true;
            }
            if (existing == 0) {
                long size = table.readLong(SIZE_OFFSET, TABLE_LOCATION);
                if (size * 2 >= mask + 1L) {
                    return false;
                }
                table.writeLong(bucket + BUCKET_COUNT, 1, TABLE_LOCATION);
                table.writeLong(bucket + BUCKET_KEY, key, TABLE_LOCATION);
                table.writeLong(SIZE_OFFSET, size + 1, TABLE_LOCATION);
                return true;
            }
            index = (index + 1) & mask;
        }
        return false;
    }

    /** Slow-path insert: probes the whole table; fails only when every bucket is taken. */
    @Uninterruptible(reason = "Operates only on native counter tables.")
    private static boolean addUnbounded(Pointer table, long key, long count) {
        int mask = (int) table.readLong(MASK_OFFSET, TABLE_LOCATION);
        int index = mix(key) & mask;
        for (int probe = 0; probe <= mask; probe++) {
            int bucket = HEADER_BYTES + index * BUCKET_BYTES;
            long existing = table.readLong(bucket + BUCKET_KEY, TABLE_LOCATION);
            if (existing == key) {
                table.writeLong(bucket + BUCKET_COUNT, table.readLong(bucket + BUCKET_COUNT, TABLE_LOCATION) + count, TABLE_LOCATION);
                return true;
            }
            if (existing == 0) {
                table.writeLong(bucket + BUCKET_COUNT, count, TABLE_LOCATION);
                table.writeLong(bucket + BUCKET_KEY, key, TABLE_LOCATION);
                table.writeLong(SIZE_OFFSET, table.readLong(SIZE_OFFSET, TABLE_LOCATION) + 1, TABLE_LOCATION);
                return true;
            }
            index = (index + 1) & mask;
        }
        return false;
    }

    /** A zeroed table with {@code buckets} buckets and its mask set; null on allocation failure. */
    @Uninterruptible(reason = "Operates only on native counter tables.")
    private static Pointer allocateTable(int buckets) {
        Pointer table = UntrackedNullableNativeMemory.calloc(Word.unsigned(HEADER_BYTES).add(Word.unsigned(buckets).multiply(BUCKET_BYTES)));
        if (table.isNonNull()) {
            table.writeLong(MASK_OFFSET, buckets - 1L, TABLE_LOCATION);
        }
        return table;
    }

    /**
     * Doubles the thread's table when it is at least half full and below {@link #MAX_BUCKETS}. Runs
     * only in the uninterruptible slow path, so a safepoint snapshot sees either the old table or the
     * complete new one. Returns the table to use (the old one when nothing was done).
     */
    @Uninterruptible(reason = "Swaps the thread's table; must not be interrupted by a snapshot.")
    private static Pointer growIfHalfFull(Pointer table) {
        long buckets = table.readLong(MASK_OFFSET, TABLE_LOCATION) + 1;
        if (table.readLong(SIZE_OFFSET, TABLE_LOCATION) * 2 < buckets || buckets >= MAX_BUCKETS) {
            return table;
        }
        Pointer grown = rehash(table, (int) (buckets * 2));
        if (grown.isNull()) {
            return table;
        }
        TABLE.set(grown);
        UntrackedNullableNativeMemory.free(table);
        return grown;
    }

    /** Copies a table into a fresh one with {@code buckets} buckets; null on allocation failure. */
    @Uninterruptible(reason = "Operates only on native counter tables.")
    private static Pointer rehash(Pointer source, int buckets) {
        Pointer target = allocateTable(buckets);
        if (target.isNull()) {
            return target;
        }
        target.writeLong(TOTAL_OFFSET, source.readLong(TOTAL_OFFSET, TABLE_LOCATION), TABLE_LOCATION);
        target.writeLong(DROPPED_OFFSET, source.readLong(DROPPED_OFFSET, TABLE_LOCATION), TABLE_LOCATION);
        int sourceMask = (int) source.readLong(MASK_OFFSET, TABLE_LOCATION);
        for (int index = 0; index <= sourceMask; index++) {
            int bucket = HEADER_BYTES + index * BUCKET_BYTES;
            long key = source.readLong(bucket + BUCKET_KEY, TABLE_LOCATION);
            if (key != 0) {
                addUnbounded(target, key, source.readLong(bucket + BUCKET_COUNT, TABLE_LOCATION));
            }
        }
        return target;
    }

    @Uninterruptible(reason = "Operates only on native counter tables.")
    private static void clearTable(Pointer table) {
        int mask = (int) table.readLong(MASK_OFFSET, TABLE_LOCATION);
        for (int index = 0; index <= mask; index++) {
            int bucket = HEADER_BYTES + index * BUCKET_BYTES;
            table.writeLong(bucket + BUCKET_KEY, 0, TABLE_LOCATION);
            table.writeLong(bucket + BUCKET_COUNT, 0, TABLE_LOCATION);
        }
        table.writeLong(TOTAL_OFFSET, 0, TABLE_LOCATION);
        table.writeLong(DROPPED_OFFSET, 0, TABLE_LOCATION);
        table.writeLong(SIZE_OFFSET, 0, TABLE_LOCATION);
    }

    @Uninterruptible(reason = "Operates only on native counter tables.")
    private static int mix(long key) {
        long value = key;
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdL;
        value ^= value >>> 33;
        return (int) value;
    }

    public record TableTestResult(int occupied, long capacity, long privateCount, long snapshotPrivateCount, boolean routedThroughRuntime, int longChains, boolean slowPathPreserved,
                    boolean snapshotCleared, boolean clearReused) {
    }

    /**
     * Native-test seam: fills a real private table well past its initial size through the production
     * paths, then drives spill, live snapshot, clear, and reuse.
     */
    public static TableTestResult exerciseTableForTesting() {
        final int keys = 3 * INITIAL_BUCKETS;
        Pointer table = allocateTable(INITIAL_BUCKETS);
        if (table.isNull()) {
            throw new OutOfMemoryError("Could not allocate call-count table test memory");
        }
        Pointer previous = TABLE.get();
        TABLE.set(table);
        try {
            int testBase = 900_000;
            boolean routedThroughRuntime = isEnabled();
            for (int counterIndex = 0; counterIndex < keys; counterIndex++) {
                if (routedThroughRuntime) {
                    incrementRuntime(testBase + counterIndex);
                } else {
                    incrementSlowPath(testBase + counterIndex + 1L);
                }
            }
            if (routedThroughRuntime) {
                incrementRuntime(testBase); // an existing key after growth
            } else {
                incrementSlowPath(testBase + 1L);
            }
            table = TABLE.get(); // growth replaced the table
            long capacity = table.readLong(MASK_OFFSET) + 1;
            int occupied = 0;
            long privateCount = 0;
            for (int index = 0; index < capacity; index++) {
                int bucket = HEADER_BYTES + index * BUCKET_BYTES;
                if (table.readLong(bucket + BUCKET_KEY) != 0) {
                    occupied++;
                    privateCount += table.readLong(bucket + BUCKET_COUNT);
                }
            }
            /* Growth keeps chains short: only a few keys need more than MAX_PROBES probes and take the slow path. */
            int longChains = 0;
            for (int counterIndex = 0; counterIndex < keys; counterIndex++) {
                if (!addBounded(table, testBase + counterIndex + 1L)) {
                    longChains++;
                }
            }
            privateCount += keys - longChains; // the chain check incremented every short-chain key once more
            int overflowCounter = testBase + keys;
            Pointer stolen = TABLE.get();
            TABLE.set(Word.nullPointer()); // a thread without a table spills to the shared table
            if (routedThroughRuntime) {
                incrementRuntime(overflowCounter);
            } else {
                incrementSlowPath(overflowCounter + 1L);
            }
            TABLE.set(stolen);
            takeSnapshot(); // Production VM operation merges and clears the live private table.
            boolean slowPathPreserved = snapshot.getOrDefault(overflowCounter, 0L) == 1;
            long snapshotPrivateCount = 0;
            for (int counterIndex = testBase; counterIndex < testBase + keys; counterIndex++) {
                snapshotPrivateCount += snapshot.getOrDefault(counterIndex, 0L);
            }
            /*
             * In an instrumented image the test thread's own instrumented code keeps recording into
             * this table, so only the test's keys can be required to be gone or to have exact counts.
             */
            table = TABLE.get();
            capacity = table.readLong(MASK_OFFSET) + 1;
            boolean snapshotCleared = true;
            for (int index = 0; snapshotCleared && index < capacity; index++) {
                int bucket = HEADER_BYTES + index * BUCKET_BYTES;
                long key = table.readLong(bucket + BUCKET_KEY);
                long counterIndex = key - 1;
                snapshotCleared = key == 0 || counterIndex < testBase || counterIndex >= testBase + keys;
            }
            boolean clearReused = false;
            if (addBounded(table, testBase + 1L)) {
                for (int index = 0; !clearReused && index < capacity; index++) {
                    int bucket = HEADER_BYTES + index * BUCKET_BYTES;
                    clearReused = table.readLong(bucket + BUCKET_KEY) == testBase + 1L && table.readLong(bucket + BUCKET_COUNT) == 1;
                }
            }
            return new TableTestResult(occupied, capacity, privateCount, snapshotPrivateCount, routedThroughRuntime, longChains, slowPathPreserved, snapshotCleared, clearReused);
        } finally {
            Pointer current = TABLE.get();
            TABLE.set(previous);
            if (current.isNonNull()) {
                UntrackedNullableNativeMemory.free(current);
            }
        }
    }

    /** Spill for a thread whose private table is missing or at its cap; grows the shared table as needed. */
    @Uninterruptible(reason = "Shared fallback must not be stopped while holding the native merge lock.")
    private static void addDirectlyToShared(long key, long count) {
        lockShared();
        Pointer shared = sharedTableForInsert();
        if (shared.isNonNull() && addUnbounded(shared, key, count)) {
            shared.writeLong(TOTAL_OFFSET, shared.readLong(TOTAL_OFFSET, TABLE_LOCATION) + count, TABLE_LOCATION);
        } else {
            addSharedDropped(count);
        }
        unlockShared();
    }

    /** Folds a private table into the shared table and optionally clears it. Takes the shared lock. */
    @Uninterruptible(reason = "Called after Thread.exit or at a safepoint.")
    private static void mergeIntoShared(Pointer source, boolean clear) {
        lockShared();
        Pointer shared = sharedTableForInsert();
        if (shared.isNonNull()) {
            shared.writeLong(TOTAL_OFFSET, shared.readLong(TOTAL_OFFSET, TABLE_LOCATION) + source.readLong(TOTAL_OFFSET, TABLE_LOCATION), TABLE_LOCATION);
            shared.writeLong(DROPPED_OFFSET, shared.readLong(DROPPED_OFFSET, TABLE_LOCATION) + source.readLong(DROPPED_OFFSET, TABLE_LOCATION), TABLE_LOCATION);
            int sourceMask = (int) source.readLong(MASK_OFFSET, TABLE_LOCATION);
            for (int index = 0; index <= sourceMask; index++) {
                int bucket = HEADER_BYTES + index * BUCKET_BYTES;
                long key = source.readLong(bucket + BUCKET_KEY, TABLE_LOCATION);
                if (key != 0) {
                    long count = source.readLong(bucket + BUCKET_COUNT, TABLE_LOCATION);
                    shared = sharedTableForInsert();
                    if (shared.isNull() || !addUnbounded(shared, key, count)) {
                        addSharedDropped(count);
                    }
                }
            }
        } else {
            addSharedDropped(source.readLong(TOTAL_OFFSET, TABLE_LOCATION));
        }
        if (clear) {
            clearTable(source);
        }
        unlockShared();
    }

    /**
     * The shared table, allocated on first use and doubled when half full, so merges never run out of
     * space. Caller holds {@link #SHARED_LOCK}. Null only on allocation failure.
     */
    @Uninterruptible(reason = "Operates only on native counter tables under the shared lock.")
    private static Pointer sharedTableForInsert() {
        Pointer shared = SHARED.get().readWord(0);
        if (shared.isNull()) {
            shared = allocateTable(INITIAL_SHARED_BUCKETS);
            SHARED.get().writeWord(0, shared);
            return shared;
        }
        long buckets = shared.readLong(MASK_OFFSET, TABLE_LOCATION) + 1;
        if (shared.readLong(SIZE_OFFSET, TABLE_LOCATION) * 2 >= buckets) {
            Pointer grown = rehash(shared, (int) (buckets * 2));
            if (grown.isNonNull()) {
                SHARED.get().writeWord(0, grown);
                UntrackedNullableNativeMemory.free(shared);
                return grown;
            }
        }
        return shared;
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
    private static void addSharedDropped(long count) {
        if (count != 0) {
            UNSAFE.getAndAddLong(null, SHARED_DROPPED.get().rawValue(), count);
        }
    }

    private static void takeSnapshot() {
        if (SubstrateUtil.HOSTED) {
            snapshot = new HashMap<>(HOSTED_COUNTS);
            snapshotTotal = HOSTED_COUNTS.values().stream().mapToLong(Long::longValue).sum();
            snapshotDropped = 0;
            return;
        }
        SnapshotOperation operation = new SnapshotOperation();
        operation.enqueue();
        Map<Integer, Long> copied = new HashMap<>();
        for (int index = 0; index < operation.keys.length; index++) {
            if (operation.keys[index] != 0) {
                copied.put((int) operation.keys[index] - 1, operation.counts[index]);
            }
        }
        snapshotTotal = operation.total;
        snapshotDropped = operation.dropped;
        snapshot = copied;
    }

    private static final class SnapshotOperation extends JavaVMOperation {
        private long[] keys = new long[0];
        private long[] counts = new long[0];
        private long total;
        private long dropped;

        SnapshotOperation() {
            super(VMOperationInfos.get(SnapshotOperation.class, "Collect PGO call counts", SystemEffect.SAFEPOINT));
        }

        @Override
        protected void operate() {
            for (IsolateThread thread = VMThreads.firstThread(); thread.isNonNull(); thread = VMThreads.nextThread(thread)) {
                Pointer table = TABLE.get(thread);
                if (table.isNonNull()) {
                    mergeIntoShared(table, true);
                }
            }
            lockShared();
            Pointer shared = SHARED.get().readWord(0);
            dropped = SHARED_DROPPED.get().readLong(0);
            if (shared.isNonNull()) {
                total = shared.readLong(TOTAL_OFFSET);
                dropped += shared.readLong(DROPPED_OFFSET);
                int buckets = (int) shared.readLong(MASK_OFFSET) + 1;
                keys = new long[buckets];
                counts = new long[buckets];
                for (int index = 0; index < buckets; index++) {
                    int bucket = HEADER_BYTES + index * BUCKET_BYTES;
                    keys[index] = shared.readLong(bucket + BUCKET_KEY);
                    counts[index] = shared.readLong(bucket + BUCKET_COUNT);
                }
            }
            unlockShared();
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
