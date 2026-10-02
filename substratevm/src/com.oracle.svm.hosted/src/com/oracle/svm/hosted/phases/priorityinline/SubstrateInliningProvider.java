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
package com.oracle.svm.hosted.phases.priorityinline;

import static com.oracle.svm.core.SubstrateOptions.UseMethodChecks;
import static jdk.graal.compiler.phases.common.priorityinline.PriorityInliningPhase.Options.MaxPolymorphicDispatches;

import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import org.graalvm.nativeimage.Platform;

import com.oracle.svm.core.SubstrateOptions;
import com.oracle.svm.jvmci.shared.code.SubstrateCallingConventionType;
import com.oracle.svm.hosted.cai.PrefixTree;
import com.oracle.svm.hosted.meta.HostedMethod;
import com.oracle.svm.hosted.meta.HostedUniverse;
import com.oracle.svm.hosted.pgo.phases.PGOApplyProfilesPhase;
import com.oracle.svm.hosted.pgo.profiles.PGOProfilesLookup;

import jdk.graal.compiler.core.common.type.StampPair;
import jdk.graal.compiler.graph.NodeSourcePosition;
import jdk.graal.compiler.nodes.CallTargetNode;
import jdk.graal.compiler.nodes.DirectCallTargetNode;
import jdk.graal.compiler.nodes.Invoke;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.options.OptionValues;
import jdk.graal.compiler.phases.common.priorityinline.DefaultInliningProvider;
import jdk.graal.compiler.phases.common.priorityinline.PolicyFactory;
import jdk.graal.compiler.phases.common.priorityinline.nodes.CallTreeNode;
import jdk.graal.compiler.phases.common.priorityinline.nodes.SubgraphNode;
import jdk.vm.ci.meta.JavaMethodProfile;
import jdk.vm.ci.meta.JavaType;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import jdk.vm.ci.meta.ResolvedJavaType;

public class SubstrateInliningProvider extends DefaultInliningProvider {

    private final HostedUniverse universe;
    private final Function<HostedMethod, PrefixTree.Cursor> methodContextProvider;

    public SubstrateInliningProvider(HostedUniverse universe, Function<HostedMethod, PrefixTree.Cursor> methodContextProvider) {
        Objects.requireNonNull(universe);
        Objects.requireNonNull(methodContextProvider);
        this.universe = universe;
        this.methodContextProvider = methodContextProvider;
    }

    @Override
    public PolicyFactory policy(OptionValues options) {
        return new SubstratePolicyFactory();
    }

    public PGOApplyProfilesPhase createPGOApplyProfilesPhase(ResolvedJavaMethod compilationRoot, NodeSourcePosition nodeSourcePosition, ResolvedJavaMethod callee,
                    NodeSourcePosition methodContext) {
        PrefixTree.Cursor compilationRootContext = methodContextProvider.apply((HostedMethod) compilationRoot);
        PrefixTree.Cursor calleeContext = compilationRootContext.findForMethod(nodeSourcePosition, callee);
        return PGOApplyProfilesPhase.createForExpandingHotCutoffs(methodContext, universe, calleeContext, PGOProfilesLookup.singleton());
    }

    @Override
    public ResolvedJavaMethod methodForDevirtualizationCheck(ResolvedJavaMethod originalTargetMethod, ResolvedJavaMethod targetMethod, ResolvedJavaMethod concreteMethod,
                    ResolvedJavaType receiverType) {
        return originalTargetMethod;
    }

    @SuppressWarnings("unused")
    @Override
    public boolean isMethodForDevirtualizationInTable(ResolvedJavaMethod originalTargetMethod, ResolvedJavaMethod targetMethod, ResolvedJavaMethod concreteMethod, ResolvedJavaType receiverType) {
        return originalTargetMethod.isInVirtualMethodTable(receiverType);
    }

    @Override
    public boolean canInlineUninitialized() {
        return true;
    }

    @Override
    public DirectCallTargetNode createDirectCallTarget(ValueNode[] arguments, StampPair returnStamp, JavaType[] signature, ResolvedJavaMethod dispatchedMethod, CallTargetNode.InvokeKind invokeKind) {
        SubstrateCallingConventionType substrateCallingConventionType = ((HostedMethod) dispatchedMethod).getCallingConventionKind().toType(true);
        return new DirectCallTargetNode(arguments, returnStamp, signature, dispatchedMethod, substrateCallingConventionType, invokeKind);
    }

    @Override
    public int getMaxPolymorphicDispatches(OptionValues options) {
        if (MaxPolymorphicDispatches.hasBeenSet(options)) {
            return MaxPolymorphicDispatches.getValue(options);
        }
        // Default for SVM
        return 3;
    }

    @Override
    public boolean useMethodChecks(OptionValues options) {
        final boolean useLLVMBackend = SubstrateOptions.useLLVMBackend();
        final boolean darwinShared = Platform.includedIn(Platform.DARWIN.class) && SubstrateOptions.SharedLibrary.getValue();
        return UseMethodChecks.getValue(options) && !useLLVMBackend && !darwinShared;
    }

    @Override
    public boolean areDeoptsAllowed() {
        return false;
    }

    public boolean isCallSiteToHotCaller(HostedMethod compilationRoot, NodeSourcePosition callPosition, ResolvedJavaMethod dispatchedMethod) {
        PrefixTree.Cursor compilationRootContext = methodContextProvider.apply(compilationRoot);
        if (compilationRootContext == null) {
            return false;
        }
        PrefixTree.Cursor calleeCursor = compilationRootContext.findForMethod(callPosition, dispatchedMethod);
        return calleeCursor != null;
    }

    public record SamplingContext(double rootRelativeHotness, long samples) {
        static final SamplingContext COLD = new SamplingContext(0.0, 0);
    }

    public SamplingContext compilationRootSamplingContext(HostedMethod compilationRoot, NodeSourcePosition callPosition, ResolvedJavaMethod dispatchedMethod) {
        PrefixTree.Cursor compilationRootContext = methodContextProvider.apply(compilationRoot);
        if (compilationRootContext == null) {
            return SamplingContext.COLD;
        }
        PrefixTree.Cursor calleeContext = compilationRootContext.findForMethod(callPosition, dispatchedMethod);
        if (calleeContext == null) {
            return SamplingContext.COLD;
        }
        long rootSamples = compilationRootContext.getSubtreeCount();
        long calleeSamples = calleeContext.getSubtreeCount();
        return createSamplingContext(rootSamples, calleeSamples);
    }

    static SamplingContext createSamplingContext(long rootSamples, long calleeSamples) {
        if (rootSamples <= 0 || calleeSamples <= 0) {
            return SamplingContext.COLD;
        }
        return new SamplingContext((double) calleeSamples / rootSamples, calleeSamples);
    }

    public double compilationRootRelativeHotness(HostedMethod compilationRoot, NodeSourcePosition callPosition, ResolvedJavaMethod dispatchedMethod) {
        return compilationRootSamplingContext(compilationRoot, callPosition, dispatchedMethod).rootRelativeHotness();
    }

    public JavaMethodProfile samplingMethodProfiles(Map<CallTreeNode, PrefixTree.Cursor> nodeContextMap, HostedMethod root, CallTreeNode caller, CallTargetNode callee) {
        PrefixTree.Cursor callerContext = nodeContextMap.computeIfAbsent(caller, _ -> {
            PrefixTree.Cursor compilationRootContext = methodContextProvider.apply(root);
            if (compilationRootContext == null) {
                return null;
            }
            SubgraphNode subgraphCaller = (SubgraphNode) caller;
            if (caller.isRoot()) {
                return compilationRootContext;
            }
            return compilationRootContext.findForMethod(caller.compilationRootPosition(), subgraphCaller.getReadonlySubgraph().method());
        });
        if (callerContext == null) {
            return null;
        }
        JavaMethodProfile javaMethodProfile = callerContext.profileFor(universe, callee.getNodeSourcePosition());
        return PGOApplyProfilesPhase.validateProfile(callee, javaMethodProfile);
    }

    public JavaMethodProfile samplingMethodProfiles(HostedMethod compilationRoot, Invoke calleeInvoke) {
        PrefixTree.Cursor compilationRootContext = methodContextProvider.apply(compilationRoot);
        if (compilationRootContext == null) {
            return null;
        }
        JavaMethodProfile javaMethodProfile = compilationRootContext.profileFor(universe, calleeInvoke.asNode().getNodeSourcePosition());
        return PGOApplyProfilesPhase.validateProfile(calleeInvoke.callTarget(), javaMethodProfile);

    }

    /// Determines whether sampling profiles are applied while expanding.
    ///
    /// @param options the options being used during expanding
    protected boolean shouldApplyProfilesWhileExpanding(OptionValues options) {
        return false;
    }

    /// Gets the hotness bonus applied while expanding.
    ///
    /// @param options the options being used during expanding
    protected int hotBonusWhileExpanding(OptionValues options) {
        return 0;
    }

    /// Gets the hotness bonus applied while inlining.
    ///
    /// @param options the options being used during inlining
    protected int hotBonusWhileInlining(OptionValues options) {
        return 0;
    }

    /// Gets the additional multiplier for a selected, sufficiently sampled hot context.
    protected int selectedContextBonusWhileInlining(@SuppressWarnings("unused") OptionValues options, @SuppressWarnings("unused") double hotness,
                    @SuppressWarnings("unused") long samples) {
        return 0;
    }

    /// Gets the additional priority for a selected, sufficiently sampled hot context.
    protected int selectedContextBonusWhileExpanding(@SuppressWarnings("unused") OptionValues options, @SuppressWarnings("unused") double hotness,
                    @SuppressWarnings("unused") long samples) {
        return 0;
    }

    /// Gets the largest callee bytecode size eligible for hot-leaf absorption; 0 disables it.
    ///
    /// @param options the options being used during expanding
    protected int hotLeafMaxCodeSize(@SuppressWarnings("unused") OptionValues options) {
        return 0;
    }

    /// Gets the minimum root-relative hotness a call edge needs for hot-leaf absorption.
    ///
    /// @param options the options being used during expanding
    protected double hotLeafMinHotness(@SuppressWarnings("unused") OptionValues options) {
        return 1.0;
    }

    /// Gets the minimum profile-corrected call-site frequency for hot-leaf absorption.
    ///
    /// @param options the options being used during expanding
    protected double hotLeafMinFrequency(@SuppressWarnings("unused") OptionValues options) {
        return Double.MAX_VALUE;
    }

    /// Whether hot-leaf absorption applies only to compilation roots present in the sampling profile.
    ///
    /// @param options the options being used during expanding
    protected boolean hotLeafRequiresSampledRoot(@SuppressWarnings("unused") OptionValues options) {
        return true;
    }

    /// Whether hot-leaf absorption applies only to JDK library callees invoked from application code.
    ///
    /// @param options the options being used during expanding
    protected boolean hotLeafLibraryOnly(@SuppressWarnings("unused") OptionValues options) {
        return false;
    }

    /// Gets how many calls must share a value before absorbing one of them; 1 disables the test.
    ///
    /// @param options the options being used during expanding
    protected int hotLeafMinSharedCalls(@SuppressWarnings("unused") OptionValues options) {
        return 1;
    }

    /// Gets the benefit multiplier for every call in a compilation root that the sampling profile
    /// shows to be hot; 1 disables the boost.
    ///
    /// @param options the options being used during inlining
    public double hotRootInliningBoost(@SuppressWarnings("unused") OptionValues options) {
        return 1.0;
    }

    /// Gets the inclusive sample share a compilation root needs to count as hot.
    ///
    /// @param options the options being used during inlining
    public double hotRootMinInclusiveShare(@SuppressWarnings("unused") OptionValues options) {
        return Double.MAX_VALUE;
    }

    /// Gets the fraction in [0, 1] of all samples with {@code root} on the stack; 0 without a profile.
    public double inclusiveTimeShare(@SuppressWarnings("unused") HostedMethod root) {
        return 0.0;
    }

}
