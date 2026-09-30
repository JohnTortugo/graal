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

import java.util.concurrent.atomic.AtomicLong;

import jdk.graal.compiler.nodes.ParameterNode;
import jdk.graal.compiler.nodes.ReturnNode;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.phases.BasePhase;
import jdk.graal.compiler.phases.tiers.HighTierContext;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import jdk.vm.ci.meta.Signature;

/**
 * Inserts a {@link SplitHistogramNode} before every return of the method selected with
 * {@code -H:PGOSplitHistogramMethod}. It runs on compilation roots before inlining and on every
 * callee graph the priority inliner decodes, so each inlined copy records exactly once.
 */
final class SplitHistogramInstrumentationPhase extends BasePhase<HighTierContext> {
    private static final AtomicLong INSTRUMENTED = new AtomicLong();

    @Override
    protected void run(StructuredGraph graph, HighTierContext context) {
        instrumentGraph(graph);
    }

    static void instrumentGraph(StructuredGraph graph) {
        String selected = PGOBranchInstrumentationFeature.Options.PGOSplitHistogramMethod.getValue();
        ResolvedJavaMethod method = graph.method();
        if (selected == null || selected.isEmpty() || method == null || !matches(method, selected) || graph.getNodes().filter(SplitHistogramNode.class).isNotEmpty()) {
            return;
        }
        int first = method.isStatic() ? 0 : 1;
        ParameterNode line = graph.getParameter(first);
        ParameterNode delimiter = graph.getParameter(first + 1);
        if (line == null || delimiter == null) {
            return;
        }
        for (ReturnNode returnNode : graph.getNodes(ReturnNode.TYPE).snapshot()) {
            SplitHistogramNode node = graph.add(new SplitHistogramNode(line, delimiter, returnNode.result()));
            node.setNodeSourcePosition(returnNode.getNodeSourcePosition());
            graph.addBeforeFixed(returnNode, node);
            INSTRUMENTED.incrementAndGet();
        }
    }

    /** Matches {@code package.Class.method} with a {@code (String, char)} parameter list and object result. */
    static boolean matches(ResolvedJavaMethod method, String selected) {
        if (!selected.equals(method.format("%H.%n"))) {
            return false;
        }
        Signature signature = method.getSignature();
        return signature.getParameterCount(false) == 2 && "Ljava/lang/String;".equals(signature.getParameterType(0, null).getName()) &&
                        signature.getParameterKind(1) == JavaKind.Char && signature.getReturnKind() == JavaKind.Object;
    }

    static long instrumented() {
        return INSTRUMENTED.get();
    }
}
