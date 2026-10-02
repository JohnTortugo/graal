/*
 * Copyright (c) 2020, 2020, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.hosted.meta;

import java.util.function.BooleanSupplier;

import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;

import com.oracle.graal.pointsto.api.PointstoOptions;
import com.oracle.graal.pointsto.heap.ImageHeapConstant;
import com.oracle.graal.pointsto.infrastructure.UniverseMetaAccess;
import com.oracle.graal.pointsto.meta.AnalysisField;
import com.oracle.svm.core.SubstrateOptions;
import com.oracle.svm.core.graal.code.CGlobalDataBasePointer;
import com.oracle.svm.core.imagelayer.ImageLayerBuildingSupport;
import com.oracle.svm.core.meta.MethodRef;
import com.oracle.svm.hosted.SVMHost;
import com.oracle.svm.hosted.ameta.FieldValueInterceptionSupport;
import com.oracle.svm.shared.BuildPhaseProvider;
import com.oracle.svm.util.GuestAnnotationAccess;

import jdk.graal.compiler.core.common.spi.JavaConstantFieldProvider;
import jdk.graal.compiler.nodes.spi.CanonicalizerTool;
import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.MetaAccessProvider;
import jdk.vm.ci.meta.ResolvedJavaField;

@Platforms(Platform.HOSTED_ONLY.class)
public abstract class SharedConstantFieldProvider extends JavaConstantFieldProvider {

    protected final UniverseMetaAccess metaAccess;
    protected final SVMHost hostVM;
    protected final FieldValueInterceptionSupport fieldValueInterceptionSupport = FieldValueInterceptionSupport.singleton();

    private static final BooleanSupplier AFTER_ANALYSIS = new BuildPhaseProvider.AfterAnalysis();

    public SharedConstantFieldProvider(MetaAccessProvider metaAccess, SVMHost hostVM) {
        super(metaAccess);
        this.metaAccess = (UniverseMetaAccess) metaAccess;
        this.hostVM = hostVM;
    }

    protected abstract AnalysisField asAnalysisField(ResolvedJavaField field);

    @Override
    public boolean isFinalField(ResolvedJavaField field, ConstantFieldTool<?> tool) {
        return super.isFinalField(field, tool) && allowConstantFolding(field, tool);
    }

    @Override
    public boolean isStableField(ResolvedJavaField field, ConstantFieldTool<?> tool) {
        boolean stable;
        /*
         * GR-46030: JVMCI does not provide access yet to the proper "stable" flag that also takes
         * the class loader of the using class into account. So we look at the annotation directly
         * for now.
         */
        if (GuestAnnotationAccess.isAnnotationPresent(field, jdk.internal.vm.annotation.Stable.class)) {
            stable = true;
        } else {
            stable = super.isStableField(field, tool);
        }
        return stable && allowConstantFolding(field, tool);
    }

    private boolean allowConstantFolding(ResolvedJavaField field, ConstantFieldTool<?> tool) {
        var aField = asAnalysisField(field);

        if (aField.preventConstantFolding()) {
            return false;
        }

        /*
         * During compiler optimizations, it is possible to see field loads with a constant receiver
         * of a wrong type that might not even be an ImageHeapConstant. Also, we need to ensure that
         * the ImageHeapConstant allows constant folding of its fields.
         */
        if (!field.isStatic() && (!(tool.getReceiver() instanceof ImageHeapConstant receiver) || !receiver.allowConstantFolding())) {
            return false;
        }

        /*
         * This code should run as late as possible, because it has side effects. So we only do it
         * after we have already checked that the field is `final` or `stable`. It marks the
         * declaring class of the field as reachable, in order to trigger computation of automatic
         * substitutions. It also ensures that the class is initialized (if the class is registered
         * for initialization at build time) before any constant folding of static fields is
         * attempted.
         */
        if (!fieldValueInterceptionSupport.isValueAvailable(aField, tool == null ? null : tool.getReceiver())) {
            return false;
        }

        if (field.isStatic() && !isClassInitialized(field) && !fieldValueInterceptionSupport.hasFieldValueTransformer(aField)) {
            /*
             * The class is not initialized at image build time, so we do not have a static field
             * value to constant fold. Note that a FieldValueTransformer is able to provide a field
             * value also for non-initialized classes.
             */
            return false;
        }
        return hostVM.allowConstantFolding(field);
    }

    /**
     * Closed-world trusted finals. A final instance field whose only registered writes initialize
     * a freshly constructed object (stores in instance initializers of its declaring class, or
     * field values of allocations materialized by escape analysis) cannot change once its holder
     * has been constructed: the verifier forbids other bytecode writes, and every other write path
     * (Unsafe, reflection, JNI, Feature API registrations) is recorded as a write of unknown
     * position during analysis. The compiler uses this to keep such loads alive across calls and
     * to float them to the earliest point of the graph. Only valid after analysis, only for the
     * points-to analysis (which records every bytecode field store), and disabled for layered
     * images where another layer may write the field.
     * <p>
     * Like the trusted finals of HotSpot this assumes that an object passed as an argument is fully
     * constructed; a constructor that publishes {@code this} to another thread and then writes a
     * final field could still be observed with the old value by that thread.
     */
    @Override
    public boolean isTrustedFinal(CanonicalizerTool tool, ResolvedJavaField field) {
        if (!SubstrateOptions.TrustFinalInstanceFields.getValue() || !AFTER_ANALYSIS.getAsBoolean() || ImageLayerBuildingSupport.buildingImageLayer() ||
                        PointstoOptions.UseExperimentalReachabilityAnalysis.getValue(hostVM.options())) {
            return false;
        }
        if (!field.isFinal() || field.isStatic()) {
            return false;
        }
        AnalysisField aField = asAnalysisField(field);
        return !aField.isUnsafeAccessed() && aField.isWrittenOnlyByInitialization();
    }

    protected boolean isClassInitialized(ResolvedJavaField field) {
        return field.getDeclaringClass().isInitialized();
    }

    @Override
    protected boolean isFinalFieldValueConstant(ResolvedJavaField field, JavaConstant value, ConstantFieldTool<?> tool) {
        if (value.getJavaKind() == JavaKind.Object && (metaAccess.isInstanceOf(value, MethodRef.class) || metaAccess.isInstanceOf(value, CGlobalDataBasePointer.class))) {
            /*
             * Prevent constant folding of placeholder objects for patched words (such as relocated
             * pointers). These are "hosted" types and so cannot be present in compiler graphs.
             */
            return false;
        }
        return super.isFinalFieldValueConstant(field, value, tool);
    }
}
