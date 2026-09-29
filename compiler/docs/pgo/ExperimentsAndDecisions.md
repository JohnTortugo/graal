# Experiments, evidence, and decisions

This page records the important evidence and the reasoning behind current defaults. Results are from
one fixed, large Java log-processing workload on AArch64. Times are normalized to the no-PGO image to
avoid coupling this public documentation to private application artifacts. The same workload was used
for training and evaluation, so these results demonstrate specialization, not generalization.

## Consumer-only milestone

An externally produced conditional profile was consumed by CE with exact full-context matching.
End-to-end branch reversal proved that opposite profiles changed generated branch layout correctly.

The first workload result was approximately 1% faster than no PGO. Investigation showed that the low
query-hit percentage was not a parser failure: cold startup and class-initialization graphs dominated
misses, while cross-compiler inlining differences changed context chains.

Decision: retain exact matching. Do not introduce leaf-only fallback without an isolated correctness
and performance experiment.

## CE producer milestone

A CE post-inlining producer trained and consumed by CE produced the strongest observed result:

| Configuration | Normalized time | Normalized throughput |
|---|---:|---:|
| No PGO | 1.000 | 1.000 |
| External conditional profile | 0.981 | 1.020 |
| CE post-inlining profile consumed early | **0.863** | **1.159** |

The result repeated across independently built images and multiple interleaved benchmark rounds.
Peak RSS did not improve.

Decision: preserve post-inlining production plus early consumption as the current performance
baseline, but keep it experimental until branch identity is corrected.

## Collection timing experiment

Consumer-aligned collection instruments root graphs before inlining and decoded inliner expansion
graphs with explicit caller context.

It improved resolved-context utilization from about 55% to about 90%, but was slower:

| Producer consumed early | Normalized time | Context utilization |
|---|---:|---:|
| Post-inlining | **0.863** | about 55% |
| Consumer-aligned | 0.916 | about 90% |

Decision: matching percentage measures compatibility, not optimization value. Do not replace a faster
profile solely because another profile matches more contexts.

## Stage-qualified consumption experiment

Independent channels were evaluated:

| Consumption policy | Normalized time | Relevant context result |
|---|---:|---|
| Post profile consumed early | **0.863** | about 55% used |
| Post profile consumed post-inlining only | 0.936 | 99.9% used and fully applied |
| Aligned early + post late | 0.899 | about 90% early, about 88% late |

Late-only consumption proves that stage-matched collection and consumption can use essentially every
resolved profile entry. It is slower because it cannot influence inlining and earlier HighTier
optimization. Applying an early profile changes the final graph, so 100% late utilization is not an
invariant in a multi-stage build.

Decision: retain separate stage channels and application accounting, but do not make stage-correct
consumption the performance default.

## Lookup versus application

A profile lookup hit means only that the context key exists. It does not prove that profile successor
BCIs matched the graph. Application accounting therefore reports distinct contexts that are fully
applied, partially applied, matched but not applied, or unused.

In the large workload, every matched context in the evaluated CE variants fully applied all successor
probabilities. Successful-query repetition was negligible. Total query counts varied by less than
about 0.5% across matched builds.

Decision: preserve successor-level application accounting. Do not use raw query-hit percentage as a
coverage or performance metric.

## Branch identity finding

Source inspection showed that `ProfilingUtilities` groups candidates as:

```text
NodeSourcePosition -> List<ControlSplitNode>
```

Temporary build diagnostics measured substantial many-to-one grouping. In the post-inlining build:

```text
553,319 selected registrations
500,756 context keys
34,414 multi-node source groups
2,532 contexts with conflicting successor pairs
up to 7 successor-pair variants under one context
```

In the consumer-aligned build:

```text
595,200 selected registrations
576,937 context keys
3,983 multi-node source groups
1,662 contexts with conflicting successor pairs
up to 7 successor-pair variants under one context
```

Cross-profile analysis found 10,047 active contexts present in both profiles. Of these, 151 had
different successor-BCI sets and 171 comparable contexts selected opposite dominant targets. Event
counts differed by at least 10x for 161 contexts, 100x for 39, and 1000x for 18.

Mechanisms include:

- early counters surviving after an `IfNode` is folded;
- copied counters from duplicated graph nodes;
- several transformed nodes inheriting one source position;
- instrumentation changing inlining decisions;
- post-inlining collection observing only surviving branches.

Decision: context-only counter deduplication is a correctness defect when successor pairs
conflict. It is retained only for external legacy input compatibility.

## Branch identity v2 implementation and result

The implemented precise identity is:

```text
stage
+ full method/BCI caller context
+ ordered successor-BCI signature
+ condition kind
+ deterministic occurrence ordinal
```

A bounded condition-shape fingerprint is serialized as advisory validation telemetry. It is not part
of the hard identity: repeated-build testing showed stable ordinals for every common site, while some
otherwise matching sites exhibited fingerprint drift. Graph-local node IDs remain rejected.

Each selected physical branch site now owns a counter. The serializer aggregates only identical v2
identities and emits them in `ceConditionalProfilesV2`. A legacy entry is emitted only when one
context maps to one precise identity. Exact v2 lookup has priority; cross-stage legacy fallback is
allowed only for such unambiguous contexts.

A large post-inlining training run produced:

```text
552,664 physical counters
19,260 active precise sites
18,095 safe legacy contexts
1,165 precise sites excluded from legacy ambiguity
0 duplicate v2 identities after resolution
```

At the matching post-inlining consumer stage:

```text
18,931/18,961 resolved sites used (99.8%)
18,931 fully applied
0 partial or matched-not-applied
30 unused
0 occurrence mismatches
```

Stage-correct v2 performance was effectively neutral versus context-only late consumption; their
three-run ranges overlapped. This is expected for a correctness fix that changes only ambiguous
sites.

The important result came from safe cross-stage adoption. Applying only the v2 file's unambiguous
legacy subset early improved every paired run:

| Early profile policy | Normalized time | Normalized throughput |
|---|---:|---:|
| Context-merged post profile | 0.865 | 1.156 |
| **v2 safe unambiguous post profile** | **0.844** | **1.185** |

The safe policy improved median time by 2.43% and throughput by 2.49% relative to the previous winner,
while reducing GC time by 2.72%. Relative to no PGO it improved time by 15.60% and throughput by
18.49%. Therefore ambiguous context aggregation was harmful; removing it strengthened rather than
explained away the earlier speedup.

Decision: use v2 exact identity whenever stage matches. When adopting a profile across stages, allow
only the serializer-proven unambiguous context subset. Never apply a context-only fallback when more
than one precise site shares that context.

A held-out workload is still required before claiming generalization.

## Public mx benchmark validation

The stock Native Image `mx benchmark` PGO pipeline was validated with Renaissance 0.16.1
`scala-doku`, `scrabble`, and `fj-kmeans`, plus DaCapo 23.11-MR2 `sunflow`. The benchmark harness now
accepts a non-empty `ceConditionalProfilesV2` profile as an intentional conditional-only profile;
profiles without the CE extension retain the existing sampling-profile assertion.

Longer run-only samples produced:

| Benchmark | Primary statistic | PGO delta | Classification |
|---|---|---:|---|
| scala-doku | 9-iteration steady median | -0.46% | near noise |
| scrabble | 3-fork all-steady mean | +0.68% | no win; high variability |
| fj-kmeans | 9-iteration steady median | +0.43% | near noise |
| sunflow | 3-fork all-iteration median | +0.13% | neutral; high variability |

Profiles resolved 96.6–99.9% of precise sites. Safe cross-stage fallback used 50.9–57.2% of resolved
sites, and every matched site fully applied its successor records. Between 4.6% and 5.5% of active
precise sites were excluded from legacy fallback because more than one site owned the context.

Instrumentation overhead was workload-sensitive: approximately 1.23x for scala-doku, 4.2x for
scrabble, 21x for fj-kmeans, and 16x for sunflow. Parallel workloads expose substantial shared-counter
cache traffic even though increments are non-atomic.

Decision: branch identity v2 and safe fallback are validated across unrelated applications, but
conditional-only PGO is workload-selective. Do not infer broad performance improvement from one
application. Reduce instrumentation overhead and add profile-usefulness filtering before expanding to
a new profile category.

## Instrumentation overhead: per-thread counters

A microbenchmark (hot loop, five data-dependent branches per iteration, identical work per thread)
isolated the cost of the first producer. Single-threaded slowdown was 1.15x; at 16 threads it was
11x. The generated per-successor code was already minimal (constant offset plus load/add/store, no
bounds check), so the growth came from every core writing the same cache lines of the shared
counter array. The same racy increments also lost most events: a 4-thread run recorded 26% of the
exact expected count.

The producer now gives each thread a private native counter block (see Architecture, "Per-thread
counter blocks"). Getting there took three variants, each measured on the fixed single-worker
application training run (old shared array: 183.7 s and 184.6 s in two rounds):

| Variant | Per-site code | Training run | Notes |
|---|---|---:|---|
| thread-local + null check, shared-array fallback | load TL, `cbz`, load/add/store | 194.6 s, 193.6 s (+5.4%) | correct; the diamond at every site is the cost, not the extra load |
| thread-local, no null check | load TL, load/add/store | 183.3 s | as fast as before, but crashed a public benchmark: a foreign thread constructs its `Thread` object (instrumented JDK code) before the thread-start listener runs |
| displacement from shared base, no check | load TL, `add`, load/add/store | 191.3 s (+4.2%) | branch-free and safe, but the extra add on the address path is not free in call-heavy code |
| block allocated at thread attach (final) | load TL, load/add/store | 180.4 s (−1.8%) | requires a listener callback at the end of `VMThreads.attachThread`, the only point that precedes every Java execution on a thread |

Results with the final variant on the same host:

| Workload | Old slowdown | New slowdown |
|---|---:|---:|
| microbenchmark, 1 thread | 1.15x | 1.14x |
| microbenchmark, 16 threads | 10.6x | 1.14x |
| microbenchmark, 64 threads | 48x | 1.22x |
| fj-kmeans instrument-run | 21x | 1.15x |
| sunflow instrument-run | 16x | 1.19x |
| application training (single worker) | 1.41x | 1.38x |

Counts are now exact under concurrency (the 4-thread microbenchmark records precisely the expected
value). Old parallel-benchmark profiles had lost 72–79% of events; dominant-successor agreement
between old and new profiles was still 99.6–99.7%, so ratios had mostly survived, but the absolute
counts any usefulness filter would depend on had not. On the fixed single-worker application
workload the new producer recorded the same event total within 0.012% and the resulting PGO image
was performance-neutral versus the previous profile (+0.8% median, overlapping ranges).

Snapshotting counts before serialization also removed a few hundred one-event sites per profile
that the old producer had recorded from its own file-writing code. Replacing the fixed image-heap
array with global data sized after compilation shrank instrumentation images by 14–20 MB.

Decision: per-thread blocks allocated at attach are the producer baseline. Instrument-image memory
rises by 16 bytes per instrumented site per live thread, calloc-backed, and was not measurable in
run RSS.

## Profile usefulness filter (opt-in; tuning deferred)

With exact counts available, the consumer gained telemetry on how each applied profile relates to
the probability the node already carried. On the fixed application workload, 47% of applied sites
had ten or fewer recorded events, and contradictions of the compiler's prior concentrated there: of
56 contradictions in the lowest decade, 49 overrode an *injected* probability (domain knowledge such
as "exception path unlikely"). Above ten thousand events, contradictions were almost absent. Across
four unrelated profiles, 47–62% of sites had fewer than ten events while sites with at least one
hundred events carried 100.00% of all events.

Two opt-in hosted options let a matched site keep its static probability instead:
`-H:PGOConditionalMinEvents=<n>` withholds sites below an absolute event count and
`-H:PGOConditionalMinBias=<share>` withholds sites whose dominant successor share is below the
threshold. Withheld sites are reported separately from misses.

Measured on the fixed application workload (same profile, three interleaved rounds):

| Setting | Applied sites | Median time vs unfiltered |
|---|---:|---:|
| none | 9,575 | — |
| minimum 100 events | 2,769 | −1.8% (one outlier round; suggestive, not established) |
| minimum 10,000 events | 862 | +0.3% (withholds useful mid-count sites) |
| minimum 0.6 dominant share | 9,228 | −0.6% (neutral) |

Public suites moved in the favorable direction by 0.2–0.7%, within noise.

Decision: the filter stays opt-in with both thresholds defaulting to off. Tuning is deferred to a
separate investigation rather than continued inside the implementation track: a single aggregate
count per site is likely the wrong lens, because a branch may behave differently in different
phases of the program (startup versus steady state), and the mechanism behind the small gain —
restoring injected priors, layout side effects, or noise — has not been isolated. Candidate
follow-ups are per-phase profile snapshots, relative rather than absolute thresholds, and shrinkage
toward the prior instead of a hard cut.

## Second category: receiver-type profiles (consumer-first)

The open-source tree already contains consumers for several further iprof sections, all inert in
CE because nothing supplies the data: `virtualInvokeProfiles` (receiver-type histograms per indirect
call) feed the priority inliner's inline-cache construction, and `samplingProfiles` feed call-tree
hotness, the hot-caller inliner bonuses, hot-callee devirtualization, and duplication prioritization.

Receiver-type profiles were taken first, consumer-first as in the first milestone: the section is
parsed and resolved, and an external reference profile's receiver histograms were grafted onto the
CE conditional profile for the fixed workload so the delta isolates the new category. 1,656 of 1,993
entries resolved and 82% were consumed at indirect call sites.

The stock application path made the image **1.3% slower** (paired, three rounds, same sign). Cause:
closed-world analysis already devirtualizes every monomorphic call and gives the remaining indirect
calls an exact type profile with zero not-recorded probability, which is what lets the inliner emit a
complete type switch with no fallback invoke. The apply path injected a not-recorded probability
unconditionally, so every profiled site gained a fallback call it did not need; it could also admit
receiver types the analysis had proved impossible.

Fix: when the static profile is exact, keep it exact — restrict observed receivers to the analysed
set and do not inject a not-recorded probability. Unobserved analysed types keep an extremely small
probability rather than zero: inline-cache construction folds a type whose target cannot be resolved
into the not-recorded probability and only emits a fallback when that is positive, so a zero entry
would leave a receiver uncovered (one build with exact zeros faulted on a cold path). With the fix
the same profiles gave −0.18/−0.37/−0.56% in three paired rounds.

Decision: keep the exactness fix (any receiver source, including sampling-derived method profiles,
passes through the same path), but do not build a CE receiver-type producer now. The ceiling is
structurally low in a closed world — only polymorphic inline caches can be re-ordered — so the next
category is sampling-based hotness, which gates far more of the dormant machinery.

## Third category: sampling-based hotness (consumer-first)

The tree's remaining dormant machinery is gated on hotness: a per-compilation-root calling-context
cursor for the priority inliner (cutoff hotness, sampled callee profiles), and a per-graph
`GlobalProfileProvider` (hot caller, self time) consulted by hot-callee devirtualization, profile
application while expanding, and duplication budgets. The per-context compilation-root selection
that drives this in the commercial implementation is not in the tree, so the consumer builds a
method-rooted approximation: every frame of every sampled stack roots a suffix, giving each method a
merged calling-context tree below it.

External sampling profiles are structurally hard to consume: 98% of the reference profile's samples
passed through two hidden-lambda thread-entry frames that cannot exist under the same name in
another build. Resolving stacks from the leaf outward and truncating at the first unresolvable
frame retained 99.4% of samples (614 of 642 stacks truncated, 2 complete).

Measured on the fixed workload against the conditional-only image (paired, three rounds each; a
first run was discarded because image builds were running concurrently on the host):

| Configuration | Mean paired delta |
|---|---:|
| all hotness mechanisms on, bonuses 0 | +0.06% / +0.70% (two runs) |
| without sampled callee method profiles | +1.05% |
| without hot-caller gating | +0.02% |
| without self time | +1.24% |
| everything gated off (inliner swap only) | −0.43% |
| hot-inlining bonus 1 | **+5.43%** |
| hot-inlining bonus 3 | +4.84% |
| expansion bonus 1 + inlining bonus 1 | +1.41% |

Hot-caller gating, self-time duplication budgets and sampled callee profiles are indistinguishable
from noise here; the hot-inlining bonus, which multiplies a callee's local benefit by
`1 + bonus × hotness`, is clearly harmful with these inputs.

Decision: the plumbing is committed, neutral by default (bonuses 0) and fully option-gated, but this
category cannot be evaluated consumer-first. Cross-build samples lose their outer contexts, the
context-insensitive root approximation differs from the commercial design, and the tuned bonus
values are unknown. A same-build sampler producing `samplingProfiles` in the CE instrumentation
image is the prerequisite for an honest measurement and is the next producer candidate.

## Loop headers and profile-induced inlining shapes

Reading the priority inliner's decision for a hot cutoff (a trivial `String.charAt` call left out of
line in the fixed workload's hottest per-character loop, 7% of run time) exposed two defects.

Producer: the site selector kept one `IfNode` per source position, preferring the copy with default
probability. After loop transformations a loop header's bytecode branch exists as a peeled guard
(default probability) and as the in-loop exit condition (compiler-assigned probability). The selector
kept the guard and dropped the copy carrying the iteration count, so every counted loop read as "runs
once, never exits". The producer now instruments every physical copy (precise identities keep them
distinct) and the legacy context entry is the sum over copies that route to the same successor BCIs,
negated conditions included; rewired copies stay ambiguous.

Consumer: lookups answered only for the exact inlining context. Profiles change inlining, so a callee
the training build compiled standalone becomes inlined into a caller the profile never saw, and all
branches of that inlined copy fell back to default probabilities — the loop appeared to iterate twice,
and the inliner would not spend on its body. The consumer now falls back to the same branch under
progressively shorter contexts (`-H:PGOContextFallback`, default on), reported separately.

Fixed workload, paired against the previous best image: **−8.8%** (102.9 s vs 112.6 s), query hit
rate 1.4% → 29%, the `charAt` call inlined. The new producer without the fallback was **+9.8%**:
correct loop counts alone pull more code into hot roots and then starve the unseen contexts, so the
two changes are only sound together. Public suites stayed at noise level (scala-doku +0.5%,
fj-kmeans +1.1%, scrabble −0.8% over three forks, sunflow −0.6%); their shortened-context fallback
counts were tiny because their training and final inlining shapes already agreed. Instrumentation
overhead rose modestly with the extra sites (fj-kmeans 1.15x → 1.29x).

Versus the no-PGO baseline the fixed workload is now −21%, and 16.5% behind the commercial PGO
result (was 25%).

## Same-build stack sampling and receiver-frequency safety

The CE instrumentation image now emits `samplingProfiles` in the same run as branch profiles. A
recurring callback walks each Java thread every 10 ms by default and records raw instruction-pointer
chains in a private native open-addressed table. The callback cannot allocate, so decoding, inlined-
frame expansion, method identity resolution, and stack merging happen at teardown. Method signatures
are included in code metadata whenever sampling is active. Explicit slash/dot conversion for hidden-
class descriptors made lambda frames resolve consistently between training and optimized builds.

On the fixed workload, one training run produced 75,224 samples from 13 threads, merged to 432 stacks;
all sample and conditional contexts resolved. Sampling did not add measurable training overhead. With
a hot-inlining bonus of 1, a fresh paired three-round confirmation improved the previous best image by
**2.4%** (98.17 s versus 100.58 s), with serial-GC time falling from 2.61–2.66 s to 2.55–2.58 s. This
puts the fixed workload 24.8% below its no-PGO baseline and 11.2% above the timing-only commercial PGO
reference. Expansion bonuses remained unhelpful, so their default stays zero.

Public validation found an important semantic failure before acceptance: scala-doku regressed 9.2%.
The regression required sampled callee method profiles and hot-caller devirtualization; disabling
hot-callee devirtualization removed it. At a hot Scala function bridge, samples selected two
long-running outer lambdas for receiver guards, while the tiny receivers actually dispatched at the
bridge were not observed as leaves. Every dispatch paid failing guards.

The issue is fundamental: stack samples weight time below a callee, whereas receiver guards need
dispatch frequencies. Receiver-based hot-callee devirtualization now requires a dynamic receiver type
profile. Sampling-only method profiles remain available to the regular cost-benefit inliner. After the
fix scala-doku returned to +1.2% (the same as globally disabling the transformation); fj-kmeans and
scrabble remained within noise, and sunflow showed no regression.

Decision: accept the same-build sampler, default smooth hot-inlining bonus 1, smooth expansion bonus 0, and the
receiver-frequency guard. Do not use sample-time method profiles as receiver-frequency profiles.

## Confidence-gated hot-context inlining

The public tree's `PrefixTree` exposes hot/cold-root state, but CE has no integration that creates
analysis-time method variants and redirects selected AOT calls to context-specific compiled roots.
The first bounded step instead specializes hot contexts through the existing priority inliner.

Each call-tree node now carries its inclusive sample count as well as its share of the compilation
root. Discrete bonuses apply only when the profile has at least 5,000 samples, the context has at
least 50 samples, and it represents at least 5% of its root. Selected contexts receive expansion
priority +5 and local-benefit multiplier +1; the existing smooth hotness multiplier remains additive
and normal call-tree budgets cap growth. Option domains are validated, and unobserved contexts never
qualify.

The confidence gates account for a limitation of recurring-callback sampling: leaf observations are
biased toward code that can service callbacks. Inclusive calling contexts still identify active hot
paths, but sparse leaves are not sufficient evidence for aggressive inlining.

Fixed workload, three rotated paired rounds: 98.006 s previous best versus **95.629 s**, a **2.42%**
improvement, with serial-GC time comparable. This is 26.8% below the no-PGO baseline and 8.3% above
the timing-only commercial PGO reference. Public validation used contemporaneous run-only baselines
after detecting a host-speed shift: scala-doku +0.5%, fj-kmeans −9.3%, scrabble +2.5% over three
high-variance forks, and sunflow −14.2%. These runs establish no robust regression; the public
improvements are not claimed as general speedups.

Decision: accept confidence-gated context-aware inlining. Separate context-specific AOT method
variants remain a larger future architecture project, not a capability claimed here.

## Receiver-frequency producer experiment

A CE `virtualInvokeProfiles` producer recorded `(call site, concrete receiver type)` frequencies in
per-thread native tables. The first pre-inlining prototype appeared to improve the fixed workload,
but review found that it could alias cached expansion contexts, race teardown, retain memory for every
historical thread, under-report losses, and perturb the training inliner.

A safety redesign instrumented only root sites after priority inlining, merged and freed exited-thread
tables, snapshotted live tables at a safepoint, and reported all losses. It recorded 1.568 billion
events across 369 active contexts with about 3.8% training overhead and no lost events. The isolated
same-profile result was **+0.27%** (96.266 s with receiver profiles versus 96.009 s with only that
section removed), i.e. noise/slight regression.

Decision: do not merge the producer. Keep the external receiver-profile consumer and the requirement
that receiver guards have dispatch-frequency evidence. Revisit only when a workload demonstrates a
substantial remaining indirect-call ceiling.

## Validation standard

Each accepted iteration requires:

- focused parser, resolver, probability, serialization, and accounting tests;
- primary checkstyle;
- full Native Image distribution build;
- tiny instrument, dump, consume, and functional smoke test;
- matched application build;
- interleaved runtime measurements;
- source, profile, input, and binary provenance outside public documentation.
