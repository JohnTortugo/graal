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

import com.oracle.svm.guest.staging.core.heap.UnknownObjectField;
import com.oracle.svm.guest.staging.core.heap.UnknownPrimitiveField;
import com.oracle.svm.shared.BuildPhaseProvider.AfterCompilation;

/** Immutable metadata and shared-storage index for one instrumented conditional branch. */
public final class BranchProfileCounter {

    /** Calling context, innermost frame first, in canonical JVM descriptor form. */
    @UnknownObjectField(availability = AfterCompilation.class) private final String[] methodDescriptors;
    /** BCI for every frame in {@link #methodDescriptors}. */
    @UnknownObjectField(availability = AfterCompilation.class) private final int[] contextBcis;
    /** Source BCI of the true and false successor respectively. */
    @UnknownPrimitiveField(availability = AfterCompilation.class) private final int trueSuccessorBci;
    @UnknownPrimitiveField(availability = AfterCompilation.class) private final int falseSuccessorBci;
    /** Index into the relocation-safe shared runtime counter array. */
    @UnknownPrimitiveField(availability = AfterCompilation.class) private final int counterIndex;

    BranchProfileCounter(String[] methodDescriptors, int[] contextBcis, int trueSuccessorBci, int falseSuccessorBci, int counterIndex) {
        this.methodDescriptors = methodDescriptors;
        this.contextBcis = contextBcis;
        this.trueSuccessorBci = trueSuccessorBci;
        this.falseSuccessorBci = falseSuccessorBci;
        this.counterIndex = counterIndex;
    }

    public String[] getMethodDescriptors() {
        return methodDescriptors;
    }

    public int[] getContextBcis() {
        return contextBcis;
    }

    public int getTrueSuccessorBci() {
        return trueSuccessorBci;
    }

    public int getFalseSuccessorBci() {
        return falseSuccessorBci;
    }

    public int getCounterIndex() {
        return counterIndex;
    }

    public long getTrueCount() {
        return BranchProfileRecorder.getTrueCount(counterIndex);
    }

    public long getFalseCount() {
        return BranchProfileRecorder.getFalseCount(counterIndex);
    }
}
