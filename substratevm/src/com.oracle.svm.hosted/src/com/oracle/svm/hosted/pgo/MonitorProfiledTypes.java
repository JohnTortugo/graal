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

    public MonitorProfiledTypes(Set<String> observedTypeDescriptors, long recordedEvents) {
        this.observedTypeDescriptors = Set.copyOf(observedTypeDescriptors);
        this.recordedEvents = recordedEvents;
    }

    public boolean retainMonitorField(String typeDescriptor) {
        synchronizedTypes++;
        if (observedTypeDescriptors.contains(typeDescriptor)) {
            retainedTypes++;
            return true;
        }
        return false;
    }

    public int observedTypes() {
        return observedTypeDescriptors.size();
    }

    public long recordedEvents() {
        return recordedEvents;
    }

    public int synchronizedTypes() {
        return synchronizedTypes;
    }

    public int retainedTypes() {
        return retainedTypes;
    }

    public int omittedTypes() {
        return synchronizedTypes - retainedTypes;
    }
}
