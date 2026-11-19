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
package com.oracle.svm.hosted.pgo.profiles;

/**
 * Deterministic build-time summary describing how well an iprof file's {@code conditionalProfiles}
 * could be mapped onto the current image. The numbers depend only on the profile content and the
 * hosted universe, so two builds of the same inputs produce identical diagnostics -- useful for
 * regression detection.
 *
 * @param profileVersion the iprof schema version that was consumed.
 * @param totalEntries number of {@code conditionalProfiles} entries in the file.
 * @param resolvedEntries entries whose entire calling context resolved to methods present in the
 *            image and were installed into the lookup table.
 * @param unresolvedEntries entries dropped because at least one context frame referenced a method
 *            absent from the image.
 * @param duplicateContextEntries entries whose canonical context collided with an
 *            already-installed entry (kept: first-wins) and were skipped.
 * @param singleFrameEntries resolved entries with a context of exactly one frame (no inlining).
 * @param inlinedContextEntries resolved entries with a multi-frame (inlined) context.
 */
public record ConditionalProfileDiagnostics(
                String profileVersion,
                int totalEntries,
                int resolvedEntries,
                int unresolvedEntries,
                int duplicateContextEntries,
                int singleFrameEntries,
                int inlinedContextEntries) {

    /** Fraction (0..1) of file entries that were installed into the lookup table. */
    public double resolutionRatio() {
        return totalEntries == 0 ? 0.0 : (double) resolvedEntries / totalEntries;
    }

    /** A stable, single-line, human-readable summary suitable for build logs. */
    public String summary() {
        return String.format("iprof conditionalProfiles v%s: %d entries, %d resolved (%.1f%%), %d unresolved, %d duplicate-context; %d single-frame, %d inlined-context",
                        profileVersion, totalEntries, resolvedEntries, resolutionRatio() * 100.0, unresolvedEntries, duplicateContextEntries, singleFrameEntries, inlinedContextEntries);
    }
}
