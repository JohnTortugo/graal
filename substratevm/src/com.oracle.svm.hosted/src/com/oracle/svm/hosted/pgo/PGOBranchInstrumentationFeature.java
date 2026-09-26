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

import java.util.ListIterator;
import java.util.Map;

import org.graalvm.collections.EconomicMap;

import com.oracle.svm.core.feature.InternalFeature;
import com.oracle.svm.core.graal.meta.RuntimeConfiguration;
import com.oracle.svm.core.graal.snippets.NodeLoweringProvider;
import com.oracle.svm.core.pgo.BranchProfileRecorder;
import com.oracle.svm.core.pgo.BranchProfileThreadCounters;
import com.oracle.svm.core.thread.ThreadListenerSupport;
import com.oracle.svm.core.util.UserError;
import com.oracle.svm.guest.staging.jdk.RuntimeSupport;
import com.oracle.svm.hosted.pgo.profiles.ConditionalProfileSiteDescriptor.Stage;
import com.oracle.svm.shared.feature.AutomaticallyRegisteredFeature;
import com.oracle.svm.shared.option.APIOption;
import com.oracle.svm.shared.option.HostedOptionKey;

import jdk.graal.compiler.core.common.GraalOptions;
import jdk.graal.compiler.graph.Node;
import jdk.graal.compiler.graph.NodeSourcePosition;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.options.Option;
import jdk.graal.compiler.options.OptionKey;
import jdk.graal.compiler.options.OptionValues;
import jdk.graal.compiler.phases.BasePhase;
import jdk.graal.compiler.phases.PhaseSuite;
import jdk.graal.compiler.phases.common.AbstractInliningPhase;
import jdk.graal.compiler.phases.tiers.HighTierContext;
import jdk.graal.compiler.phases.tiers.Suites;
import jdk.graal.compiler.phases.util.Providers;

/** Enables CE Native Image conditional-branch instrumentation. */
@AutomaticallyRegisteredFeature
public final class PGOBranchInstrumentationFeature implements InternalFeature {

    public static final class Options {
        // @formatter:off
        @APIOption(name = "pgo-instrument")//
        @Option(help = "Instrument profile-relevant conditional branches after hosted HighTier inlining.")//
        public static final HostedOptionKey<Boolean> BranchProfilesInstrument = new SourcePositionOption();

        @APIOption(name = "pgo-instrument-aligned")//
        @Option(help = "Instrument conditional branches at the root and priority-inliner expansion points where CE consumes profiles.")//
        public static final HostedOptionKey<Boolean> BranchProfilesInstrumentAligned = new SourcePositionOption();
        // @formatter:on

        private static final class SourcePositionOption extends HostedOptionKey<Boolean> {
            private SourcePositionOption() {
                super(false);
            }

            @Override
            protected void onValueUpdate(EconomicMap<OptionKey<?>, Object> values, Boolean oldValue, Boolean newValue) {
                super.onValueUpdate(values, oldValue, newValue);
                if (Boolean.TRUE.equals(newValue)) {
                    GraalOptions.TrackNodeSourcePosition.update(values, true);
                }
            }
        }
    }

    private static boolean postInliningEnabled() {
        return Options.BranchProfilesInstrument.getValue();
    }

    public static boolean alignedEnabled() {
        return Options.BranchProfilesInstrumentAligned.getValue();
    }

    private static boolean enabled() {
        return postInliningEnabled() || alignedEnabled();
    }

    /** Called for every decoded priority-inliner cutoff graph under its exact caller context. */
    public static void instrumentExpandedGraph(StructuredGraph graph, NodeSourcePosition inliningContext) {
        if (alignedEnabled()) {
            BranchProfileInstrumentationPhase.instrumentGraph(graph, Stage.INLINE_EXPANSION, inliningContext);
        }
    }

    @Override
    public boolean isInConfiguration(IsInConfigurationAccess access) {
        return enabled();
    }

    @Override
    public void afterRegistration(AfterRegistrationAccess access) {
        if (!enabled()) {
            return;
        }
        if (postInliningEnabled() && alignedEnabled()) {
            throw UserError.abort("--pgo-instrument and --pgo-instrument-aligned are mutually exclusive");
        }
        if (PGOConditionalProfilesFeature.anyProfileEnabled()) {
            throw UserError.abort("Profile instrumentation and profile consumption cannot be used in the same image build");
        }
        RuntimeSupport.getRuntimeSupport().addTearDownHook(BranchProfileRecorder.getTeardownHook());
        ThreadListenerSupport.get().register(BranchProfileThreadCounters.create());
    }

    @Override
    public void registerLowerings(RuntimeConfiguration runtimeConfig, OptionValues options, Providers providers,
                    Map<Class<? extends Node>, NodeLoweringProvider<?>> lowerings, boolean hosted) {
        if (hosted && enabled()) {
            providers.getReplacements().registerSnippetTemplateCache(new BranchProfileCounterNode.Templates(options, providers));
        }
    }

    @Override
    public void registerGraalPhases(Providers providers, Suites suites, boolean hosted, boolean fallback) {
        if (!hosted || fallback || !enabled()) {
            return;
        }
        PhaseSuite<HighTierContext> highTier = suites.getHighTier();
        Stage stage = alignedEnabled() ? Stage.ROOT_PRE_INLINE : Stage.POST_HIGH_TIER;
        BranchProfileInstrumentationPhase phase = new BranchProfileInstrumentationPhase(stage, null);
        if (alignedEnabled()) {
            ListIterator<BasePhase<? super HighTierContext>> inliner = highTier.findPhase(AbstractInliningPhase.class);
            if (inliner != null) {
                inliner.previous();
                inliner.add(phase);
            } else {
                highTier.prependPhase(phase);
            }
        } else {
            /* Preserve the milestone-2 post-inlining producer as the default baseline. */
            highTier.appendPhase(phase);
        }
    }

    @Override
    public void afterCompilation(AfterCompilationAccess access) {
        if (!enabled()) {
            return;
        }
        BranchProfileRecorder.sealRegistry();
        // Checkstyle: stop
        System.out.printf("[PGO] branch instrumentation (%s): %d IfNodes instrumented, %d skipped without source positions%n",
                        alignedEnabled() ? "consumer-aligned" : "post-inlining",
                        BranchProfileInstrumentationPhase.instrumentedBranches(), BranchProfileInstrumentationPhase.skippedBranches());
        // Checkstyle: resume
    }
}
