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
package pgoworkload;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * Deterministic workload for the end-to-end PGO test. Every part exists because a profile applied
 * to it has gone wrong before, so each is a correctness trap for a bad profile rather than a
 * benchmark:
 *
 * <ul>
 * <li>An interface receiver whose distribution depends on the calling context, so a receiver
 * profile recorded in one context and applied (through the shortened-context fallback) in another
 * must stay correct through the type guard's fallback path.</li>
 * <li>A loop whose only real exit sits in a small boolean helper that an instrumented image inlines
 * and splits, leaving a residual branch record that says "never exits".</li>
 * <li>A reader built from a chain of small methods with a rarely taken refill path, the shape the
 * priority inliner priced wrongly.</li>
 * <li>Recursion into nested containers, a catch-handler unwind loop that runs only for malformed
 * records, fresh strings through {@code hashCode}/{@code equals}/{@code HashMap}, a
 * {@code switch} on strings, and {@code Number.doubleValue()} virtual dispatch.</li>
 * <li>Short-lived threads that are joined and a daemon thread that is still running at exit, so
 * the instrumented build must merge the counts of finished threads and snapshot those of live
 * ones.</li>
 * </ul>
 *
 * Usage: {@code PgoWorkload <seed> <records>}. The output is four lines of checksums and counts
 * that must be identical for every build of the program. The last line is a list of
 * {@code name=count} pairs that the gate passes to {@code PgoEffectsCheck}, which compares them
 * with the counts the instrumented build recorded.
 */
public final class PgoWorkload {

    /** Circles built by {@link #circlesOnly} and {@link #mixed}; printed for the profile check. */
    static long circles;
    /** Squares built by {@link #mixed}; printed for the profile check. */
    static long squares;
    /** Triangles built by {@link #mixed}; printed for the profile check. */
    static long triangles;
    /** Circles built by {@link #mixed}; {@link #circlesOnly} builds the others. */
    static long mixedCircles;
    /** How often {@link #unitAreas} called {@link #areaOf}. */
    static long unitAreaCalls;
    /** The simple class name of the receiver {@link #unitAreas} passed to {@link #areaOf}. */
    static String unitReceiver;
    /** Sum of the {@link #UNITS} values of the parsed strings, -1 for each unknown string. */
    static long unitChecksum;
    /** Rolling hash of the parsed strings. */
    static long stringChecksum;
    /** Sum of the parsed integers, each read back through a boxed {@link Number}. */
    static long boxedChecksum;
    /** How often {@link #profiledSwitch} took its first successor (values 0 to 6). */
    static long switchZero;
    /** How often {@link #profiledSwitch} took its second successor (values 7 and 8). */
    static long switchOne;
    /** How often {@link #profiledSwitch} took its default successor. */
    static long switchTwo;
    /** Number of malformed records {@link #parseRecord} skipped. */
    static int malformed;
    /** Number of records that followed a marker token. */
    static int markers;
    /** Type tag of an integer field; the type tags go in the high four bits of a header byte. */
    static final int T_INT = 1;
    /** Type tag of a string field. */
    static final int T_STR = 2;
    /** Type tag of a nested struct. */
    static final int T_STRUCT = 3;
    /** Type tag the parser rejects; written as the last field of a malformed record. */
    static final int T_BAD = 4;
    /** Type tag of a marker token, which the reader skips between records. */
    static final int T_MARK = 0xf;
    /** String field values; several differ from a {@link #UNITS} key only in case. */
    static final String[] FIELDS = {"Count", "ms", "Bytes", "count", "Percent", "Seconds", "mS"};
    /** Lower-case unit names to their codes; looked up first as written, then in lower case. */
    static final Map<String, Integer> UNITS = new HashMap<>();

    static {
        UNITS.put("count", 1);
        UNITS.put("ms", 2);
        UNITS.put("bytes", 3);
        UNITS.put("percent", 4);
        UNITS.put("seconds", 5);
    }

    // ---------------------------------------------------------------- receivers by context

    /** The receiver type of the interface calls whose receiver profiles the test checks. */
    interface Shape {
        /**
         * A value computed from the shape's area; only its determinism matters.
         *
         * @return the value
         */
        double area();

        /**
         * The shape's name, compared by {@link #countKinds}.
         *
         * @return the name in lower case
         */
        String kind();
    }

    /**
     * Gives an area computation enough self time for the stack sampler to attribute samples to the
     * implementation itself; hot-callee devirtualization only guards an interface call whose
     * callee was sampled under the compilation root. Samples are taken at safepoint checks, so the
     * loop must not be a counted one (counted loops carry no safepoint check): it runs about 16
     * iterations for any positive area.
     *
     * @param area the area
     * @return a partial harmonic sum of {@code area} that is at least {@code 3.3 * area}
     */
    static double weigh(double area) {
        double total = 0;
        int k = 1;
        while (total < area * 3.3) {
            total += area / k;
            k++;
        }
        return total;
    }

    /** A shape whose {@link #area} runs {@link #weigh}. */
    static final class Circle implements Shape {
        /** The radius. */
        final double radius;

        /**
         * Creates a circle.
         *
         * @param radius the radius
         */
        Circle(double radius) {
            this.radius = radius;
        }

        @Override
        public double area() {
            return weigh(3.0 * radius * radius);
        }

        @Override
        public String kind() {
            return "circle";
        }
    }

    /** A shape whose {@link #area} runs {@link #weigh}. */
    static final class Square implements Shape {
        /** The side length. */
        final double side;

        /**
         * Creates a square.
         *
         * @param side the side length
         */
        Square(double side) {
            this.side = side;
        }

        @Override
        public double area() {
            return weigh(side * side);
        }

        @Override
        public String kind() {
            return "square";
        }
    }

    /** A shape whose {@link #area} is a single expression, so it has little self time. */
    static final class Triangle implements Shape {
        /** The base length; the height equals it. */
        final double base;

        /**
         * Creates a triangle.
         *
         * @param base the base length, which is also the height
         */
        Triangle(double base) {
            this.base = base;
        }

        @Override
        public double area() {
            return base * base / 2;
        }

        @Override
        public String kind() {
            return "triangle";
        }
    }

    /**
     * Trivial wrapper around the interface call {@link Shape#area}. An image built with
     * {@code -H:NeverInline=pgoworkload.PgoWorkload.areaOf} (and {@code kindOf}) compiles it
     * standalone, so its receiver profile is recorded under the one-frame context; an image built
     * without that option inlines it during graph decoding, so the same call site is queried under
     * deeper contexts and must be served by the shortened-context fallback.
     *
     * @param shape the receiver
     * @return {@code shape.area()}
     */
    static double areaOf(Shape shape) {
        return shape.area();
    }

    /**
     * Trivial wrapper around the interface call {@link Shape#kind}, for the same reason as
     * {@link #areaOf}.
     *
     * @param shape the receiver
     * @return {@code shape.kind()}
     */
    static String kindOf(Shape shape) {
        return shape.kind();
    }

    /**
     * Sums {@link #areaOf} over {@code shapes}; the receiver distribution comes from the caller.
     *
     * @param shapes the shapes
     * @return the sum
     */
    static double sumAreas(Shape[] shapes) {
        double sum = 0;
        for (Shape shape : shapes) {
            sum += areaOf(shape);
        }
        return sum;
    }

    /**
     * Counts the shapes whose {@link #kindOf} is {@code kind}.
     *
     * @param shapes the shapes
     * @param kind the name to count
     * @return the count
     */
    static int countKinds(Shape[] shapes, String kind) {
        int n = 0;
        for (Shape shape : shapes) {
            if (kindOf(shape).equals(kind)) {
                n++;
            }
        }
        return n;
    }

    /**
     * Context A: all circles.
     *
     * @param n the number of shapes
     * @param seed selects the radii
     * @return the area sum plus the circle count
     */
    static double circlesOnly(int n, long seed) {
        Shape[] shapes = new Shape[n];
        for (int i = 0; i < n; i++) {
            shapes[i] = new Circle(Math.floorMod(seed + i, 7));
        }
        circles += n;
        return sumAreas(shapes) + countKinds(shapes, "circle");
    }

    /**
     * Context B: 70% squares, 20% triangles, 10% circles (the residues 0-6, 7-8 and 9).
     *
     * @param n the number of shapes
     * @param seed selects the residues
     * @return the area sum plus the square and triangle counts, scaled apart
     */
    static double mixed(int n, long seed) {
        Shape[] shapes = new Shape[n];
        for (int i = 0; i < n; i++) {
            long residue = Math.floorMod(seed * 31 + i, 10);
            if (residue < 7) {
                shapes[i] = new Square(residue);
                squares++;
            } else if (residue < 9) {
                shapes[i] = new Triangle(residue);
                triangles++;
            } else {
                shapes[i] = new Circle(residue);
                circles++;
                mixedCircles++;
            }
        }
        return sumAreas(shapes) + countKinds(shapes, "square") * 1000 + countKinds(shapes, "triangle") * 1_000_000;
    }

    /**
     * An interface call whose receiver is one type for every even record count and another for odd
     * ones; the array load keeps the type unknown to the static analysis. A profile trained on an
     * even count is monomorphic here, and an image built from it must still be right on an odd one.
     *
     * @param records the record count; its parity picks the receiver, its size the loop length
     * @return the sum of the {@link #areaOf} results
     */
    static double unitAreas(int records) {
        Shape[] units = {new Square(1.5), new Circle(1.5)};
        Shape unit = units[records % 2];
        double total = 0;
        /* Long enough for the stack sampler to see this root and the area implementation under it. */
        long iterations = 20L * records;
        for (long i = 0; i < iterations; i++) {
            total += areaOf(unit);
        }
        unitAreaCalls += iterations;
        unitReceiver = unit.getClass().getSimpleName();
        return total;
    }

    // ---------------------------------------------------------------- the reader

    /**
     * Byte-oriented reader with the small-method chain and rare refill path of a binary decoder.
     * A token starts with a header byte: the type tag in the high four bits and the length in the
     * low four, where length {@code 0xe} means that a variable-length integer with the length
     * follows.
     */
    static final class Reader {
        /** The whole input. */
        private final byte[] source;
        /** Index in {@link #source} of the next byte to read. */
        private int position;
        /** A window of {@link #source}; {@link #refill} moves it to {@link #position}. */
        private final byte[] buffer = new byte[64];
        /** Index in {@link #source} of {@code buffer[0]}. */
        private int bufferStart;
        /** Index in {@link #source} just past the last byte in {@link #buffer}. */
        private int bufferLimit;
        /** How many containers the reader is in; marker tokens are skipped only at depth 0. */
        private int depth;
        /** Whether the last {@link #nextToken} skipped a marker token. */
        private boolean onMarker;
        /** Number of tokens {@link #nextToken} returned. */
        int tokens;

        /**
         * Creates a reader positioned at the start of {@code source}.
         *
         * @param source the encoded records, as built by {@link PgoWorkload#encode}
         */
        Reader(byte[] source) {
            this.source = source;
            refill();
        }

        /** Copies the next {@link #buffer} length of input starting at {@link #position}. */
        private void refill() {
            bufferStart = position;
            int n = Math.min(buffer.length, source.length - position);
            System.arraycopy(source, position, buffer, 0, Math.max(n, 0));
            bufferLimit = position + Math.max(n, 0);
        }

        /** Returns the next byte as 0 to 255, or -1 at the end of the input; refills rarely. */
        private int readByte() {
            if (position >= bufferLimit) {
                if (position >= source.length) {
                    return -1;
                }
                refill();
            }
            return buffer[position++ - bufferStart] & 0xff;
        }

        /**
         * Reads a variable-length integer: seven bits per byte, most significant group first, and
         * the high bit set on the last byte.
         */
        private int readVarUInt() {
            int value = 0;
            int b;
            do {
                b = readByte();
                if (b < 0) {
                    throw new IllegalStateException("truncated");
                }
                value = (value << 7) | (b & 0x7f);
            } while ((b & 0x80) == 0);
            return value;
        }

        /**
         * Reads a header; returns the type tag in bits 24 and up and the length below, or -1 at the
         * end of the input.
         */
        private int readHeader() {
            int b = readByte();
            if (b < 0) {
                return -1;
            }
            int type = b >>> 4;
            int length = b & 0xf;
            if (length == 0xe) {
                length = readVarUInt();
            }
            return (type << 24) | length;
        }

        /**
         * The loop with the exit in a helper: skips marker tokens until positioned on a value.
         *
         * @return the header of the value, as {@link #readHeader} returns it, or -1 at the end of
         *         the input
         */
        int nextToken() {
            onMarker = false;
            while (true) {
                int header = readHeader();
                if (header < 0) {
                    return -1;
                }
                if (depth == 0 && isMarker(header)) {
                    onMarker = true;
                    continue;
                }
                tokens++;
                return header;
            }
        }

        private boolean isMarker(int header) {
            return (header >>> 24) == 0xf;
        }

        /**
         * Whether the last {@link #nextToken} skipped a marker token.
         *
         * @return {@code true} if it did
         */
        boolean wasOnMarker() {
            return onMarker;
        }

        /** Enters a container. */
        void stepIn() {
            depth++;
        }

        /**
         * Leaves a container.
         *
         * @throws IllegalStateException if the reader is not in a container
         */
        void stepOut() {
            if (depth <= 0) {
                throw new IllegalStateException("not in a container");
            }
            depth--;
        }

        /**
         * The container depth.
         *
         * @return how many containers the reader is in
         */
        int depth() {
            return depth;
        }

        /**
         * Reads a big-endian unsigned integer.
         *
         * @param length the number of bytes
         * @return the value
         * @throws IllegalStateException if the input ends first
         */
        long readInt(int length) {
            long value = 0;
            for (int i = 0; i < length; i++) {
                int b = readByte();
                if (b < 0) {
                    throw new IllegalStateException("truncated int");
                }
                value = (value << 8) | b;
            }
            return value;
        }

        /**
         * Reads an ISO-8859-1 string, which yields a fresh {@link String} for every field.
         *
         * @param length the number of bytes
         * @return the string
         * @throws IllegalStateException if the input ends first
         */
        String readString(int length) {
            byte[] bytes = new byte[length];
            for (int i = 0; i < length; i++) {
                int b = readByte();
                if (b < 0) {
                    throw new IllegalStateException("truncated string");
                }
                bytes[i] = (byte) b;
            }
            return new String(bytes, StandardCharsets.ISO_8859_1);
        }
    }

    /**
     * Builds a byte stream of nested records; a marker token precedes every 50th record, and every
     * 97th record is malformed.
     *
     * @param records the number of records
     * @param seed the start state of the random generator that shapes the records
     * @return the encoded stream
     */
    static byte[] encode(int records, long seed) {
        List<Byte> out = new ArrayList<>();
        long state = seed;
        for (int r = 0; r < records; r++) {
            state = state * 6364136223846793005L + 1442695040888963407L;
            if (r % 50 == 0) {
                out.add((byte) (T_MARK << 4));
            }
            boolean bad = r % 97 == 96;
            writeStruct(out, state, 0, bad);
        }
        byte[] bytes = new byte[out.size()];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = out.get(i);
        }
        return bytes;
    }

    /**
     * Appends to {@code out} a struct of two to four fields chosen from the bits of {@code state}.
     * Fields at {@code level} 2 and deeper are integers, so nesting stops there; when {@code bad}
     * is set the last field gets the {@link #T_BAD} tag.
     */
    private static void writeStruct(List<Byte> out, long state, int level, boolean bad) {
        int fields = 2 + (int) ((state >>> 8) % 3);
        /* Field values are kept non-negative so that length and kind arithmetic stays in range. */
        out.add((byte) ((T_STRUCT << 4) | 0xe));
        writeVarUInt(out, fields);
        for (int f = 0; f < fields; f++) {
            long bits = (state >>> (f * 5)) & Long.MAX_VALUE;
            int kind = (int) (bits % 3);
            if (bad && f == fields - 1) {
                out.add((byte) (T_BAD << 4));
                continue;
            }
            if (kind == 0 || level > 1) {
                int len = 1 + (int) (bits % 4);
                out.add((byte) ((T_INT << 4) | len));
                for (int i = len - 1; i >= 0; i--) {
                    out.add((byte) (bits >>> (i * 8)));
                }
            } else if (kind == 1) {
                String fieldName = FIELDS[(int) ((bits >>> 3) % FIELDS.length)];
                out.add((byte) ((T_STR << 4) | fieldName.length()));
                for (byte b : fieldName.getBytes(StandardCharsets.ISO_8859_1)) {
                    out.add(b);
                }
            } else {
                writeStruct(out, bits * 31 + 7, level + 1, false);
            }
        }
    }

    /**
     * Appends {@code value} to {@code out} in the encoding {@link Reader#readVarUInt} reads; values
     * below {@code 1 << 14} only.
     */
    private static void writeVarUInt(List<Byte> out, int value) {
        if (value < 0x80) {
            out.add((byte) (value | 0x80));
        } else {
            out.add((byte) (value >>> 7));
            out.add((byte) ((value & 0x7f) | 0x80));
        }
    }

    /** Thrown for a malformed record; it has no stack trace, so throwing it is cheap. */
    static final class Malformed extends RuntimeException {
        private static final long serialVersionUID = 1L;

        /**
         * Creates the exception.
         *
         * @param message what is wrong with the record
         */
        Malformed(String message) {
            super(message, null, false, false);
        }
    }

    /**
     * What the threads of {@link #exerciseThreadLifecycle} or {@link #exerciseLiveSnapshot}
     * computed.
     *
     * @param checksum the sum of the values the threads computed
     * @param trueCount how often the threads' branch took its first arm
     * @param falseCount how often it took the other arm
     */
    record ThreadLifecycleResult(long checksum, long trueCount, long falseCount) {
    }

    /**
     * Spins for 50 ms so that the stack sampler records samples from a short-lived thread before it
     * is joined. The gate checks that the profile has a sample in this method.
     */
    static void sampleJoinedThread() {
        long deadline = System.nanoTime() + 50_000_000L;
        while (System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
    }

    /**
     * Spins for 100 ms so that the stack sampler records samples from a thread that is still alive
     * at exit, then blocks on {@code neverReleased} until the program ends. The gate checks that
     * the profile has a sample in this method.
     *
     * @param neverReleased a latch nobody counts down
     */
    static void sampleLiveThread(CountDownLatch neverReleased) {
        long deadline = System.nanoTime() + 100_000_000L;
        while (System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        try {
            neverReleased.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * An interface call made only from the worker threads; its receiver profile must hold the
     * receivers of every thread.
     *
     * @param shape the receiver
     * @return the length of {@code shape.kind()}
     */
    static int threadedReceiver(Shape shape) {
        return shape.kind().length();
    }

    /**
     * A branch taken only on the live daemon thread of {@link #exerciseLiveSnapshot}; the program
     * counts its outcomes itself so that the recorded counts can be checked.
     *
     * @param value the tested value, 0 to 3
     * @param counts incremented at index 0 when {@code value < 2}, at index 1 otherwise
     * @return 11 or 13, by the arm taken
     */
    static int liveSnapshotBranch(int value, long[] counts) {
        if (value < 2) {
            counts[0]++;
            return 11;
        }
        counts[1]++;
        return 13;
    }

    /**
     * Leaves a daemon thread alive so the teardown dump must snapshot its private recorder tables.
     *
     * @return the daemon thread's checksum and the outcomes of its {@link #liveSnapshotBranch}
     */
    static ThreadLifecycleResult exerciseLiveSnapshot() {
        CountDownLatch recorded = new CountDownLatch(1);
        CountDownLatch neverReleased = new CountDownLatch(1);
        long[] counts = new long[2];
        long[] checksum = new long[1];
        Thread daemon = new Thread(() -> {
            Shape receiver = new Circle(1.0);
            for (int i = 0; i < 4_000; i++) {
                checksum[0] += liveSnapshotBranch(i & 3, counts) + threadedReceiver(receiver);
            }
            recorded.countDown();
            sampleLiveThread(neverReleased);
        });
        daemon.setDaemon(true);
        daemon.start();
        try {
            recorded.await();
            Thread.sleep(60);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for live recorder thread", exception);
        }
        return new ThreadLifecycleResult(checksum[0], counts[0], counts[1]);
    }

    /**
     * A branch taken only on the short-lived threads of {@link #exerciseThreadLifecycle}; the
     * program counts its outcomes itself so that the recorded counts can be checked.
     *
     * @param value the tested value, 0 to 3
     * @param counts incremented at index 0 when {@code value == 0}, at index 1 otherwise
     * @return 3 or 7, by the arm taken
     */
    static int threadedBranch(int value, long[] counts) {
        if (value == 0) {
            counts[0]++;
            return 3;
        }
        counts[1]++;
        return 7;
    }

    /**
     * Exercises recorder allocation, live counting, exit-time merge, and short-lived thread
     * teardown.
     *
     * @param records the record count; sets the iterations per thread, at least 1000
     * @return the threads' checksum and the outcomes of their {@link #threadedBranch}
     */
    static ThreadLifecycleResult exerciseThreadLifecycle(int records) {
        int threadCount = 8;
        int iterations = Math.max(1_000, records / 100);
        Thread[] threads = new Thread[threadCount];
        long[] checksums = new long[threadCount];
        long[] trueCounts = new long[threadCount];
        for (int threadIndex = 0; threadIndex < threadCount; threadIndex++) {
            final int index = threadIndex;
            threads[index] = new Thread(() -> {
                long checksum = 0;
                long[] counts = new long[2];
                Shape receiver = new Square(1.0);
                for (int i = 0; i < iterations; i++) {
                    checksum += threadedBranch((i + index) & 3, counts) + threadedReceiver(receiver);
                }
                checksums[index] = checksum;
                trueCounts[index] = counts[0];
                sampleJoinedThread();
            });
            threads[index].start();
        }
        long checksum = 0;
        long trueCount = 0;
        for (int i = 0; i < threadCount; i++) {
            try {
                threads[i].join();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while joining recorder lifecycle thread", exception);
            }
            checksum += checksums[i];
            trueCount += trueCounts[i];
        }
        long total = (long) threadCount * iterations;
        return new ThreadLifecycleResult(checksum, trueCount, total - trueCount);
    }

    /**
     * A three-successor switch with an independently counted 70/20/10 distribution.
     *
     * @param value the switch value, 0 to 9
     * @return 3, 5 or 7, by the successor taken
     */
    static int profiledSwitch(int value) {
        return switch (value) {
            case 0, 1, 2, 3, 4, 5, 6 -> {
                switchZero++;
                yield 3;
            }
            case 7, 8 -> {
                switchOne++;
                yield 5;
            }
            default -> {
                switchTwo++;
                yield 7;
            }
        };
    }

    /**
     * Parses one struct recursively, the way a log-entry parser walks a binary record.
     *
     * @param reader the reader, positioned on the struct's first field
     * @param fieldCount the number of fields
     * @return a checksum of the fields
     * @throws Malformed if the input ends early or a field has an unknown type tag
     */
    static long parseStruct(Reader reader, int fieldCount) {
        long sum = 0;
        for (int f = 0; f < fieldCount; f++) {
            int header = reader.nextToken();
            if (header < 0) {
                throw new Malformed("truncated struct");
            }
            int type = header >>> 24;
            int length = header & 0xffffff;
            switch (type) {
                case T_INT -> {
                    long value = reader.readInt(length);
                    Number boxed = (value & 1) == 0 ? Long.valueOf(value) : Double.valueOf(value);
                    boxedChecksum += (long) boxed.doubleValue();
                    sum += value;
                }
                case T_STR -> {
                    String text = reader.readString(length);
                    stringChecksum = stringChecksum * 31 + text.hashCode();
                    Integer unit = UNITS.get(text);
                    if (unit == null) {
                        unit = UNITS.get(text.toLowerCase());
                    }
                    unitChecksum += unit == null ? -1 : unit;
                    switch (text) {
                        case "Count", "count" -> sum += 1;
                        case "ms", "mS" -> sum += 2;
                        default -> sum += text.length();
                    }
                }
                case T_STRUCT -> {
                    reader.stepIn();
                    sum += 7 * parseStruct(reader, length);
                    reader.stepOut();
                }
                default -> throw new Malformed("bad field type " + type);
            }
        }
        return sum;
    }

    /**
     * Parses one record; on a malformed record unwinds the container depth like a log reader does.
     *
     * @param reader the reader, positioned before the record
     * @return the record's checksum, -1 for a malformed record, or {@link Long#MIN_VALUE} at the
     *         end of the input
     */
    static long parseRecord(Reader reader) {
        int header = reader.nextToken();
        if (header < 0) {
            return Long.MIN_VALUE;
        }
        if (reader.wasOnMarker()) {
            markers++;
        }
        try {
            if ((header >>> 24) != T_STRUCT) {
                throw new Malformed("record is not a struct");
            }
            reader.stepIn();
            long value = parseStruct(reader, header & 0xffffff);
            reader.stepOut();
            return value;
        } catch (Malformed e) {
            while (reader.depth() > 0) {
                reader.stepOut();
            }
            malformed++;
            return -1;
        }
    }

    /**
     * Runs every part of the workload once and prints the results.
     *
     * @param args the seed and the number of records
     */
    public static void main(String[] args) {
        long seed = Long.parseLong(args[0]);
        int records = Integer.parseInt(args[1]);
        byte[] data = encode(records, seed);
        Reader reader = new Reader(data);
        long recordSum = 0;
        int count = 0;
        long parseCalls = 0;
        while (true) {
            long recordValue = parseRecord(reader);
            parseCalls++;
            if (recordValue == Long.MIN_VALUE) {
                break;
            }
            recordSum += recordValue;
            count++;
        }
        double shapes = 0;
        for (int i = 0; i < Math.max(1, records / 100); i++) {
            shapes += circlesOnly(64, seed + i) + mixed(64, seed - i);
        }
        long splits = 0;
        int splitCalls = Math.max(1, records / 50);
        for (int i = 0; i < splitCalls; i++) {
            String line = "a=" + i + ",bb=" + (i * 7) + ",ccc=" + (i % 13) + (i % 3 == 0 ? ",d" : "");
            List<String> parts = splitFields(line, ',');
            splits += parts.size() * 31L + parts.get(parts.size() - 1).length();
        }
        long switchChecksum = 0;
        for (int i = 0; i < records; i++) {
            switchChecksum += profiledSwitch(i % 10);
        }
        System.out.println("records=" + count + " malformed=" + malformed + " markers=" + markers + " tokens=" + reader.tokens);
        System.out.println("recordSum=" + recordSum + " units=" + unitChecksum + " strings=" + stringChecksum + " boxed=" + boxedChecksum);
        double units = unitAreas(records);
        System.out.println("shapes=" + shapes + " bytes=" + data.length + " splits=" + splits + " units=" + units);
        ThreadLifecycleResult lifecycle = exerciseThreadLifecycle(records);
        ThreadLifecycleResult liveSnapshot = exerciseLiveSnapshot();
        System.out.println("circles=" + circles + " squares=" + squares + " triangles=" + triangles + " mixedCircles=" + mixedCircles +
                        " unitAreaCalls=" + unitAreaCalls + " unitReceiver=" + unitReceiver + " parseCalls=" + parseCalls +
                        " splitCalls=" + splitCalls + " switchChecksum=" + switchChecksum + " switchZero=" + switchZero + " switchOne=" + switchOne + " switchTwo=" + switchTwo +
                        " threadChecksum=" + lifecycle.checksum() + " threadTrue=" + lifecycle.trueCount() + " threadFalse=" + lifecycle.falseCount() +
                        " liveChecksum=" + liveSnapshot.checksum() + " liveTrue=" + liveSnapshot.trueCount() + " liveFalse=" + liveSnapshot.falseCount());
    }

    /**
     * Splits {@code line} at every {@code delimiter}; the gate checks its recorded call count
     * against the program's own {@code splitCalls}.
     *
     * @param line the text to split
     * @param delimiter the separator
     * @return the parts, including an empty last part when {@code line} ends with the separator
     */
    static ArrayList<String> splitFields(String line, char delimiter) {
        ArrayList<String> parts = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < line.length(); i++) {
            if (line.charAt(i) == delimiter) {
                parts.add(line.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(line.substring(start));
        return parts;
    }
}
