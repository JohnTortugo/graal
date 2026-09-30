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
import com.oracle.svm.core.pgo.CallCountProfileCounter;
import com.oracle.svm.core.pgo.CallCountProfileRecorder;
import com.oracle.svm.hosted.pgo.profiles.ConditionalProfileContextResolver;

import jdk.graal.compiler.graph.NodeSourcePosition;
import jdk.graal.compiler.nodes.Invoke;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.phases.BasePhase;
import jdk.graal.compiler.phases.tiers.HighTierContext;
import jdk.vm.ci.meta.ResolvedJavaMethod;

/** Counts exact call edges that remain after hosted inlining. */
final class CallCountProfileInstrumentationPhase extends BasePhase<HighTierContext> {
    private static final AtomicLong INSTRUMENTED = new AtomicLong();
    private static final AtomicLong SKIPPED = new AtomicLong();

    @Override
    protected void run(StructuredGraph graph, HighTierContext context) {
        if (graph.method() == null || BranchProfileInstrumentationPhase.isNativeImageRuntimeMethod(graph.method().getDeclaringClass().getName())) {
            return;
        }
        List<Invoke> invokes = new ArrayList<>();
        graph.getInvokes().forEach(invokes::add);
        for (Invoke invoke : invokes) {
            ResolvedJavaMethod target = exactTarget(invoke);
            NodeSourcePosition caller = invoke.asNode().getNodeSourcePosition();
            if (target == null || caller == null) {
                SKIPPED.incrementAndGet();
                continue;
            }
            NodeSourcePosition position = new NodeSourcePosition(caller, target, 0);
            List<String> methods = new ArrayList<>();
            List<Integer> bcis = new ArrayList<>();
            for (NodeSourcePosition frame = position; frame != null; frame = frame.getCaller()) {
                methods.add(ConditionalProfileContextResolver.methodDescriptor(frame.getMethod()));
                bcis.add(frame.getBCI());
            }
            CallCountProfileCounter counter = CallCountProfileRecorder.create(methods.toArray(String[]::new), bcis.stream().mapToInt(Integer::intValue).toArray());
            CallCountProfileMarkerNode marker = graph.add(new CallCountProfileMarkerNode(counter));
            marker.setNodeSourcePosition(position);
            graph.addBeforeFixed(invoke.asFixedNode(), marker);
            INSTRUMENTED.incrementAndGet();
        }
    }

    private static ResolvedJavaMethod exactTarget(Invoke invoke) {
        if (invoke.getInvokeKind().isDirect()) {
            return invoke.getTargetMethod();
        }
        if (invoke.callTarget() instanceof SubstrateMethodCallTargetNode target && target.getStaticMethodProfile() != null && target.getStaticMethodProfile().getMethods().length == 1 &&
                        target.getStaticMethodProfile().getNotRecordedProbability() == 0.0) {
            return target.getStaticMethodProfile().getMethods()[0].getMethod();
        }
        return null;
    }

    static long instrumented() {
        return INSTRUMENTED.get();
    }

    static long skipped() {
        return SKIPPED.get();
    }
}
