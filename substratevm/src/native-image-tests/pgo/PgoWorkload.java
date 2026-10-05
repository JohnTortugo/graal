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
 * </ul>
 *
 * Usage: {@code PgoWorkload <seed> <records>}. The output is a set of checksums that must be
 * identical for every build of the program.
 */
public final class PgoWorkload {

    // ---------------------------------------------------------------- receivers by context

    interface Shape {
        double area();

        String kind();
    }

    static final class Circle implements Shape {
        final double r;

        Circle(double r) {
            this.r = r;
        }

        @Override
        public double area() {
            return 3.0 * r * r;
        }

        @Override
        public String kind() {
            return "circle";
        }
    }

    static final class Square implements Shape {
        final double s;

        Square(double s) {
            this.s = s;
        }

        @Override
        public double area() {
            return s * s;
        }

        @Override
        public String kind() {
            return "square";
        }
    }

    static final class Triangle implements Shape {
        final double b;

        Triangle(double b) {
            this.b = b;
        }

        @Override
        public double area() {
            return b * b / 2;
        }

        @Override
        public String kind() {
            return "triangle";
        }
    }

    /**
     * Trivial wrappers around the interface calls. An image built with
     * {@code -H:NeverInline=pgoworkload.PgoWorkload.areaOf} (and {@code kindOf}) compiles them
     * standalone, so their receiver profiles are recorded under the one-frame context; an image
     * built without that option inlines them during graph decoding, so the same call sites are
     * queried under deeper contexts and must be served by the shortened-context fallback.
     */
    static double areaOf(Shape shape) {
        return shape.area();
    }

    static String kindOf(Shape shape) {
        return shape.kind();
    }

    static double sumAreas(Shape[] shapes) {
        double sum = 0;
        for (Shape shape : shapes) {
            sum += areaOf(shape);
        }
        return sum;
    }

    static int countKinds(Shape[] shapes, String kind) {
        int n = 0;
        for (Shape shape : shapes) {
            if (kindOf(shape).equals(kind)) {
                n++;
            }
        }
        return n;
    }

    /** Context A: all circles. */
    static double circlesOnly(int n, long seed) {
        Shape[] shapes = new Shape[n];
        for (int i = 0; i < n; i++) {
            shapes[i] = new Circle((seed + i) % 7);
        }
        return sumAreas(shapes) + countKinds(shapes, "circle");
    }

    /** Context B: mostly squares, some triangles, a few circles. */
    static double mixed(int n, long seed) {
        Shape[] shapes = new Shape[n];
        for (int i = 0; i < n; i++) {
            long v = (seed * 31 + i) % 10;
            shapes[i] = v < 7 ? new Square(v) : v < 9 ? new Triangle(v) : new Circle(v);
        }
        return sumAreas(shapes) + countKinds(shapes, "square") * 1000 + countKinds(shapes, "triangle") * 1_000_000;
    }

    // ---------------------------------------------------------------- the reader

    /** Byte-oriented reader with the small-method chain and rare refill path of a binary decoder. */
    static final class Reader {
        private final byte[] source;
        private int position;
        private final byte[] buffer = new byte[64];
        private int bufferStart;
        private int bufferLimit;
        private int depth;
        private boolean onMarker;
        int tokens;
        long sum;

        Reader(byte[] source) {
            this.source = source;
            refill();
        }

        private void refill() {
            bufferStart = position;
            int n = Math.min(buffer.length, source.length - position);
            System.arraycopy(source, position, buffer, 0, Math.max(n, 0));
            bufferLimit = position + Math.max(n, 0);
        }

        private int readByte() {
            if (position >= bufferLimit) {
                if (position >= source.length) {
                    return -1;
                }
                refill();
            }
            return buffer[position++ - bufferStart] & 0xff;
        }

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

        /** The loop with the exit in a helper: skips marker tokens until positioned on a value. */
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

        boolean wasOnMarker() {
            return onMarker;
        }

        void stepIn() {
            depth++;
        }

        void stepOut() {
            if (depth <= 0) {
                throw new IllegalStateException("not in a container");
            }
            depth--;
        }

        int depth() {
            return depth;
        }

        long readInt(int length) {
            long v = 0;
            for (int i = 0; i < length; i++) {
                int b = readByte();
                if (b < 0) {
                    throw new IllegalStateException("truncated int");
                }
                v = (v << 8) | b;
            }
            return v;
        }

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

    static final int T_INT = 1;
    static final int T_STR = 2;
    static final int T_STRUCT = 3;
    static final int T_BAD = 4;
    static final int T_MARK = 0xf;

    static final String[] FIELDS = {"Count", "ms", "Bytes", "count", "Percent", "Seconds", "mS"};
    static final Map<String, Integer> UNITS = new HashMap<>();
    static {
        UNITS.put("count", 1);
        UNITS.put("ms", 2);
        UNITS.put("bytes", 3);
        UNITS.put("percent", 4);
        UNITS.put("seconds", 5);
    }

    /** Builds a byte stream of nested records; every 97th record is malformed. */
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

    private static void writeStruct(List<Byte> out, long state, int level, boolean bad) {
        int fields = 2 + (int) ((state >>> 8) % 3);
        /* Field values are kept non-negative so that length and kind arithmetic stays in range. */
        out.add((byte) ((T_STRUCT << 4) | 0xe));
        writeVarUInt(out, fields);
        for (int f = 0; f < fields; f++) {
            long v = (state >>> (f * 5)) & Long.MAX_VALUE;
            int kind = (int) (v % 3);
            if (bad && f == fields - 1) {
                out.add((byte) (T_BAD << 4));
                continue;
            }
            if (kind == 0 || level > 1) {
                int len = 1 + (int) (v % 4);
                out.add((byte) ((T_INT << 4) | len));
                for (int i = len - 1; i >= 0; i--) {
                    out.add((byte) (v >>> (i * 8)));
                }
            } else if (kind == 1) {
                String s = FIELDS[(int) ((v >>> 3) % FIELDS.length)];
                out.add((byte) ((T_STR << 4) | s.length()));
                for (byte b : s.getBytes(StandardCharsets.ISO_8859_1)) {
                    out.add(b);
                }
            } else {
                writeStruct(out, v * 31 + 7, level + 1, false);
            }
        }
    }

    private static void writeVarUInt(List<Byte> out, int value) {
        if (value < 0x80) {
            out.add((byte) (value | 0x80));
        } else {
            out.add((byte) (value >>> 7));
            out.add((byte) ((value & 0x7f) | 0x80));
        }
    }

    static final class Malformed extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Malformed(String message) {
            super(message, null, false, false);
        }
    }

    static long unitChecksum;
    static long stringChecksum;
    static long boxedChecksum;
    static int malformed;
    static int markers;

    /** Parses one struct recursively, the way a log-entry parser walks a binary record. */
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
                    long v = reader.readInt(length);
                    Number boxed = (v & 1) == 0 ? Long.valueOf(v) : Double.valueOf(v);
                    boxedChecksum += (long) boxed.doubleValue();
                    sum += v;
                }
                case T_STR -> {
                    String s = reader.readString(length);
                    stringChecksum = stringChecksum * 31 + s.hashCode();
                    Integer unit = UNITS.get(s);
                    if (unit == null) {
                        unit = UNITS.get(s.toLowerCase());
                    }
                    unitChecksum += unit == null ? -1 : unit;
                    switch (s) {
                        case "Count", "count" -> sum += 1;
                        case "ms", "mS" -> sum += 2;
                        default -> sum += s.length();
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

    /** Parses one record; on a malformed record unwinds the container depth like a log reader does. */
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
            long v = parseStruct(reader, header & 0xffffff);
            reader.stepOut();
            return v;
        } catch (Malformed e) {
            while (reader.depth() > 0) {
                reader.stepOut();
            }
            malformed++;
            return -1;
        }
    }

    public static void main(String[] args) {
        long seed = Long.parseLong(args[0]);
        int records = Integer.parseInt(args[1]);
        byte[] data = encode(records, seed);
        Reader reader = new Reader(data);
        long recordSum = 0;
        int count = 0;
        while (true) {
            long v = parseRecord(reader);
            if (v == Long.MIN_VALUE) {
                break;
            }
            recordSum += v;
            count++;
        }
        double shapes = 0;
        for (int i = 0; i < Math.max(1, records / 100); i++) {
            shapes += circlesOnly(64, seed + i) + mixed(64, seed - i);
        }
        long splits = 0;
        for (int i = 0; i < Math.max(1, records / 50); i++) {
            String line = "a=" + i + ",bb=" + (i * 7) + ",ccc=" + (i % 13) + (i % 3 == 0 ? ",d" : "");
            List<String> parts = splitFields(line, ',');
            splits += parts.size() * 31L + parts.get(parts.size() - 1).length();
        }
        System.out.println("records=" + count + " malformed=" + malformed + " markers=" + markers + " tokens=" + reader.tokens);
        System.out.println("recordSum=" + recordSum + " units=" + unitChecksum + " strings=" + stringChecksum + " boxed=" + boxedChecksum);
        System.out.println("shapes=" + shapes + " bytes=" + data.length + " splits=" + splits);
    }

    /**
     * A {@code (String, char)} splitter of the shape the experimental split histogram
     * ({@code -H:PGOSplitHistogramMethod}) instruments.
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
