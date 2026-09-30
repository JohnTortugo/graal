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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import com.oracle.svm.core.nodes.SubstrateMethodCallTargetNode;
import com.oracle.svm.core.pgo.ReceiverProfileRecorder;
import com.oracle.svm.core.pgo.ReceiverProfileSite;
import com.oracle.svm.hosted.meta.HostedType;
import com.oracle.svm.hosted.pgo.phases.PGOApplyProfilesPhase;
import com.oracle.svm.hosted.pgo.profiles.ConditionalProfileContextResolver;

import jdk.graal.compiler.graph.NodeSourcePosition;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.java.MethodCallTargetNode;
import jdk.graal.compiler.phases.BasePhase;
import jdk.graal.compiler.phases.tiers.HighTierContext;
import jdk.vm.ci.meta.JavaTypeProfile;
import jdk.vm.ci.meta.JavaTypeProfile.ProfiledType;

/** Instruments indirect invokes that remain after priority inlining. */
final class ReceiverProfileInstrumentationPhase extends BasePhase<HighTierContext> {
    private static final AtomicLong INSTRUMENTED_INVOKES = new AtomicLong();
    private static final AtomicLong SKIPPED_INVOKES = new AtomicLong();

    @Override
    protected void run(StructuredGraph graph, HighTierContext context) {
        if (graph.method() == null || BranchProfileInstrumentationPhase.isNativeImageRuntimeMethod(graph.method().getDeclaringClass().getName())) {
            return;
        }
        for (MethodCallTargetNode callTarget : graph.getNodes(MethodCallTargetNode.TYPE).snapshot()) {
            if (!callTarget.invokeKind().isIndirect() || callTarget.receiver() == null || !(callTarget instanceof SubstrateMethodCallTargetNode substrateTarget)) {
                continue;
            }
            JavaTypeProfile staticProfile = substrateTarget.getStaticTypeProfile();
            NodeSourcePosition position = PGOApplyProfilesPhase.createPointContext(callTarget.getNodeSourcePosition(), null);
            if (staticProfile == null || staticProfile.getTypes().length == 0 || position == null || callTarget.invoke() == null) {
                SKIPPED_INVOKES.incrementAndGet();
                continue;
            }
            List<Integer> typeIds = new ArrayList<>();
            List<String> typeDescriptors = new ArrayList<>();
            for (ProfiledType profiledType : staticProfile.getTypes()) {
                if (profiledType.getType() instanceof HostedType type) {
                    typeIds.add(type.getHub().getTypeID());
                    typeDescriptors.add(type.getName());
                }
            }
            if (typeIds.isEmpty()) {
                SKIPPED_INVOKES.incrementAndGet();
                continue;
            }
            List<String> methods = new ArrayList<>();
            List<Integer> bcis = new ArrayList<>();
            for (NodeSourcePosition frame = position; frame != null; frame = frame.getCaller()) {
                methods.add(ConditionalProfileContextResolver.methodDescriptor(frame.getMethod()));
                bcis.add(frame.getBCI());
            }
            ReceiverProfileSite site = ReceiverProfileRecorder.createSite(methods.toArray(String[]::new), bcis.stream().mapToInt(Integer::intValue).toArray(),
                            typeIds.stream().mapToInt(Integer::intValue).toArray(), typeDescriptors.toArray(String[]::new));
            ReceiverProfileCounterNode counter = graph.add(new ReceiverProfileCounterNode(site.siteIndex(), callTarget.receiver()));
            counter.setNodeSourcePosition(callTarget.getNodeSourcePosition());
            graph.addBeforeFixed(callTarget.invoke().asFixedNode(), counter);
            INSTRUMENTED_INVOKES.incrementAndGet();
        }
    }

    static long instrumentedInvokes() {
        return INSTRUMENTED_INVOKES.get();
    }

    static long skippedInvokes() {
        return SKIPPED_INVOKES.get();
    }
}
