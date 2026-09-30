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

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ListIterator;

import org.graalvm.collections.EconomicMap;
import org.graalvm.nativeimage.ImageSingletons;

import com.oracle.svm.core.feature.InternalFeature;
import com.oracle.svm.core.util.UserError;
import com.oracle.svm.hosted.FeatureImpl;
import com.oracle.svm.hosted.meta.HostedMethod;
import com.oracle.svm.hosted.meta.HostedUniverse;
import com.oracle.svm.hosted.pgo.IprofConditionalParser.IprofFormatException;
import com.oracle.svm.hosted.pgo.IprofConditionalParser.ParsedProfile;
import com.oracle.svm.hosted.pgo.phases.PGOApplyProfilesPhase;
import com.oracle.svm.hosted.pgo.profiles.ConditionalProfileContextResolver;
import com.oracle.svm.hosted.pgo.profiles.ConditionalProfileFilter;
import com.oracle.svm.hosted.pgo.profiles.ConditionalProfileSiteDescriptor.Stage;
import com.oracle.svm.hosted.pgo.profiles.PGOProfilesLookup;
import com.oracle.svm.hosted.pgo.profiles.SamplingHotness;
import com.oracle.svm.hosted.pgo.profiles.SamplingInliningProvider;
import com.oracle.svm.hosted.pgo.profiles.SimpleConditionalProfilesLookup;
import com.oracle.svm.hosted.phases.priorityinline.SubstratePriorityInliningPhase;
import com.oracle.svm.shared.feature.AutomaticallyRegisteredFeature;
import com.oracle.svm.shared.option.APIOption;
import com.oracle.svm.shared.option.HostedOptionKey;

import jdk.graal.compiler.core.common.GraalOptions;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.options.Option;
import jdk.graal.compiler.options.OptionKey;
import jdk.graal.compiler.phases.BasePhase;
import jdk.graal.compiler.phases.PhaseSuite;
import jdk.graal.compiler.phases.common.AbstractInliningPhase;
import jdk.graal.compiler.phases.tiers.HighTierContext;
import jdk.graal.compiler.phases.tiers.Suites;
import jdk.graal.compiler.phases.util.Providers;

/** Wires stage-qualified conditional-only iprof data into hosted Native Image compilation. */
@AutomaticallyRegisteredFeature
public final class PGOConditionalProfilesFeature implements InternalFeature {

    public static final class Options {
        // @formatter:off
        @APIOption(name = "pgo")//
        @Option(help = "Consume conditional branch profiles before root inlining and during priority-inliner expansion.")//
        public static final HostedOptionKey<String> ConditionalProfilesUse = new ProfilePathOption();

        @APIOption(name = "pgo-post-inlining")//
        @Option(help = "Consume conditional branch profiles at the end of hosted HighTier, matching the post-inlining producer stage.")//
        public static final HostedOptionKey<String> ConditionalProfilesPostInliningUse = new ProfilePathOption();

        @Option(help = "When a conditional site's full inlining context has no profile, fall back to the profile of the same branch under a shorter context (outermost callers dropped).")//
        public static final HostedOptionKey<Boolean> PGOContextFallback = new HostedOptionKey<>(true);

        @Option(help = "Ignore matched conditional profiles with fewer than this many recorded successor events; the site keeps its static probability. 0 disables.")//
        public static final HostedOptionKey<Long> PGOConditionalMinEvents = new HostedOptionKey<>(0L);

        @Option(help = "Ignore matched conditional profiles whose dominant successor share is below this value in [0,1]; the site keeps its static probability. 0 disables.")//
        public static final HostedOptionKey<Double> PGOConditionalMinBias = new HostedOptionKey<>(0.0);

        @Option(help = "Expose callCountProfiles to call-count optimization consumers. Disable with -H:-PGOUseCallCounts.")//
        public static final HostedOptionKey<Boolean> PGOUseCallCounts = new HostedOptionKey<>(true);
        // @formatter:on

        private static final class ProfilePathOption extends HostedOptionKey<String> {
            private ProfilePathOption() {
                super("");
            }

            @Override
            protected void onValueUpdate(EconomicMap<OptionKey<?>, Object> values, String oldValue, String newValue) {
                super.onValueUpdate(values, oldValue, newValue);
                if (newValue != null && !newValue.isEmpty()) {
                    GraalOptions.TrackNodeSourcePosition.update(values, true);
                }
            }
        }
    }

    private ParsedProfile parsedEarlyProfile;
    private ParsedProfile parsedPostInliningProfile;
    private SimpleConditionalProfilesLookup earlyLookup;
    private SamplingHotness samplingHotness;
    private SimpleConditionalProfilesLookup postInliningLookup;
    private HostedUniverse hostedUniverse;

    private static String earlyProfilePath() {
        return Options.ConditionalProfilesUse.getValue();
    }

    private static String postInliningProfilePath() {
        return Options.ConditionalProfilesPostInliningUse.getValue();
    }

    private static boolean pathSet(String path) {
        return path != null && !path.isEmpty();
    }

    public static boolean anyProfileEnabled() {
        return pathSet(earlyProfilePath()) || pathSet(postInliningProfilePath());
    }

    @Override
    public boolean isInConfiguration(IsInConfigurationAccess access) {
        return anyProfileEnabled();
    }

    @Override
    public void afterRegistration(AfterRegistrationAccess access) {
        if (pathSet(earlyProfilePath())) {
            parsedEarlyProfile = parseProfile(earlyProfilePath(), "--pgo");
        }
        if (pathSet(postInliningProfilePath())) {
            parsedPostInliningProfile = parseProfile(postInliningProfilePath(), "--pgo-post-inlining");
        }
    }

    private static ParsedProfile parseProfile(String profilePath, String optionName) {
        Path path = Path.of(profilePath);
        if (!Files.isReadable(path)) {
            throw UserError.abort("The iprof file passed to %s is not readable: %s", optionName, path);
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return new IprofConditionalParser().parse(reader);
        } catch (IprofFormatException e) {
            throw UserError.abort("The iprof file passed to %s is malformed: %s (%s)", optionName, path, e.getMessage());
        } catch (IOException e) {
            throw UserError.abort("Could not read the iprof file passed to %s: %s (%s)", optionName, path, e.getMessage());
        }
    }

    @Override
    public void beforeCompilation(BeforeCompilationAccess access) {
        if (!anyProfileEnabled()) {
            return;
        }
        hostedUniverse = ((FeatureImpl.BeforeCompilationAccessImpl) access).getUniverse();

        ConditionalProfileFilter filter = new ConditionalProfileFilter(Options.PGOConditionalMinEvents.getValue(), Options.PGOConditionalMinBias.getValue());
        if (parsedEarlyProfile != null && !ImageSingletons.contains(PGOProfilesLookup.class)) {
            earlyLookup = ConditionalProfileContextResolver.resolve(parsedEarlyProfile, hostedUniverse);
            earlyLookup.setFilter(filter);
            earlyLookup.setContextFallback(Options.PGOContextFallback.getValue());
            earlyLookup.setUseCallCounts(Options.PGOUseCallCounts.getValue());
            ImageSingletons.add(PGOProfilesLookup.class, earlyLookup);
            reportResolution("early", earlyLookup);
            if (earlyLookup.getSampleCounts().isPresent()) {
                samplingHotness = new SamplingHotness(earlyLookup.getSampleCounts().get());
                // Checkstyle: stop
                System.out.printf("[PGO:early] sampling hotness: %d samples over %d sampled methods%n", samplingHotness.totalSamples(), samplingHotness.sampledMethodCount());
                // Checkstyle: resume
            } else if (earlyLookup.profileCategoryRecorded(SimpleConditionalProfilesLookup.CALL_COUNT_PROFILES_CATEGORY)) {
                /* Install the same context-aware provider for call-count-only profiles. */
                samplingHotness = new SamplingHotness(java.util.Map.of());
            }
        }
        if (parsedPostInliningProfile != null) {
            postInliningLookup = ConditionalProfileContextResolver.resolve(parsedPostInliningProfile, hostedUniverse);
            postInliningLookup.setFilter(filter);
            postInliningLookup.setContextFallback(Options.PGOContextFallback.getValue());
            postInliningLookup.setUseCallCounts(Options.PGOUseCallCounts.getValue());
            reportResolution("post-inlining", postInliningLookup);
        }
        parsedEarlyProfile = null;
        parsedPostInliningProfile = null;
    }

    private static void reportResolution(String stage, SimpleConditionalProfilesLookup lookup) {
        // Checkstyle: stop
        System.out.println("[PGO:" + stage + "] " + lookup.diagnostics().summary());
        if (lookup.virtualInvokeDiagnostics() != null && lookup.virtualInvokeDiagnostics().totalEntries() > 0) {
            System.out.println("[PGO:" + stage + "] " + lookup.virtualInvokeDiagnostics().summary());
        }
        if (lookup.samplingDiagnostics() != null && lookup.samplingDiagnostics().totalEntries() > 0) {
            System.out.println("[PGO:" + stage + "] " + lookup.samplingDiagnostics().summary());
        }
        if (lookup.callCountDiagnostics() != null && lookup.callCountDiagnostics().totalEntries() > 0) {
            System.out.println("[PGO:" + stage + "] " + lookup.callCountDiagnostics().summary());
        }
        // Checkstyle: resume
        if (!lookup.profileCategoryRecorded(SimpleConditionalProfilesLookup.CONDITIONAL_PROFILES_CATEGORY)) {
            // Checkstyle: stop
            System.err.printf("[PGO:%s] WARNING: profile contains no applicable conditionalProfiles (%d entries, %d resolved)%n",
                            stage, lookup.diagnostics().totalEntries(), lookup.diagnostics().resolvedEntries());
            // Checkstyle: resume
        }
    }

    @Override
    public void registerGraalPhases(Providers providers, Suites suites, boolean hosted, boolean fallback) {
        if (!hosted || fallback || hostedUniverse == null) {
            return;
        }
        PhaseSuite<HighTierContext> highTier = suites.getHighTier();
        if (samplingHotness != null) {
            installSamplingInliner(highTier);
        }
        if (earlyLookup != null) {
            ApplyConditionalProfilesPhase earlyPhase = new ApplyConditionalProfilesPhase(hostedUniverse, earlyLookup, Stage.ROOT_PRE_INLINE, samplingHotness);
            ListIterator<BasePhase<? super HighTierContext>> inliner = highTier.findPhase(AbstractInliningPhase.class);
            if (inliner != null) {
                inliner.previous();
                inliner.add(earlyPhase);
            } else {
                highTier.prependPhase(earlyPhase);
            }
        }
        if (postInliningLookup != null) {
            /* The post-inlining producer is appended at this same hosted HighTier boundary. */
            highTier.appendPhase(new ApplyConditionalProfilesPhase(hostedUniverse, postInliningLookup, Stage.POST_HIGH_TIER, null));
        }
    }

    /**
     * Replaces the priority inliner with a copy whose inlining provider knows sampled hotness. The
     * copy constructor exists for exactly this purpose; the phase keeps its position in the suite.
     */
    private void installSamplingInliner(PhaseSuite<HighTierContext> highTier) {
        ListIterator<BasePhase<? super HighTierContext>> position = highTier.findPhase(SubstratePriorityInliningPhase.class);
        if (position == null) {
            return;
        }
        SubstratePriorityInliningPhase current = (SubstratePriorityInliningPhase) position.previous();
        position.set(new SubstratePriorityInliningPhase(current, new SamplingInliningProvider(hostedUniverse, samplingHotness, earlyLookup), earlyLookup));
    }

    /** Creates the single-use PGO subphase separately for every compilation graph. */
    private static final class ApplyConditionalProfilesPhase extends BasePhase<HighTierContext> {
        private final HostedUniverse universe;
        private final SimpleConditionalProfilesLookup lookup;
        private final Stage stage;
        private final SamplingHotness hotness;

        private ApplyConditionalProfilesPhase(HostedUniverse universe, SimpleConditionalProfilesLookup lookup, Stage stage, SamplingHotness hotness) {
            this.universe = universe;
            this.lookup = lookup;
            this.stage = stage;
            this.hotness = hotness;
        }

        @Override
        protected void run(StructuredGraph graph, HighTierContext context) {
            if (hotness != null && graph.method() instanceof HostedMethod method) {
                /* Runs before the inliner and duplication, which read the provider from the root graph. */
                graph.setGlobalProfileProvider(hotness.providerFor(method));
            }
            PGOApplyProfilesPhase.createContextInsensitive(universe, lookup, stage).apply(graph, context);
        }
    }

    @Override
    public void afterCompilation(AfterCompilationAccess access) {
        reportApplication("early", earlyLookup);
        reportApplication("post-inlining", postInliningLookup);
        if (samplingHotness != null) {
            // Checkstyle: stop
            System.out.printf("[PGO:early] sampling hotness: %d hot compilation roots, %d cold%n", samplingHotness.hotCompilationUnits(), samplingHotness.coldCompilationUnits());
            // Checkstyle: resume
        }
    }

    private static void reportApplication(String stage, SimpleConditionalProfilesLookup lookup) {
        if (lookup == null) {
            return;
        }
        long hits = lookup.hitCount();
        long misses = lookup.missCount();
        long queries = hits + misses;
        double hitRate = queries == 0 ? 0.0 : 100.0 * hits / queries;
        int matchedContexts = lookup.matchedContextCount();
        int availableContexts = lookup.availableContextCount();
        double contextUseRate = availableContexts == 0 ? 0.0 : 100.0 * matchedContexts / availableContexts;
        // Checkstyle: stop
        System.out.printf("[PGO:%s] %d queries, %d hits (%.1f%%), %d misses; contexts: %d/%d used (%.1f%%), %d fully applied, %d partially applied, %d matched-not-applied, %d unused%n",
                        stage, queries, hits, hitRate, misses, matchedContexts, availableContexts, contextUseRate,
                        lookup.fullyAppliedContextCount(), lookup.partiallyAppliedContextCount(), lookup.unappliedMatchedContextCount(), lookup.unusedResolvedContextCount());
        if (lookup.availableVirtualInvokeContextCount() > 0) {
            System.out.printf("[PGO:%s] virtual invokes: %d queries, %d hits, %d misses; contexts: %d/%d used; impossible receivers dropped: %d records, %d events%n", stage,
                            lookup.virtualInvokeHitCount() + lookup.virtualInvokeMissCount(), lookup.virtualInvokeHitCount(), lookup.virtualInvokeMissCount(),
                            lookup.matchedVirtualInvokeContextCount(), lookup.availableVirtualInvokeContextCount(),
                            lookup.impossibleReceiverRecords(), lookup.impossibleReceiverEvents());
        }
        System.out.printf("[PGO:%s] prior comparison by events (agree/flip(injected-prior flips)): %s%n", stage, lookup.priorComparisonSummary());
        if (lookup.filter().isActive()) {
            System.out.printf("[PGO:%s] usefulness filter minEvents=%d minBias=%.2f withheld %d distinct sites: %d queries too few events, %d queries too even%n",
                            stage, lookup.filter().minEvents(), lookup.filter().minBias(), lookup.filteredContextCount(), lookup.filteredFewEventsCount(), lookup.filteredEvenCount());
        }
        if (lookup.usesPreciseProfiles()) {
            SimpleConditionalProfilesLookup.PreciseMissDiagnostics precise = lookup.preciseMissDiagnostics();
            System.out.printf("[PGO:%s] precise misses by first mismatch: context=%d, stage=%d, successors=%d, condition-kind=%d, occurrence=%d; matched fingerprint drift=%d, unambiguous legacy fallback=%d, shortened-context fallback=%d (avg %.1f frames dropped)%n",
                            stage, precise.context(), precise.stage(), precise.successors(), precise.conditionKind(), precise.occurrence(), precise.fingerprintDrift(), precise.unambiguousFallback(),
                            lookup.contextFallbackCount(), lookup.contextFallbackCount() == 0 ? 0.0 : (double) lookup.contextFallbackDroppedFrames() / lookup.contextFallbackCount());
        }
        // Checkstyle: resume
        if (PGOApplyProfilesPhase.Options.PGOPrintProfileQualityDetails.getValue()) {
            System.out.println("[PGO:" + stage + "] resolved context samples: " + lookup.contextKeySamples(10));
        }
    }
}
