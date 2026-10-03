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
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.graalvm.nativeimage.IsolateThread;
import org.graalvm.word.Pointer;
import org.graalvm.word.impl.Word;

import com.oracle.svm.core.heap.VMOperationInfos;
import com.oracle.svm.core.hub.DynamicHubIntrinsics;
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

/** Allocation-free receiver-frequency recorder used only by PGO instrumentation images. */
public final class ReceiverProfileRecorder implements ThreadListener {
    private static final int MAX_SITES = 1_000_000;
    private static final int THREAD_BUCKETS = 16_384;
    private static final int SHARED_BUCKETS = 131_072;
    private static final int HEADER_BYTES = 16;
    private static final int TOTAL_OFFSET = 0;
    private static final int DROPPED_OFFSET = 8;
    private static final int BUCKET_BYTES = 16;
    private static final int BUCKET_KEY = 0;
    private static final int BUCKET_COUNT = 8;
    private static final int THREAD_TABLE_BYTES = HEADER_BYTES + THREAD_BUCKETS * BUCKET_BYTES;
    private static final int SHARED_TABLE_BYTES = HEADER_BYTES + SHARED_BUCKETS * BUCKET_BYTES;

    private static final Unsafe UNSAFE = Unsafe.getUnsafe();
    private static final FastThreadLocalWord<Pointer> TABLE = FastThreadLocalFactory.createWord("ReceiverProfileRecorder.table");
    private static final CGlobalData<Pointer> SHARED = CGlobalDataFactory.createBytes(() -> SHARED_TABLE_BYTES, "__svm_pgo_receiver_profiles");
    private static final CGlobalData<Pointer> SHARED_LOCK = CGlobalDataFactory.createWord();
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
    private static long snapshotUnknown;
    /** Build-time constant captured in the instrumentation image. */
    private static boolean enabled;

    private ReceiverProfileRecorder() {
    }

    public static ReceiverProfileRecorder create() {
        enabled = true;
        return new ReceiverProfileRecorder();
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static ReceiverProfileSite createSite(String[] methodDescriptors, int[] contextBcis, int[] receiverTypeIds, String[] receiverTypeDescriptors) {
        if (methodDescriptors.length == 0 || methodDescriptors.length != contextBcis.length || receiverTypeIds.length != receiverTypeDescriptors.length) {
            throw new IllegalArgumentException("Receiver profile site metadata must contain matching arrays and a non-empty context");
        }
        int index = NEXT_SITE.getAndIncrement();
        if (index >= MAX_SITES) {
            throw new IllegalStateException("Receiver instrumentation exceeds the " + MAX_SITES + "-site capacity");
        }
        ReceiverProfileSite site = new ReceiverProfileSite(index, methodDescriptors, contextBcis, receiverTypeIds, receiverTypeDescriptors);
        SITES.put(index, site);
        return site;
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
            mergeIntoShared(table, false);
            TABLE.set(isolateThread, Word.nullPointer());
            UntrackedNullableNativeMemory.free(table);
        }
    }

    /** Registers the descriptor of an image type so that receivers unknown to a site's static profile can be named. */
    public static void registerTypeDescriptor(int typeId, String descriptor) {
        TYPE_DESCRIPTORS_BY_ID.putIfAbsent(typeId, descriptor);
    }

    public static void record(int siteIndex, Object receiver) {
        if (receiver != null) {
            recordType(siteIndex, DynamicHubIntrinsics.readHub(receiver).getTypeID());
        }
    }

    static void recordType(int siteIndex, int typeId) {
        long key = ((long) (siteIndex + 1) << 32) | Integer.toUnsignedLong(typeId);
        if (SubstrateUtil.HOSTED) {
            HOSTED_COUNTS.merge(key, 1L, Long::sum);
            return;
        }
        Pointer table = TABLE.get();
        if (table.isNull()) {
            addSharedHeader(DROPPED_OFFSET, 1);
            return;
        }
        table.writeLong(TOTAL_OFFSET, table.readLong(TOTAL_OFFSET) + 1);
        if (!addToTable(table, THREAD_BUCKETS, key, 1)) {
            table.writeLong(DROPPED_OFFSET, table.readLong(DROPPED_OFFSET) + 1);
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

    @Uninterruptible(reason = "Called after Thread.exit or at a safepoint.")
    private static void mergeIntoShared(Pointer source, boolean clear) {
        lockShared();
        Pointer shared = SHARED.get();
        addSharedHeader(TOTAL_OFFSET, source.readLong(TOTAL_OFFSET));
        addSharedHeader(DROPPED_OFFSET, source.readLong(DROPPED_OFFSET));
        for (int index = 0; index < THREAD_BUCKETS; index++) {
            int bucket = HEADER_BYTES + index * BUCKET_BYTES;
            long key = source.readLong(bucket + BUCKET_KEY);
            if (key != 0) {
                long count = source.readLong(bucket + BUCKET_COUNT);
                if (!addToTable(shared, SHARED_BUCKETS, key, count)) {
                    addSharedHeader(DROPPED_OFFSET, count);
                }
                if (clear) {
                    source.writeLong(bucket + BUCKET_KEY, 0);
                    source.writeLong(bucket + BUCKET_COUNT, 0);
                }
            }
        }
        if (clear) {
            source.writeLong(TOTAL_OFFSET, 0);
            source.writeLong(DROPPED_OFFSET, 0);
        }
        unlockShared();
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
        new MergeLiveOperation().enqueue();
        Map<Long, Long> copied = new HashMap<>();
        lockShared();
        Pointer shared = SHARED.get();
        snapshotTotal = shared.readLong(TOTAL_OFFSET);
        snapshotDropped = shared.readLong(DROPPED_OFFSET);
        for (int index = 0; index < SHARED_BUCKETS; index++) {
            int bucket = HEADER_BYTES + index * BUCKET_BYTES;
            long key = shared.readLong(bucket + BUCKET_KEY);
            if (key != 0) {
                copied.put(key, shared.readLong(bucket + BUCKET_COUNT));
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

    public static List<DecodedReceiverProfile> decodeProfiles() {
        takeSnapshot();
        Map<ContextKey, Map<String, Long>> aggregate = new HashMap<>();
        long unknown = 0;
        for (Map.Entry<Long, Long> entry : snapshot.entrySet()) {
            long key = entry.getKey();
            int siteIndex = (int) (key >>> 32) - 1;
            int typeId = (int) key;
            ReceiverProfileSite site = SITES.get(siteIndex);
            String descriptor = site == null ? null : site.receiverTypeDescriptor(typeId);
            if (descriptor == null && site != null) {
                descriptor = TYPE_DESCRIPTORS_BY_ID.get(typeId);
            }
            if (descriptor == null) {
                unknown += entry.getValue();
                continue;
            }
            ContextKey context = new ContextKey(Arrays.asList(site.methodDescriptors()), Arrays.stream(site.contextBcis()).boxed().toList());
            aggregate.computeIfAbsent(context, _ -> new HashMap<>()).merge(descriptor, entry.getValue(), Long::sum);
        }
        snapshotUnknown = unknown;
        List<DecodedReceiverProfile> result = new ArrayList<>();
        aggregate.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> result.add(
                        new DecodedReceiverProfile(entry.getKey().methodDescriptors.toArray(String[]::new), entry.getKey().bcis.stream().mapToInt(Integer::intValue).toArray(),
                                        new TreeMap<>(entry.getValue()))));
        return result;
    }

    public static Statistics statistics() {
        if (snapshot == null) {
            takeSnapshot();
        }
        return new Statistics(SITES.size() - 1, snapshotTotal, snapshotDropped, snapshotUnknown);
    }

    public record DecodedReceiverProfile(String[] methodDescriptors, int[] bcis, Map<String, Long> countsByTypeDescriptor) {
        public DecodedReceiverProfile {
            methodDescriptors = methodDescriptors.clone();
            bcis = bcis.clone();
            countsByTypeDescriptor = Map.copyOf(countsByTypeDescriptor);
        }
    }

    public record Statistics(int physicalSites, long events, long dropped, long unknown) {
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
