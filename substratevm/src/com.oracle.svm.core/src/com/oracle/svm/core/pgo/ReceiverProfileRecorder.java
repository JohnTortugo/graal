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
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.graalvm.nativeimage.IsolateThread;
import org.graalvm.word.LocationIdentity;
import org.graalvm.word.Pointer;
import org.graalvm.word.impl.Word;

import com.oracle.svm.core.heap.VMOperationInfos;
import com.oracle.svm.core.hub.DynamicHubIntrinsics;
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
 * Allocation-free receiver-frequency recorder used only by PGO instrumentation images.
 *
 * Same storage shape as {@link CallCountProfileRecorder}: a per-thread open-addressing table keyed
 * by (site, type id) that starts at {@link #INITIAL_BUCKETS} and doubles whenever it is half full,
 * up to {@link #MAX_BUCKETS}; a fast path bounded to {@link #MAX_PROBES} probes; one foreign call for
 * growth, long chains, a table at its cap, or a missing table; and a shared table of the same layout
 * that grows under a lock when private tables are merged into it.
 */
public final class ReceiverProfileRecorder implements ThreadListener {
    private static final int MAX_SITES = 1_000_000;
    /** Buckets of a fresh per-thread table (256 KB). */
    private static final int INITIAL_BUCKETS = 16_384;
    /** Largest per-thread table (4 MB); keys that do not fit beyond this go to the shared table. */
    private static final int MAX_BUCKETS = 262_144;
    /** Buckets of a fresh shared table. */
    private static final int INITIAL_SHARED_BUCKETS = 131_072;
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
    private static final FastThreadLocalWord<Pointer> TABLE = FastThreadLocalFactory.createWord("ReceiverProfileRecorder.table");

    /** Location of the per-thread table pointer; a snippet that reads it must declare it private. */
    public static LocationIdentity tablePointerLocation() {
        return TABLE.getLocationIdentity();
    }

    /**
     * Location of the table contents. The tables are native memory that is never aliased with any
     * Java-visible memory, so the fast path accesses them under this name and a snippet that contains
     * the fast path declares it private. The slow path runs as {@link #RECORD_SLOW_PATH}, which kills
     * only this location and {@link #tablePointerLocation()} (it may replace the table).
     */
    public static final LocationIdentity TABLE_LOCATION = NamedLocationIdentity.mutable("PGOReceiverTables");

    public static final SubstrateForeignCallDescriptor RECORD_SLOW_PATH = findForeignCall(ReceiverProfileRecorder.class, "recordSlowPath", NO_SIDE_EFFECT, TABLE_LOCATION,
                    TABLE.getLocationIdentity());
    /** Pointer to the shared table; allocated lazily and replaced on growth, always under {@link #SHARED_LOCK}. */
    private static final CGlobalData<Pointer> SHARED = CGlobalDataFactory.createWord();
    private static final CGlobalData<Pointer> SHARED_LOCK = CGlobalDataFactory.createWord();
    /** Events that could not be placed anywhere (table allocation failed). */
    private static final CGlobalData<Pointer> SHARED_DROPPED = CGlobalDataFactory.createWord();
    private static final ConcurrentMap<Integer, ReceiverProfileSite> SITES = new ConcurrentHashMap<>();
    private static final AtomicInteger NEXT_SITE = new AtomicInteger();
    private static final ConcurrentMap<Long, Long> HOSTED_COUNTS = SubstrateUtil.HOSTED ? new ConcurrentHashMap<>() : null;

    /**
     * Descriptor of every image type by type id, registered after compilation. Lets sites whose
     * receiver type state was unknown to the static analysis (saturated or absent static type
     * profile) still be decoded: the recorder keys by the dynamic hub id, this table names it.
     */
    private static final ConcurrentMap<Integer, String> TYPE_DESCRIPTORS_BY_ID = new ConcurrentHashMap<>();

    /** Makes metadata allocated after analysis visible to the image-heap scanner. */
    private static final ReceiverProfileSite UNUSED_SITE = createSite(new String[]{"Lcom/oracle/svm/core/pgo/ReceiverProfileRecorder;.__unused__()V"}, new int[]{-1}, new int[]{-1},
                    new String[]{"Ljava/lang/Object;"});

    private static Map<Long, Long> snapshot;
    private static long snapshotTotal;
    private static long snapshotDropped;
    /** Build-time constant captured in the instrumentation image. */
    private static boolean enabled;
    private static boolean monitorProfilesEnabled;

    private ReceiverProfileRecorder() {
    }

    public static ReceiverProfileRecorder create() {
        enabled = true;
        return new ReceiverProfileRecorder();
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void enableMonitorProfiles() {
        monitorProfilesEnabled = true;
    }

    public static boolean monitorProfilesEnabled() {
        return monitorProfilesEnabled;
    }

    public static ReceiverProfileSite createSite(String[] methodDescriptors, int[] contextBcis, int[] receiverTypeIds, String[] receiverTypeDescriptors) {
        if (methodDescriptors.length == 0 || methodDescriptors.length != contextBcis.length || receiverTypeIds.length != receiverTypeDescriptors.length) {
            throw new IllegalArgumentException("Receiver profile site metadata must contain matching arrays and a non-empty context");
        }
        int index = NEXT_SITE.getAndIncrement();
        if (index >= MAX_SITES) {
            throw new IllegalStateException("Receiver instrumentation exceeds the " + MAX_SITES + "-site capacity");
        }
        ReceiverProfileSite site = new ReceiverProfileSite(index, methodDescriptors, contextBcis, receiverTypeIds, receiverTypeDescriptors, false);
        SITES.put(index, site);
        return site;
    }

    /**
     * Returns the single aggregation site shared by every monitor probe. Monitor profiles are
     * global by dynamic type, so retaining the physical site in the runtime key only consumes table
     * capacity and can displace receiver-profile keys without adding information to the output.
     */
    public static ReceiverProfileSite createMonitorSite() {
        return MonitorSiteHolder.SITE;
    }

    private static ReceiverProfileSite createMonitorSiteInternal() {
        int index = NEXT_SITE.getAndIncrement();
        if (index >= MAX_SITES) {
            throw new IllegalStateException("Monitor instrumentation exceeds the " + MAX_SITES + "-site capacity");
        }
        ReceiverProfileSite site = new ReceiverProfileSite(index, new String[0], new int[0], new int[0], new String[0], true);
        SITES.put(index, site);
        return site;
    }

    /** Delays allocation so images without monitor instrumentation contain no monitor site. */
    private static final class MonitorSiteHolder {
        private static final ReceiverProfileSite SITE = createMonitorSiteInternal();
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

    /** Registers the descriptor of an image type so that receivers unknown to a site's static profile can be named. */
    public static void registerTypeDescriptor(int typeId, String descriptor) {
        TYPE_DESCRIPTORS_BY_ID.putIfAbsent(typeId, descriptor);
    }

    /**
     * Snippet body: no hosted branch, nothing that can throw, and memory accesses only to
     * {@link #tablePointerLocation()} and {@link #TABLE_LOCATION}, so that the node lowering to it
     * need not be a memory kill.
     */
    public static void record(int siteIndex, Object receiver) {
        if (receiver != null) {
            recordTypeRuntime(siteIndex, DynamicHubIntrinsics.readHub(receiver).getTypeID());
        }
    }

    static void recordType(int siteIndex, int typeId) {
        if (SubstrateUtil.HOSTED) {
            HOSTED_COUNTS.merge(((long) (siteIndex + 1) << 32) | Integer.toUnsignedLong(typeId), 1L, Long::sum);
            return;
        }
        recordTypeRuntime(siteIndex, typeId);
    }

    private static void recordTypeRuntime(int siteIndex, int typeId) {
        long key = ((long) (siteIndex + 1) << 32) | (typeId & 0xffffffffL);
        Pointer table = TABLE.get();
        if (table.isNonNull() && addBounded(table, key)) {
            table.writeLong(TOTAL_OFFSET, table.readLong(TOTAL_OFFSET, TABLE_LOCATION) + 1, TABLE_LOCATION);
        } else {
            callSlowPath(RECORD_SLOW_PATH, key);
        }
    }

    @NodeIntrinsic(value = ForeignCallNode.class)
    private static native void callSlowPath(@ConstantNodeParameter ForeignCallDescriptor descriptor, long key);

    /**
     * Rare path, compiled separately so that the snippet contains no allocation or atomics: grows the
     * private table when it is half full, inserts with an unbounded probe, and spills to the shared
     * table only when the private table is at its cap or missing.
     */
    @SubstrateForeignCallTarget(stubCallingConvention = false, fullyUninterruptible = true)
    @Uninterruptible(reason = "May replace the thread's table and writes native counter memory; needs no deoptimization state.")
    private static void recordSlowPath(long key) {
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
     * only in the uninterruptible slow path, so a safepoint merge sees either the old table or the
     * complete new one. Returns the table to use (the old one when nothing was done).
     */
    @Uninterruptible(reason = "Swaps the thread's table; must not be interrupted by a merge.")
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

    public record TableTestResult(int occupied, long capacity, long privateCount, long snapshotPrivateCount, boolean routedThroughRuntime, int longChains, boolean spillPreserved,
                    boolean mergeCleared, boolean clearReused) {
    }

    /**
     * Native-test seam: fills a real private table well past its initial size through the production
     * paths, then drives spill, shared merge, clear, and reuse.
     */
    public static TableTestResult exerciseTableForTesting() {
        final int keys = 3 * INITIAL_BUCKETS;
        Pointer table = allocateTable(INITIAL_BUCKETS);
        if (table.isNull()) {
            throw new OutOfMemoryError("Could not allocate receiver table test memory");
        }
        Pointer previous = TABLE.get();
        TABLE.set(table);
        try {
            int testBase = 900_000;
            boolean routedThroughRuntime = isEnabled();
            for (int siteIndex = 0; siteIndex < keys; siteIndex++) {
                if (routedThroughRuntime) {
                    recordTypeRuntime(testBase + siteIndex, 1);
                } else {
                    recordSlowPath(((long) (testBase + siteIndex + 1) << 32) | 1);
                }
            }
            long firstKey = ((long) (testBase + 1) << 32) | 1;
            if (routedThroughRuntime) {
                recordTypeRuntime(testBase, 1); // an existing key after growth
            } else {
                recordSlowPath(firstKey);
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
            for (int siteIndex = 0; siteIndex < keys; siteIndex++) {
                if (!addBounded(table, ((long) (testBase + siteIndex + 1) << 32) | 1)) {
                    longChains++;
                }
            }
            privateCount += keys - longChains; // the chain check incremented every short-chain key once more
            int overflowSite = testBase + keys;
            Pointer stolen = TABLE.get();
            TABLE.set(Word.nullPointer()); // a thread without a table spills to the shared table
            if (routedThroughRuntime) {
                recordTypeRuntime(overflowSite, 1);
            } else {
                recordSlowPath(((long) (overflowSite + 1) << 32) | 1);
            }
            TABLE.set(stolen);
            mergeIntoShared(stolen, true);
            /*
             * In an instrumented image the test thread's own instrumented code keeps recording into
             * this table, so only the test's keys can be required to be gone or to have exact counts.
             */
            boolean mergeCleared = true;
            for (int index = 0; mergeCleared && index < capacity; index++) {
                int bucket = HEADER_BYTES + index * BUCKET_BYTES;
                long key = stolen.readLong(bucket + BUCKET_KEY);
                long siteIndex = (key >>> 32) - 1;
                mergeCleared = key == 0 || siteIndex < testBase || siteIndex >= testBase + keys;
            }
            takeSnapshot();
            boolean spillPreserved = snapshot.getOrDefault(((long) (overflowSite + 1) << 32) | 1, 0L) == 1;
            long snapshotPrivateCount = 0;
            for (Map.Entry<Long, Long> entry : snapshot.entrySet()) {
                int siteIndex = (int) (entry.getKey() >>> 32) - 1;
                if (siteIndex >= testBase && siteIndex < testBase + keys) {
                    snapshotPrivateCount += entry.getValue();
                }
            }
            boolean clearReused = false;
            if (addBounded(stolen, firstKey)) {
                for (int index = 0; !clearReused && index < capacity; index++) {
                    int bucket = HEADER_BYTES + index * BUCKET_BYTES;
                    clearReused = stolen.readLong(bucket + BUCKET_KEY) == firstKey && stolen.readLong(bucket + BUCKET_COUNT) == 1;
                }
            }
            mergeIntoShared(stolen, true);
            return new TableTestResult(occupied, capacity, privateCount, snapshotPrivateCount, routedThroughRuntime, longChains, spillPreserved, mergeCleared, clearReused);
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

    @Uninterruptible(reason = "Native spin lock for rare exit and snapshot merges.")
    private static void lockShared() {
        Pointer lock = SHARED_LOCK.get();
        while (!UNSAFE.compareAndSetInt(null, lock.rawValue(), 0, 1)) {
            // Receiver recording does not acquire this lock.
        }
    }

    @Uninterruptible(reason = "Native spin lock for rare exit and snapshot merges.")
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
        new MergeLiveOperation().enqueue();
        Map<Long, Long> copied = new HashMap<>();
        lockShared();
        Pointer shared = SHARED.get().readWord(0);
        snapshotTotal = 0;
        snapshotDropped = SHARED_DROPPED.get().readLong(0);
        if (shared.isNonNull()) {
            snapshotTotal = shared.readLong(TOTAL_OFFSET);
            snapshotDropped += shared.readLong(DROPPED_OFFSET);
            int mask = (int) shared.readLong(MASK_OFFSET);
            for (int index = 0; index <= mask; index++) {
                int bucket = HEADER_BYTES + index * BUCKET_BYTES;
                long key = shared.readLong(bucket + BUCKET_KEY);
                if (key != 0) {
                    copied.put(key, shared.readLong(bucket + BUCKET_COUNT));
                }
            }
        }
        unlockShared();
        snapshot = copied;
    }

    private static final class MergeLiveOperation extends JavaVMOperation {
        MergeLiveOperation() {
            super(VMOperationInfos.get(MergeLiveOperation.class, "Collect PGO receiver profiles", SystemEffect.SAFEPOINT));
        }

        @Override
        protected void operate() {
            for (IsolateThread thread = VMThreads.firstThread(); thread.isNonNull(); thread = VMThreads.nextThread(thread)) {
                Pointer table = TABLE.get(thread);
                if (table.isNonNull()) {
                    mergeIntoShared(table, true);
                }
            }
        }
    }

    /** Takes one consistent snapshot and decodes both profile categories from it. */
    public static DecodedTypeProfiles decodeAllProfiles() {
        takeSnapshot();
        return new DecodedTypeProfiles(decodeProfilesFromSnapshot(), decodeMonitorProfilesFromSnapshot());
    }

    public static List<DecodedReceiverProfile> decodeProfiles() {
        takeSnapshot();
        return decodeProfilesFromSnapshot();
    }

    private static List<DecodedReceiverProfile> decodeProfilesFromSnapshot() {
        Map<ContextKey, Map<String, Long>> aggregate = new HashMap<>();
        for (Map.Entry<Long, Long> entry : snapshot.entrySet()) {
            long key = entry.getKey();
            int siteIndex = (int) (key >>> 32) - 1;
            int typeId = (int) key;
            ReceiverProfileSite site = SITES.get(siteIndex);
            if (site == null || site.isMonitorProfile()) {
                continue;
            }
            String descriptor = site.receiverTypeDescriptor(typeId);
            if (descriptor == null && site != null) {
                descriptor = TYPE_DESCRIPTORS_BY_ID.get(typeId);
            }
            if (descriptor == null) {
                continue;
            }
            ContextKey context = new ContextKey(Arrays.asList(site.methodDescriptors()), Arrays.stream(site.contextBcis()).boxed().toList());
            aggregate.computeIfAbsent(context, _ -> new HashMap<>()).merge(descriptor, entry.getValue(), Long::sum);
        }
        List<DecodedReceiverProfile> result = new ArrayList<>();
        aggregate.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> result.add(
                        new DecodedReceiverProfile(entry.getKey().methodDescriptors.toArray(String[]::new), entry.getKey().bcis.stream().mapToInt(Integer::intValue).toArray(),
                                        new TreeMap<>(entry.getValue()))));
        return result;
    }

    /** Aggregates dynamic receiver types from all monitor-enter sites. */
    public static Map<String, Long> decodeMonitorProfiles() {
        takeSnapshot();
        return decodeMonitorProfilesFromSnapshot();
    }

    private static Map<String, Long> decodeMonitorProfilesFromSnapshot() {
        Map<String, Long> result = new TreeMap<>();
        for (Map.Entry<Long, Long> entry : snapshot.entrySet()) {
            long key = entry.getKey();
            int siteIndex = (int) (key >>> 32) - 1;
            int typeId = (int) key;
            ReceiverProfileSite site = SITES.get(siteIndex);
            if (site == null || !site.isMonitorProfile()) {
                continue;
            }
            String descriptor = TYPE_DESCRIPTORS_BY_ID.get(typeId);
            if (descriptor != null) {
                result.merge(descriptor, entry.getValue(), Long::sum);
            }
        }
        return result;
    }

    public static Statistics statistics() {
        if (snapshot == null) {
            takeSnapshot();
        }
        int receiverSites = 0;
        int monitorSites = 0;
        for (ReceiverProfileSite site : SITES.values()) {
            if (site == UNUSED_SITE) {
                continue;
            }
            if (site.isMonitorProfile()) {
                monitorSites++;
            } else {
                receiverSites++;
            }
        }
        long receiverEvents = 0;
        long monitorEvents = 0;
        long unknownReceivers = 0;
        long unknownMonitors = 0;
        for (Map.Entry<Long, Long> entry : snapshot.entrySet()) {
            long key = entry.getKey();
            int siteIndex = (int) (key >>> 32) - 1;
            int typeId = (int) key;
            ReceiverProfileSite site = SITES.get(siteIndex);
            if (site == null) {
                continue;
            }
            long count = entry.getValue();
            if (site.isMonitorProfile()) {
                monitorEvents += count;
                if (!TYPE_DESCRIPTORS_BY_ID.containsKey(typeId)) {
                    unknownMonitors += count;
                }
            } else {
                receiverEvents += count;
                if (site.receiverTypeDescriptor(typeId) == null && !TYPE_DESCRIPTORS_BY_ID.containsKey(typeId)) {
                    unknownReceivers += count;
                }
            }
        }
        return new Statistics(receiverSites, monitorSites, receiverEvents, monitorEvents, snapshotTotal, snapshotDropped, unknownReceivers, unknownMonitors);
    }

    public record DecodedTypeProfiles(List<DecodedReceiverProfile> receiverProfiles, Map<String, Long> monitorProfiles) {
        public DecodedTypeProfiles {
            receiverProfiles = List.copyOf(receiverProfiles);
            monitorProfiles = Map.copyOf(monitorProfiles);
        }
    }

    public record DecodedReceiverProfile(String[] methodDescriptors, int[] bcis, Map<String, Long> countsByTypeDescriptor) {
        public DecodedReceiverProfile {
            methodDescriptors = methodDescriptors.clone();
            bcis = bcis.clone();
            countsByTypeDescriptor = Map.copyOf(countsByTypeDescriptor);
        }
    }

    public record Statistics(int receiverSites, int monitorSites, long receiverEvents, long monitorEvents, long events, long dropped, long unknownReceivers, long unknownMonitors) {
    }

    private record ContextKey(List<String> methodDescriptors, List<Integer> bcis) implements Comparable<ContextKey> {
        private ContextKey {
            methodDescriptors = List.copyOf(methodDescriptors);
            bcis = List.copyOf(bcis);
        }

        @Override
        public int compareTo(ContextKey other) {
            int length = Math.min(methodDescriptors.size(), other.methodDescriptors.size());
            for (int i = 0; i < length; i++) {
                int comparison = methodDescriptors.get(i).compareTo(other.methodDescriptors.get(i));
                if (comparison != 0) {
                    return comparison;
                }
                comparison = Integer.compare(bcis.get(i), other.bcis.get(i));
                if (comparison != 0) {
                    return comparison;
                }
            }
            return Integer.compare(methodDescriptors.size(), other.methodDescriptors.size());
        }
    }
}
