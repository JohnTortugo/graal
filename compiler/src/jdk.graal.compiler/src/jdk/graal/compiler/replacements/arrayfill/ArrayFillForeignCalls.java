/*
 * Copyright (c) 2018, 2021, Oracle and/or its affiliates. All rights reserved.
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
package jdk.graal.compiler.replacements.arrayfill;

import jdk.graal.compiler.core.common.LIRKind;
import jdk.graal.compiler.core.common.spi.ForeignCallDescriptor;
import jdk.graal.compiler.core.common.spi.ForeignCallLinkage;
import jdk.graal.compiler.core.common.spi.ForeignCallSignature;
import jdk.graal.compiler.core.common.spi.ForeignCallsProvider;
import jdk.vm.ci.meta.JavaKind;

public final class ArrayFillForeignCalls {
    private static final ForeignCallDescriptor STUB_BYTE_ARRAY_FILL = ForeignCalls.pureFunctionForeignCallDescriptor("byteArrayFill", void.class, Pointer.class, int.class, byte.class);
    private static final ForeignCallDescriptor STUB_INT_ARRAY_FILL = ForeignCalls.pureFunctionForeignCallDescriptor("intArrayFill", void.class, Pointer.class, int.class, int.class);

    public static final ForeignCallDescriptor[] STUBS = {
                    STUB_BYTE_ARRAY_FILL,
                    STUB_INT_ARRAY_FILL,
    };

    public static ForeignCallDescriptor getArrayFillStub(ArrayFillNode arrayFillNode) {
        JavaKind kind = arrayFillNode.getElementKind();
        switch (kind) {
            case Byte:
                return STUB_BYTE_ARRAY_FILL;
            case Int:
                return STUB_INT_ARRAY_FILL;
            default:
                return null;
        }
    }

    ForeignCallDescriptor lookupArrayfillDescriptor(JavaKind kind, boolean aligned, boolean disjoint, boolean uninit, LocationIdentity killedLocation) {
    }

}
