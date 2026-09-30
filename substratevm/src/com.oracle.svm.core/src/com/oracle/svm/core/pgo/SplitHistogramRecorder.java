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
import static com.oracle.svm.shared.Uninterruptible.CALLED_FROM_UNINTERRUPTIBLE_CODE;
import static jdk.graal.compiler.core.common.spi.ForeignCallDescriptor.CallSideEffect.NO_SIDE_EFFECT;

import java.util.ArrayList;

import org.graalvm.word.LocationIdentity;

import com.oracle.svm.core.jdk.Target_java_lang_String;
import com.oracle.svm.core.snippets.SnippetRuntime.SubstrateForeignCallDescriptor;
import com.oracle.svm.core.snippets.SubstrateForeignCallTarget;
import com.oracle.svm.guest.staging.log.Log;
import com.oracle.svm.shared.Uninterruptible;
import com.oracle.svm.shared.util.SubstrateUtil;

import jdk.graal.compiler.nodes.NamedLocationIdentity;

/**
 * Experimental per-invocation distributions for a user-selected {@code (String, char)} splitting
 * method: input length, returned {@link ArrayList} size and string coder, keyed by delimiter. The
 * data is diagnostic only and is printed at teardown; it is not written to the iprof file. Results
 * that are not {@link ArrayList} instances are counted with size 0.
 */
public final class SplitHistogramRecorder {
    static final int DELIMITERS = 129; // ASCII plus one non-ASCII bucket.
    static final int LENGTH_BUCKETS = 13;
    static final int OUTPUT_BUCKETS = 20;
    static final int CODER_BUCKETS = 3; // Latin-1, UTF-16, null.
    private static final int CELLS_PER_DELIMITER = 1 + LENGTH_BUCKETS + OUTPUT_BUCKETS + CODER_BUCKETS;

    /** Counter memory is private to this recorder, so only its own location is killed. */
    private static final LocationIdentity COUNTER_LOCATION = NamedLocationIdentity.mutable("PGOSplitHistogramCounters");

    /** Declared before {@link #enable()} may run, so the descriptor is always available. */
    public static final SubstrateForeignCallDescriptor RECORD = findForeignCall(SplitHistogramRecorder.class, "recordInvocation", NO_SIDE_EFFECT, COUNTER_LOCATION);

    private static boolean enabled;
    private static int base = -1;

    private SplitHistogramRecorder() {
    }

    /** Called at build time; reserves the dense cell range inside the sparse call-count storage. */
    public static synchronized void enable() {
        if (base < 0) {
            base = CallCountProfileRecorder.allocateRawCounter();
            for (int i = 1; i < DELIMITERS * CELLS_PER_DELIMITER; i++) {
                CallCountProfileRecorder.allocateRawCounter();
            }
        }
        enabled = true;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /**
     * Foreign call target invoked immediately before each return of the selected method. Running as
     * a separately compiled method lets it dereference its arguments, which a snippet lowered after
     * frame-state assignment cannot do. It is uninterruptible so that the call site needs no
     * deoptimization state, and therefore reads fields directly instead of calling accessors.
     */
    @SubstrateForeignCallTarget(stubCallingConvention = false, fullyUninterruptible = true)
    @Uninterruptible(reason = "Writes only native counter memory; needs no deoptimization state.")
    static void recordInvocation(Object line, int delimiter, Object result) {
        int delimiterBase = base + delimiterBucket(delimiter) * CELLS_PER_DELIMITER;
        bump(delimiterBase);
        int coder = 2;
        int length = 0;
        if (line instanceof String string) {
            coder = coder(string);
            length = charCount(string, coder);
        }
        bump(delimiterBase + 1 + lengthBucket(length));
        bump(delimiterBase + 1 + LENGTH_BUCKETS + outputBucket(listSize(result)));
        bump(delimiterBase + 1 + LENGTH_BUCKETS + OUTPUT_BUCKETS + coder);
    }

    @Uninterruptible(reason = "Increments one native counter cell; must not be interrupted.")
    private static void bump(int cell) {
        if (SubstrateUtil.HOSTED) {
            CallCountProfileRecorder.increment(cell);
        } else {
            CallCountProfileRecorder.incrementRuntime(cell);
        }
    }

    @Uninterruptible(reason = CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    private static int coder(String string) {
        return SubstrateUtil.HOSTED ? 0 : SubstrateUtil.cast(string, Target_java_lang_String.class).coder();
    }

    /** Latin-1 stores one byte per character, UTF-16 two, so the coder is the shift amount. */
    @Uninterruptible(reason = CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    private static int charCount(String string, int coder) {
        if (SubstrateUtil.HOSTED) {
            return string.length();
        }
        byte[] value = SubstrateUtil.cast(string, Target_java_lang_String.class).value;
        return value == null ? 0 : value.length >> coder;
    }

    @Uninterruptible(reason = CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    private static int listSize(Object result) {
        if (!(result instanceof ArrayList<?> list)) {
            return 0;
        }
        return SubstrateUtil.HOSTED ? hostedSize(list) : SubstrateUtil.cast(list, Target_java_util_ArrayList_SplitHistogram.class).size;
    }

    @Uninterruptible(reason = "Hosted-only path, folded away in an image.", calleeMustBe = false)
    private static int hostedSize(ArrayList<?> list) {
        return list.size();
    }

    @Uninterruptible(reason = CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    static int delimiterBucket(int delimiter) {
        return delimiter >= 0 && delimiter < DELIMITERS - 1 ? delimiter : DELIMITERS - 1;
    }

    /** Buckets: 0, 1, 2..3, 4..7, 8..15, ..., 1024..2047, and 2048+. */
    @Uninterruptible(reason = CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    static int lengthBucket(int length) {
        if (length <= 1) {
            return length < 0 ? 0 : length;
        }
        int bucket = 2;
        int upper = 3;
        while (bucket < LENGTH_BUCKETS - 1 && length > upper) {
            bucket++;
            upper = (upper << 1) | 1;
        }
        return bucket;
    }

    /** Exact sizes 0..15, then ArrayList growth steps 16..22, 23..33, 34..63, and 64+. */
    @Uninterruptible(reason = CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    static int outputBucket(int size) {
        if (size <= 15) {
            return size < 0 ? 0 : size;
        } else if (size <= 22) {
            return 16;
        } else if (size <= 33) {
            return 17;
        } else if (size <= 63) {
            return 18;
        }
        return 19;
    }

    /** Requires a {@link CallCountProfileRecorder} snapshot, which is taken lazily if absent. */
    public static void dumpSummary() {
        if (!enabled) {
            return;
        }
        for (int delimiter = 0; delimiter < DELIMITERS; delimiter++) {
            int delimiterBase = base + delimiter * CELLS_PER_DELIMITER;
            long total = CallCountProfileRecorder.getRawCount(delimiterBase);
            if (total == 0) {
                continue;
            }
            Log log = Log.log().string("[PGO] split histogram delimiter=").signed(delimiter).string(" total=").signed(total).string(" lengths=[");
            append(log, delimiterBase + 1, LENGTH_BUCKETS);
            log.string("] outputs=[");
            append(log, delimiterBase + 1 + LENGTH_BUCKETS, OUTPUT_BUCKETS);
            log.string("] coders=[");
            append(log, delimiterBase + 1 + LENGTH_BUCKETS + OUTPUT_BUCKETS, CODER_BUCKETS);
            log.string("]").newline();
        }
    }

    /** Hosted-only test accessor. */
    static long count(int delimiter, int cell) {
        return CallCountProfileRecorder.getRawCount(base + delimiterBucket(delimiter) * CELLS_PER_DELIMITER + cell);
    }

    private static void append(Log log, int start, int count) {
        for (int i = 0; i < count; i++) {
            if (i != 0) {
                log.character(',');
            }
            log.signed(CallCountProfileRecorder.getRawCount(start + i));
        }
    }
}
