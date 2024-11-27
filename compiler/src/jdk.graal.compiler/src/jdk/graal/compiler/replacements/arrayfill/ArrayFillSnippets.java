/*
 * Copyright (c) 2011, 2022, Oracle and/or its affiliates. All rights reserved.
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

import static jdk.graal.compiler.nodes.extended.BranchProbabilityNode.DEOPT_PROBABILITY;
import static jdk.graal.compiler.nodes.extended.BranchProbabilityNode.FAST_PATH_PROBABILITY;
import static jdk.graal.compiler.nodes.extended.BranchProbabilityNode.FREQUENT_PROBABILITY;
import static jdk.graal.compiler.nodes.extended.BranchProbabilityNode.NOT_FREQUENT_PROBABILITY;
import static jdk.graal.compiler.nodes.extended.BranchProbabilityNode.probability;

import java.util.EnumMap;
import java.util.function.Supplier;

import org.graalvm.collections.UnmodifiableEconomicMap;
import org.graalvm.word.LocationIdentity;

import jdk.graal.compiler.api.replacements.Fold.InjectedParameter;
import jdk.graal.compiler.api.replacements.Snippet;
import jdk.graal.compiler.api.replacements.Snippet.ConstantParameter;
import jdk.graal.compiler.api.replacements.Snippet.NonNullParameter;
import jdk.graal.compiler.debug.Assertions;
import jdk.graal.compiler.debug.GraalError;
import jdk.graal.compiler.graph.Node;
import jdk.graal.compiler.nodes.CallTargetNode;
import jdk.graal.compiler.nodes.DeoptimizeNode;
import jdk.graal.compiler.nodes.FrameState;
import jdk.graal.compiler.nodes.GraphState.GuardsStage;
import jdk.graal.compiler.nodes.InvokeNode;
import jdk.graal.compiler.nodes.InvokeWithExceptionNode;
import jdk.graal.compiler.nodes.NamedLocationIdentity;
import jdk.graal.compiler.nodes.NodeView;
import jdk.graal.compiler.nodes.PiNode;
import jdk.graal.compiler.nodes.SnippetAnchorNode;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.UnreachableNode;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.extended.GuardedUnsafeLoadNode;
import jdk.graal.compiler.nodes.extended.GuardingNode;
import jdk.graal.compiler.nodes.extended.RawStoreNode;
import jdk.graal.compiler.nodes.graphbuilderconf.GraphBuilderContext;
import jdk.graal.compiler.nodes.graphbuilderconf.InvocationPlugin;
import jdk.graal.compiler.nodes.graphbuilderconf.InvocationPlugins;
import jdk.graal.compiler.nodes.java.ArrayLengthNode;
import jdk.graal.compiler.nodes.spi.CoreProviders;
import jdk.graal.compiler.nodes.spi.LoweringTool;
import jdk.graal.compiler.nodes.type.StampTool;
import jdk.graal.compiler.nodes.util.GraphUtil;
import jdk.graal.compiler.options.OptionValues;
import jdk.graal.compiler.phases.PhaseSuite;
import jdk.graal.compiler.phases.util.Providers;
import jdk.graal.compiler.replacements.ReplacementsUtil;
import jdk.graal.compiler.replacements.SnippetCounter;
import jdk.graal.compiler.replacements.SnippetIntegerHistogram;
import jdk.graal.compiler.replacements.SnippetTemplate;
import jdk.graal.compiler.replacements.Snippets;
import jdk.graal.compiler.replacements.nodes.BasicArrayCopyNode;
import jdk.vm.ci.code.BytecodeFrame;
import jdk.vm.ci.meta.DeoptimizationAction;
import jdk.vm.ci.meta.DeoptimizationReason;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.MetaAccessProvider;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import jdk.vm.ci.meta.ResolvedJavaType;

public abstract class ArrayFillSnippets implements Snippets {

    public static void registerSystemArraycopyPlugin(InvocationPlugins.Registration r) {
        registerSystemArraycopyPlugin(r, false);
    }

    public static void registerSystemArraycopyPlugin(InvocationPlugins.Registration r, boolean forceAnyLocation) {
        r.register(new InvocationPlugin("fill", byte[].class, byte.class) {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode src, ValueNode srcPos, ValueNode dst, ValueNode dstPos, ValueNode length) {
                ValueNode nonNullSrc = b.nullCheckedValue(src);
                ValueNode nonNullDst = b.nullCheckedValue(dst);
                b.add(new ArrayCopyNode(b.bci(), nonNullSrc, srcPos, nonNullDst, dstPos, length, forceAnyLocation));
                return true;
            }
        });
    }

    /**
     * Snippet that performs a stub call for an {@linkplain BasicArrayCopyNode#isExact() exact}
     * array copy.
     */
    @Snippet
    public void arraycopyExactStubCallSnippet(@NonNullParameter Object src, int srcPos, @NonNullParameter Object dest, int destPos, int length,
                    @ConstantParameter JavaKind elementKind, @ConstantParameter LocationIdentity locationIdentity) {
        ArrayCopyCallNode.arraycopy(src, srcPos, dest, destPos, length, elementKind, locationIdentity);
    }

    /**
     * Returns a {@link Supplier} that builds a phase suite with extra phases to run on the snippet
     * in the mid tier. Extension point for subclasses, used to implement
     * {@link Templates#createMidTierPreLoweringPhases()}.
     *
     * @return a valid {@link Supplier} that returns either a valid {@link PhaseSuite} or
     *         {@code null} if no extra lowering phases are needed
     */
    protected Supplier<PhaseSuite<CoreProviders>> midTierPreLoweringPhaseFactory() {
        return () -> null;
    }

    public static class Templates extends SnippetTemplate.AbstractTemplates {
        private final SnippetTemplate.SnippetInfo arraycopyExactStubCallSnippet;

        private final Supplier<PhaseSuite<CoreProviders>> midTierPreLoweringPhaseFactory;

        @SuppressWarnings("this-escape")
        public Templates(ArrayFillSnippets receiver, OptionValues options, Providers providers) {
            super(options, providers);

            arraycopyExactStubCallSnippet = snippet(providers, receiver, "arraycopyExactStubCallSnippet");
        }

        protected SnippetTemplate.SnippetInfo snippet(Providers providers, ArrayFillSnippets receiver, String methodName) {
            SnippetTemplate.SnippetInfo info = snippet(providers,
                            ArrayFillSnippets.class,
                            methodName,
                            originalArraycopy(providers.getMetaAccess()),
                            receiver,
                            LocationIdentity.any());
            return info;
        }

        public void lower(ArrayCopyNode arraycopy, LoweringTool tool) {
            JavaKind elementKind = BasicArrayCopyNode.selectComponentKind(arraycopy);

            SnippetTemplate.Arguments args = new SnippetTemplate.Arguments(arraycopyExactStubCallSnippet, arraycopy.graph().getGuardsStage(), tool.getLoweringStage());
            args.add("src", arraycopy.getSource());
            args.add("srcPos", arraycopy.getSourcePosition());
            args.add("dest", arraycopy.getDestination());
            args.add("destPos", arraycopy.getDestinationPosition());
            args.add("length", arraycopy.getLength());

            Object locationIdentity = arraycopy.killsAnyLocation() ? LocationIdentity.any() : NamedLocationIdentity.getArrayLocation(elementKind);

            args.add("elementKind", elementKind);
            args.add("locationIdentity", locationIdentity);

            instantiate(tool, args, arraycopy);
        }

        @Override
        protected PhaseSuite<CoreProviders> createMidTierPreLoweringPhases() {
            return midTierPreLoweringPhaseFactory.get();
        }
    }
}
