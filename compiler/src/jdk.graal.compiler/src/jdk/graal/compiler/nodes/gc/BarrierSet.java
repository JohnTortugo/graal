/*
 * Copyright (c) 2019, 2022, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2019, Red Hat Inc. All rights reserved.
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
package jdk.graal.compiler.nodes.gc;

import org.graalvm.word.LocationIdentity;

import jdk.graal.compiler.core.common.memory.BarrierType;
import jdk.graal.compiler.core.common.type.Stamp;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.extended.RawStoreNode;
import jdk.graal.compiler.nodes.memory.FixedAccessNode;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.ResolvedJavaField;

public interface BarrierSet {

    //@formatter:off
    //// YES YES YES YES YES YES DONE

    boolean hasWriteBarrier();

    boolean hasReadBarrier();

    /**
     * Perform verification of inserted or missing barriers.
     *
     * @param graph the graph to verify.
     */
    default void verifyBarriers(StructuredGraph graph) {
    }

    // What type of barrier for reading a static or instance field
    BarrierType fieldReadBarrierType(ResolvedJavaField field, JavaKind storageKind);

    /// NOT NOT NOT NOT NOT NOT DONE




    /**
     * Checks whether writing to {@link LocationIdentity#INIT_LOCATION} can be performed with an
     * intervening allocation.
     */
    default BarrierType postAllocationInitBarrier(BarrierType original) {
        return original;
    }

    // Add the needed GC barriers according to type of 'n'
    void addBarriers(FixedAccessNode n);


    // What type of barrier for a field write
    BarrierType fieldWriteBarrierType(ResolvedJavaField field, JavaKind storageKind);

    // What type of barrier for a ... ?!
    BarrierType readBarrierType(LocationIdentity location, ValueNode address, Stamp loadStamp);

    /**
     * @param location
     */
    // What type of barrier for ...? apparently to write on Current Thread something
    default BarrierType writeBarrierType(LocationIdentity location) {
        return BarrierType.NONE;
    }

    // What type of barrier for a write to an Unsafe memory region.
    BarrierType writeBarrierType(RawStoreNode store);

    // What type of barrier when writing to an array entry.
    BarrierType arrayWriteBarrierType(JavaKind storageKind);

    // What type of barrier for a read+write happening on the same node - like a CAS
    // Not sure if only used for reference types, probably not.
    BarrierType readWriteBarrier(ValueNode object, ValueNode value);



    //@formatter:on
}
