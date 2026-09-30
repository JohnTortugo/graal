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

/** Immutable metadata for one physical indirect-call receiver profile site. */
public final class ReceiverProfileSite {
    private final int siteIndex;
    private final String[] methodDescriptors;
    private final int[] contextBcis;
    private final int[] receiverTypeIds;
    private final String[] receiverTypeDescriptors;

    ReceiverProfileSite(int siteIndex, String[] methodDescriptors, int[] contextBcis, int[] receiverTypeIds, String[] receiverTypeDescriptors) {
        this.siteIndex = siteIndex;
        this.methodDescriptors = methodDescriptors.clone();
        this.contextBcis = contextBcis.clone();
        this.receiverTypeIds = receiverTypeIds.clone();
        this.receiverTypeDescriptors = receiverTypeDescriptors.clone();
    }

    public int siteIndex() {
        return siteIndex;
    }

    public String[] methodDescriptors() {
        return methodDescriptors.clone();
    }

    public int[] contextBcis() {
        return contextBcis.clone();
    }

    String receiverTypeDescriptor(int typeId) {
        for (int i = 0; i < receiverTypeIds.length; i++) {
            if (receiverTypeIds[i] == typeId) {
                return receiverTypeDescriptors[i];
            }
        }
        return null;
    }
}
