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
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Registry for variable-arity switch successor counters. */
public final class SwitchProfileRecorder {
    private static final ConcurrentMap<Integer, SwitchProfileCounter> SWITCHES = new ConcurrentHashMap<>();
    private static final AtomicInteger NEXT_SWITCH = new AtomicInteger();
    private static boolean enabled;
    private static final SwitchProfileCounter UNUSED = create(new String[]{"Lcom/oracle/svm/core/pgo/SwitchProfileRecorder;.__unused__()V"}, new int[]{0}, new int[]{-1, -2});

    private SwitchProfileRecorder() {
    }

    public static void enable() {
        enabled = true;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static SwitchProfileCounter create(String[] methodDescriptors, int[] contextBcis, int[] successorBcis) {
        if (methodDescriptors.length == 0 || methodDescriptors.length != contextBcis.length || successorBcis.length < 2) {
            throw new IllegalArgumentException("Switch profile metadata must contain a context and at least two successors");
        }
        int[] counters = new int[successorBcis.length];
        for (int i = 0; i < counters.length; i++) {
            counters[i] = CallCountProfileRecorder.allocateRawCounter();
        }
        SwitchProfileCounter profile = new SwitchProfileCounter(methodDescriptors, contextBcis, successorBcis, counters);
        SWITCHES.put(NEXT_SWITCH.getAndIncrement(), profile);
        return profile;
    }

    public static List<SwitchProfileCounter> profiles() {
        List<SwitchProfileCounter> result = new ArrayList<>();
        for (SwitchProfileCounter profile : SWITCHES.values()) {
            if (profile != UNUSED) {
                result.add(profile);
            }
        }
        result.sort(Comparator.comparing((SwitchProfileCounter profile) -> String.join("\u0000", profile.methodDescriptors()))
                        .thenComparing(profile -> java.util.Arrays.toString(profile.contextBcis()))
                        .thenComparing(profile -> profile.successorCount()));
        return result;
    }
}
