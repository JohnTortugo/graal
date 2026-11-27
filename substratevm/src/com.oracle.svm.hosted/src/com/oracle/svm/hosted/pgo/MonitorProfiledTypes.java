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
package com.oracle.svm.hosted.pgo;

import java.util.Set;

import com.oracle.svm.shared.singletons.traits.SingletonTraits;
import com.oracle.svm.shared.singletons.traits.BuiltinTraits.BuildtimeAccessOnly;
import com.oracle.svm.shared.singletons.traits.BuiltinTraits.NoLayeredCallbacks;

import jdk.vm.ci.meta.JavaType;

/**
 * Build-time set of exact runtime types observed at a monitor operation in training. A type absent
 * from the set may omit its inline monitor field; runtime synchronization remains correct through
 * {@code MultiThreadedMonitorSupport}'s secondary monitor map.
 */
@SingletonTraits(access = BuildtimeAccessOnly.class, layeredCallbacks = NoLayeredCallbacks.class)
public final class MonitorProfiledTypes {
    private final Set<String> observedTypeDescriptors;
    private final long recordedEvents;
    private int synchronizedTypes;
    private int retainedTypes;

    MonitorProfiledTypes(Set<String> observedTypeDescriptors, long recordedEvents) {
        this.observedTypeDescriptors = Set.copyOf(observedTypeDescriptors);
        this.recordedEvents = recordedEvents;
    }

    /**
     * Whether {@code type} keeps its inline monitor field. The profile names types by JVM
     * descriptor, the form {@link JavaType#getName()} returns.
     */
    public boolean retainMonitorField(JavaType type) {
        return retainMonitorField(type.getName());
    }

    boolean retainMonitorField(String typeDescriptor) {
        synchronizedTypes++;
        if (observedTypeDescriptors.contains(typeDescriptor)) {
            retainedTypes++;
            return true;
        }
        return false;
    }

    int observedTypes() {
        return observedTypeDescriptors.size();
    }

    long recordedEvents() {
        return recordedEvents;
    }

    int synchronizedTypes() {
        return synchronizedTypes;
    }

    int retainedTypes() {
        return retainedTypes;
    }

    int omittedTypes() {
        return synchronizedTypes - retainedTypes;
    }
}
