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
import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;

import org.graalvm.collections.EconomicMap;
import org.graalvm.nativeimage.ImageSingletons;

import com.oracle.svm.core.feature.InternalFeature;
import com.oracle.svm.core.util.UserError;
import com.oracle.svm.hosted.FeatureImpl;
import com.oracle.svm.hosted.meta.HostedMethod;
import com.oracle.svm.hosted.meta.HostedUniverse;
import com.oracle.svm.hosted.pgo.IprofParser.IprofFormatException;
import com.oracle.svm.hosted.pgo.IprofParser.ParsedProfile;
import com.oracle.svm.hosted.pgo.phases.PGOApplyProfilesPhase;
import com.oracle.svm.hosted.pgo.profiles.IprofProfileResolver;
import com.oracle.svm.hosted.pgo.profiles.PGOProfilesLookup;
import com.oracle.svm.hosted.pgo.profiles.SamplingHotness;
import com.oracle.svm.hosted.pgo.profiles.SamplingInliningProvider;
import com.oracle.svm.hosted.pgo.profiles.IprofProfilesLookup;
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

/**
 * Optimizes the image with a profile recorded by a {@code --pgo-instrument} image ({@code --pgo}).
 * <p>
 * The feature parses the iprof file or files, resolves their branch, switch, receiver, call-count
 * and stack-sample entries against the hosted universe, and applies them to every compilation root
 * before the inliner runs and to every callee the priority inliner expands.
 */
@AutomaticallyRegisteredFeature
public final class PGOProfileUseFeature implements InternalFeature {

    public static final class Options {
        // @formatter:off
        @APIOption(name = "pgo")//
        @Option(help = "Optimize the image with profiles recorded by a --pgo-instrument image: one .iprof file, a directory of .iprof files, " +
                       "or a comma-separated list of files, each with an optional :weight. " +
                       "Raises the default optimization level -O2 to -O3; an -O given after this option wins.")//
        public static final HostedOptionKey<String> PGOProfileUse = new ProfilePathOption();

        @Option(help = "When a conditional site's full inlining context has no profile, fall back to the profile of the same branch under a shorter context (outermost callers dropped).")//
        public static final HostedOptionKey<Boolean> PGOContextFallback = new HostedOptionKey<>(true);

        @Option(help = "When an indirect call's full inlining context has no receiver profile, fall back to the receiver profile of the same call under a shorter context (outermost callers dropped).")//
        public static final HostedOptionKey<Boolean> PGOReceiverContextFallback = new HostedOptionKey<>(true);

        @Option(help = "Use the profile's call counts to rate how hot a call edge is relative to its compilation root. Disable with -H:-PGOUseCallCounts.")//
        public static final HostedOptionKey<Boolean> PGOUseCallCounts = new HostedOptionKey<>(true);

        @Option(help = "Use the profile's stack samples: sampled hotness of roots and contexts, sampled callee profiles and self time. " +
                       "Disable with -H:-PGOUseStackSamples: the build then behaves as if the profile had no stack samples.")//
        public static final HostedOptionKey<Boolean> PGOUseStackSamples = new HostedOptionKey<>(true);

        @Option(help = "Collect detailed compiler-action counters for PGO validation. Intended for tests and diagnostics, disabled by default.")//
        public static final HostedOptionKey<Boolean> PGOTestDiagnostics = new HostedOptionKey<>(false);

        @Option(help = "Print detailed statistics on how the profile was resolved and applied. By default one summary line is printed.")//
        public static final HostedOptionKey<Boolean> PGOPrintStatistics = new HostedOptionKey<>(false);
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
                    /* Optimize at the level the profile was recorded at. */
                    PGOUtils.raiseDefaultToO3(values);
                }
            }
        }
    }

    private ParsedProfile parsedProfile;
    private IprofProfilesLookup profilesLookup;
    private SamplingHotness samplingHotness;
    private HostedUniverse hostedUniverse;

    private static String profilePath() {
        return Options.PGOProfileUse.getValue();
    }

    private static boolean pathSet(String path) {
        return path != null && !path.isEmpty();
    }

    public static boolean enabled() {
        return pathSet(profilePath());
    }

    /** Whether to print the detailed statistics; the PGO gate reads them through PGOTestDiagnostics. */
    public static boolean printStatistics() {
        return Options.PGOPrintStatistics.getValue() || Options.PGOTestDiagnostics.getValue();
    }

    @Override
    public boolean isInConfiguration(IsInConfigurationAccess access) {
        return enabled();
    }

    @Override
    public void afterRegistration(AfterRegistrationAccess access) {
        if (pathSet(profilePath())) {
            parsedProfile = parseProfile(profilePath(), "--pgo");
        }
    }

    /**
     * Parses the option value {@code path[:weight][,path[:weight]...]}: one iprof file, or several
     * that are merged with their counts scaled by the given weights (default 1), as
     * {@code llvm-profdata merge --weighted-input} and {@code gcov-tool merge -w} do for training
     * runs of different workloads or lengths.
     */
    private static ParsedProfile parseProfile(String optionValue, String optionName) {
        List<IprofProfileMerger.WeightedProfile> inputs = new ArrayList<>();
        for (String spec : optionValue.split(",")) {
            if (Files.isDirectory(Path.of(spec.strip()))) {
                /*
                 * A directory of training runs, e.g. written with -XX:ProfilesDumpFile=dir/%p.iprof.
                 * The files are merged in name order, so the same directory always gives the same
                 * merged counts and the same image.
                 */
                try (var files = Files.list(Path.of(spec.strip()))) {
                    List<Path> iprofs = files.filter(f -> f.toString().endsWith(".iprof")).sorted().toList();
                    if (iprofs.isEmpty()) {
                        throw UserError.abort("The directory passed to %s contains no .iprof files: %s", optionName, spec);
                    }
                    for (Path iprof : iprofs) {
                        inputs.add(new IprofProfileMerger.WeightedProfile(parseSingleProfile(iprof.toString(), optionName), 1.0));
                    }
                } catch (IOException e) {
                    throw UserError.abort("Could not list the directory passed to %s: %s (%s)", optionName, spec, e.getMessage());
                }
                continue;
            }
            String profilePath = spec;
            double weight = 1.0;
            int separator = spec.lastIndexOf(':');
            if (separator > 0) {
                try {
                    weight = Double.parseDouble(spec.substring(separator + 1));
                    profilePath = spec.substring(0, separator);
                } catch (NumberFormatException e) {
                    /* Not a weight suffix; the whole value is the path. */
                }
            }
            if (!(weight > 0.0)) {
                throw UserError.abort("The profile weight passed to %s must be positive: %s", optionName, spec);
            }
            inputs.add(new IprofProfileMerger.WeightedProfile(parseSingleProfile(profilePath.strip(), optionName), weight));
        }
        try {
            if (inputs.size() > 1) {
                /* Tell the user how alike the training runs are; a low overlap means the merged profile is a compromise. */
                StringBuilder overlaps = new StringBuilder();
                for (int i = 1; i < inputs.size(); i++) {
                    overlaps.append(i > 1 ? ", " : "").append(String.format("%d-%d: %.2f", 0, i, IprofProfileMerger.samplingOverlap(inputs.get(0).profile(), inputs.get(i).profile())));
                }
                // Checkstyle: stop
                System.out.printf("[PGO] merging %d profiles; sampling overlap with the first (1 = same hot methods in the same proportions): %s%n",
                                inputs.size(), overlaps);
                // Checkstyle: resume
            }
            return IprofProfileMerger.merge(inputs);
        } catch (IprofFormatException e) {
            throw UserError.abort("The iprof files passed to %s cannot be merged: %s", optionName, e.getMessage());
        }
    }

    private static ParsedProfile parseSingleProfile(String profilePath, String optionName) {
        Path path = Path.of(profilePath);
        if (!Files.isReadable(path)) {
            throw UserError.abort("The iprof file passed to %s is not readable: %s", optionName, path);
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return new IprofParser().parse(reader);
        } catch (IprofFormatException e) {
            throw UserError.abort("The iprof file passed to %s is malformed: %s (%s)", optionName, path, e.getMessage());
        } catch (IOException e) {
            throw UserError.abort("Could not read the iprof file passed to %s: %s (%s)", optionName, path, e.getMessage());
        }
    }

    @Override
    public void beforeCompilation(BeforeCompilationAccess access) {
        if (!enabled()) {
            return;
        }
        hostedUniverse = ((FeatureImpl.BeforeCompilationAccessImpl) access).getUniverse();

        if (parsedProfile != null && !ImageSingletons.contains(PGOProfilesLookup.class)) {
            profilesLookup = IprofProfileResolver.resolve(parsedProfile, hostedUniverse);
            profilesLookup.setContextFallback(Options.PGOContextFallback.getValue());
            profilesLookup.setReceiverContextFallback(Options.PGOReceiverContextFallback.getValue());
            profilesLookup.setUseCallCounts(Options.PGOUseCallCounts.getValue());
            profilesLookup.setUseStackSamples(Options.PGOUseStackSamples.getValue());
            ImageSingletons.add(PGOProfilesLookup.class, profilesLookup);
            reportResolution(profilesLookup);
            if (profilesLookup.getSampleCounts().isPresent()) {
                samplingHotness = new SamplingHotness(profilesLookup.getSampleCounts().get());
                if (printStatistics()) {
                    // Checkstyle: stop
                    System.out.printf("[PGO] sampling hotness: %d samples over %d sampled methods (%d idle samples excluded)%n",
                                    samplingHotness.totalSamples(), samplingHotness.sampledMethodCount(), samplingHotness.idleSamples());
                    // Checkstyle: resume
                }
            } else if (profilesLookup.profileCategoryRecorded(IprofProfilesLookup.CALL_COUNT_PROFILES_CATEGORY)) {
                /* Install the same context-aware provider for call-count-only profiles. */
                samplingHotness = new SamplingHotness(Map.of());
            }
        }
        parsedProfile = null;
    }

    private static void reportResolution(IprofProfilesLookup lookup) {
        // Checkstyle: stop
        if (printStatistics()) {
            System.out.println("[PGO] " + lookup.conditionalDiagnostics().summary());
            if (lookup.virtualInvokeDiagnostics() != null && lookup.virtualInvokeDiagnostics().totalEntries() > 0) {
                System.out.println("[PGO] " + lookup.virtualInvokeDiagnostics().summary());
            }
            if (lookup.samplingDiagnostics() != null && lookup.samplingDiagnostics().totalEntries() > 0) {
                System.out.println("[PGO] " + lookup.samplingDiagnostics().summary());
            }
            if (lookup.callCountDiagnostics() != null && lookup.callCountDiagnostics().totalEntries() > 0) {
                System.out.println("[PGO] " + lookup.callCountDiagnostics().summary());
            }
        } else {
            /* One line tells whether the profile matches this program; the details are opt-in. */
            System.out.printf("[PGO] profile resolved: %s; details with -H:+PGOPrintStatistics%n", resolutionSummary(lookup));
        }
        if (!Options.PGOUseStackSamples.getValue() && lookup.samplingDiagnostics() != null && lookup.samplingDiagnostics().totalEntries() > 0) {
            System.out.println("[PGO] stack samples not used: -H:-PGOUseStackSamples; the build behaves as for a profile without them");
        }
        // Checkstyle: resume
        if (!lookup.profileCategoryRecorded(IprofProfilesLookup.CONDITIONAL_PROFILES_CATEGORY)) {
            // Checkstyle: stop
            System.err.printf("[PGO] WARNING: profile contains no applicable conditionalProfiles (%d entries, %d resolved)%n",
                            lookup.conditionalDiagnostics().totalEntries(), lookup.conditionalDiagnostics().resolvedEntries());
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
        if (profilesLookup != null) {
            ApplyRootProfilesPhase applyPhase = new ApplyRootProfilesPhase(hostedUniverse, profilesLookup, samplingHotness);
            ListIterator<BasePhase<? super HighTierContext>> inliner = highTier.findPhase(AbstractInliningPhase.class);
            if (inliner != null) {
                inliner.previous();
                inliner.add(applyPhase);
            } else {
                highTier.prependPhase(applyPhase);
            }
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
        position.set(new SubstratePriorityInliningPhase(current, new SamplingInliningProvider(hostedUniverse, samplingHotness, profilesLookup), profilesLookup));
    }

    /** Creates the single-use PGO subphase separately for every compilation graph. */
    private static final class ApplyRootProfilesPhase extends BasePhase<HighTierContext> {
        private final HostedUniverse universe;
        private final IprofProfilesLookup lookup;
        private final SamplingHotness hotness;

        private ApplyRootProfilesPhase(HostedUniverse universe, IprofProfilesLookup lookup, SamplingHotness hotness) {
            this.universe = universe;
            this.lookup = lookup;
            this.hotness = hotness;
        }

        @Override
        protected void run(StructuredGraph graph, HighTierContext context) {
            if (hotness != null && graph.method() instanceof HostedMethod method) {
                /* Runs before the inliner and duplication, which read the provider from the root graph. */
                graph.setGlobalProfileProvider(hotness.providerFor(method));
            }
            PGOApplyProfilesPhase.createContextInsensitive(universe, lookup).apply(graph, context);
        }
    }

    /** Resolved entries per profile category, e.g. {@code branches 7505/7505, receivers 1610/1615}. */
    private static String resolutionSummary(IprofProfilesLookup lookup) {
        StringBuilder result = new StringBuilder();
        result.append("branches ").append(lookup.conditionalDiagnostics().resolvedEntries()).append('/').append(lookup.conditionalDiagnostics().totalEntries());
        if (lookup.virtualInvokeDiagnostics() != null && lookup.virtualInvokeDiagnostics().totalEntries() > 0) {
            result.append(", receivers ").append(lookup.virtualInvokeDiagnostics().resolvedEntries()).append('/').append(lookup.virtualInvokeDiagnostics().totalEntries());
        }
        if (lookup.callCountDiagnostics() != null && lookup.callCountDiagnostics().totalEntries() > 0) {
            result.append(", call counts ").append(lookup.callCountDiagnostics().resolvedEntries()).append('/').append(lookup.callCountDiagnostics().totalEntries());
        }
        if (lookup.samplingDiagnostics() != null && lookup.samplingDiagnostics().totalEntries() > 0) {
            result.append(", stack samples ").append(lookup.samplingDiagnostics().resolvedEntries()).append('/').append(lookup.samplingDiagnostics().totalEntries());
        }
        return result.toString();
    }

    @Override
    public void afterCompilation(AfterCompilationAccess access) {
        if (!printStatistics()) {
            return;
        }
        reportApplication(profilesLookup);
        if (samplingHotness != null) {
            // Checkstyle: stop
            System.out.printf("[PGO] sampling hotness: %d hot compilation roots, %d cold%n", samplingHotness.hotCompilationUnits(), samplingHotness.coldCompilationUnits());
            // Checkstyle: resume
        }
    }

    private static void reportApplication(IprofProfilesLookup lookup) {
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
        System.out.printf("[PGO] %d queries, %d hits (%.1f%%), %d misses; contexts: %d/%d used (%.1f%%), %d fully applied, %d partially applied, %d matched-not-applied, %d unused%n",
                        queries, hits, hitRate, misses, matchedContexts, availableContexts, contextUseRate,
                        lookup.fullyAppliedContextCount(), lookup.partiallyAppliedContextCount(), lookup.unappliedMatchedContextCount(), lookup.unusedResolvedContextCount());
        if (lookup.availableVirtualInvokeContextCount() > 0) {
            System.out.printf(
                            "[PGO] virtual invokes: %d queries, %d hits (%d via shortened context, avg %.1f frames dropped), %d misses; contexts: %d/%d used; impossible receivers dropped: %d records, %d events%n",
                            lookup.virtualInvokeHitCount() + lookup.virtualInvokeMissCount(), lookup.virtualInvokeHitCount(), lookup.virtualInvokeFallbackCount(),
                            lookup.virtualInvokeFallbackCount() == 0 ? 0.0 : (double) lookup.virtualInvokeFallbackDroppedFrames() / lookup.virtualInvokeFallbackCount(), lookup
                                            .virtualInvokeMissCount(),
                            lookup.matchedVirtualInvokeContextCount(), lookup.availableVirtualInvokeContextCount(),
                            lookup.impossibleReceiverRecords(), lookup.impossibleReceiverEvents());
        }
        System.out.printf("[PGO] shortened-context fallback: %d hits (avg %.1f frames dropped)%n",
                        lookup.contextFallbackCount(), lookup.contextFallbackCount() == 0 ? 0.0 : (double) lookup.contextFallbackDroppedFrames() / lookup.contextFallbackCount());
        // Checkstyle: resume
        if (PGOApplyProfilesPhase.Options.PGOPrintProfileQualityDetails.getValue()) {
            System.out.println("[PGO] resolved context samples: " + lookup.contextKeySamples(10));
        }
    }
}
