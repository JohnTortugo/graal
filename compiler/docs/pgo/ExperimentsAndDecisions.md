# Experiments, evidence, and decisions

This page records the important evidence and the reasoning behind current defaults. Results are from
one fixed, large Java log-processing workload on AArch64. Times are normalized to the no-PGO image to
avoid coupling this public documentation to private application artifacts. The same workload was used
for training and evaluation, so these results demonstrate specialization, not generalization.

## Producer milestone

A post-inlining producer, with its profile consumed early, produced the strongest observed result:

| Configuration | Normalized time | Normalized throughput |
|---|---:|---:|
| No PGO | 1.000 | 1.000 |
| Post-inlining profile consumed early | **0.863** | **1.159** |

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

In the large workload, every matched context in the evaluated variants fully applied all successor
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

Decision: context-only counter deduplication is a correctness defect when successor pairs conflict.
It is retained only for profiles that contain the standard `conditionalProfiles` section alone.

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
identities and emits them in `preciseConditionalProfiles`. A legacy entry is emitted only when one
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
accepts a non-empty `preciseConditionalProfiles` profile as an intentional conditional-only profile;
profiles without `preciseConditionalProfiles` retain the existing sampling-profile assertion.

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

## Second category: receiver-type profiles (consumer)

Native Image already contains consumers for several further iprof sections that had no producer:
`virtualInvokeProfiles` (receiver-type histograms per indirect call) feed the priority inliner's
inline-cache construction, and `samplingProfiles` feed call-tree hotness, the
hot-caller inliner bonuses, and hot-callee devirtualization.

The receiver-profile consumer was enabled first. Its stock apply path lost closed-world exactness:
closed-world analysis already devirtualizes every monomorphic call and gives the remaining indirect
calls an exact type profile with zero not-recorded probability, which is what lets the inliner emit a
complete type switch with no fallback invoke. The apply path injected a not-recorded probability
unconditionally, so every profiled site gained a fallback call it did not need; it could also admit
receiver types the analysis had proved impossible.

Fix: when the static profile is exact, keep it exact — restrict observed receivers to the analysed
set and do not inject a not-recorded probability. Unobserved analysed types keep an extremely small
probability rather than zero: inline-cache construction folds a type whose target cannot be resolved
into the not-recorded probability and only emits a fallback when that is positive, so a zero entry
would leave a receiver uncovered.

Decision: keep the exactness fix (any receiver source, including sampling-derived method profiles,
passes through the same path), but do not build a receiver-type producer yet. The ceiling is
structurally low in a closed world — only polymorphic inline caches can be re-ordered — so the next
category is sampling-based hotness, which gates more of the existing consumer logic.

## Third category: sampling-based hotness (consumer)

The remaining existing consumer logic is gated on hotness: a per-compilation-root calling-context
cursor for the priority inliner (cutoff hotness, sampled callee profiles), and a per-graph
`GlobalProfileProvider` (hot caller, self time) consulted by hot-callee devirtualization and profile
application while expanding. Native Image has no per-context
compilation-root selection; the consumer instead builds a method-rooted calling-context tree: every
frame of every sampled stack roots a suffix, giving each method a merged calling-context tree below
it. Stacks are resolved from the leaf outward and truncated at the first frame that does not resolve
in the image.

Decision: the plumbing is committed, neutral by default (bonuses 0) and fully option-gated. A
sampler producing `samplingProfiles` in the instrumentation image is required to measure this and is
the next producer candidate.

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

Versus the no-PGO baseline the fixed workload is now −21%.

## Stack sampling and receiver-frequency safety

The instrumentation image now emits `samplingProfiles` in the same run as branch profiles. A
recurring callback walks each Java thread every 10 ms by default and records raw instruction-pointer
chains in a private native open-addressed table. The callback cannot allocate, so decoding, inlined-
frame expansion, method identity resolution, and stack merging happen at teardown. Method signatures
are included in code metadata whenever sampling is active. Explicit slash/dot conversion for hidden-
class descriptors made lambda frames resolve consistently between training and optimized builds.

On the fixed workload, one training run produced 75,224 samples from 13 threads, merged to 432 stacks;
all sample and conditional contexts resolved. Sampling did not add measurable training overhead. With
a hot-inlining bonus of 1, a fresh paired three-round confirmation improved the previous best image by
**2.4%** (98.17 s versus 100.58 s), with serial-GC time falling from 2.61–2.66 s to 2.55–2.58 s. This
puts the fixed workload 24.8% below its no-PGO baseline. Expansion bonuses remained unhelpful, so
their default stays zero.

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

Decision: accept the stack sampler, default smooth hot-inlining bonus 1, smooth expansion bonus 0,
and the receiver-frequency guard. Do not use sample-time method profiles as receiver-frequency
profiles.

## Receiver-frequency producer experiment

A `virtualInvokeProfiles` producer recorded `(call site, concrete receiver type)` frequencies in
per-thread native tables. The first pre-inlining prototype appeared to improve the fixed workload,
but review found that it could alias cached expansion contexts, race teardown, retain memory for every
historical thread, under-report losses, and perturb the training inliner.

A safety redesign instrumented only root sites after priority inlining, merged and freed exited-thread
tables, snapshotted live tables at a safepoint, and reported all losses. It recorded 1.568 billion
events across 369 active contexts with about 3.8% training overhead and no lost events. The isolated
same-profile result was **+0.27%** (96.266 s with receiver profiles versus 96.009 s with only that
section removed), i.e. noise/slight regression.

Decision: retain the lifecycle-safe producer behind independent controls. `-H:-PGOProfileReceivers`
removes producer instrumentation and collection; `-H:-PGOUseReceiverProfiles` disables consumer
lookups and mutation. The unsafe pre-inlining implementation remains rejected, but a neutral result
on one workload does not justify removing a sound optimization that may help polymorphic programs.

## Exact post-inlining call edges

The producer now emits standard `callCountProfiles` for direct and exact-monomorphic calls that
remain after priority inlining. Attempts to defer metadata assignment or place context markers before
inlining were rejected after they failed to preserve runtime execution counts. The accepted phase
runs immediately after the inliner, where both target and full caller context are stable.

Sparse per-thread native tables recorded 27.22 billion fixed-workload edge executions in 4,000 active
contexts with zero drops. The largest edge executed 9.225 billion times. Method totals remain usable
when only an outer context frame is unresolved. The inliner combines exact edge/root invocation ratio
with sample hotness, but does not treat call executions as sampling confidence.

Fixed-workload same-profile isolation was neutral: **−0.20%** (99.020 versus 99.215 s), with a 0.33 MB
image increase and roughly 24% additional training time over receiver-only instrumentation. Public
pipelines showed different directional behavior, including improvements on three suites and a small
regression on one; host-speed drift prevents general performance claims.

Decision: retain producer and consumer independently. `-H:-PGOProfileCallCounts` removes collection;
`-H:-PGOUseCallCounts` disables optimization use. A neutral result on one application does not remove
a sound profile category.

## Switch successor profiles

The producer now covers `SwitchNode` through standard conditional records. Context-only ambiguity is
handled conservatively: only identical successor shapes aggregate, while duplicate-BCI, rewired, and
legacy-`If` collision contexts are omitted. The consumer requires a complete mapping and installs the
whole distribution atomically; case keys sharing one block retain their prior relative weights.

On the latest upstream master base, the fixed workload recorded 3,864 physical switches, 103 active
contexts, and roughly 719 million successor executions. Same-tip isolation was neutral: switch off
100.184 s, switch on 100.381 s (**+0.20%**), image +65,536 bytes. The sound mechanism remains enabled
and independently controlled by `-H:-PGOProfileSwitches` / `-H:-PGOUseSwitchProfiles`.

## Profile-guided method layout

Block emission already consumes PGO-derived CFG frequencies. Whole-image method layout previously
sorted by method name; it now sorts by descending resolved call count, retaining method-name order for
ties and absent profiles. Early and post-inlining-only profiles work, LLVM is unchanged, and
`-H:-PGOUseCodeLayout` disables the optimization.

Latest-mainline same-profile isolation improved the fixed workload by **1.01%** (99.189 versus 100.202
seconds) with identical image size and comparable GC. The mechanism remains independently gated.

## Trusted finals and structural compare implication

Reading the IR for the hottest loop showed two defects: final instance fields of a closed-world
type were re-read after every call, and a bounds check `i < value.length` survived next to the loop
condition `i < value.length >>> coder` because the implication is not a stamp fact.

`-H:+TrustFinalInstanceFields` (default on) makes the Substrate constant field provider report final
instance fields as trusted when every recorded write initializes a fresh object (constructor stores
by bytecode position; `CommitAllocationNode` field values are initialization by construction).
Unsafe, reflection and JNI writes, image layers and the reachability engine disable the trust.
`StructuralCompareImplication` (default on) lets a dominating `x < A` prove `x < B` when `A` is
`B >>> s`, `B >> s`, `B & m`, `min(B, c)` or `B + c` with `c <= 0`.

Hot loop 11 → 9 instructions per byte; fixed workload **−1.49%**, image −0.14%. Eleven unit tests.

## Range-check elimination by loop versioning

Native Image cannot deoptimize, so `LoopPredication` and `SpeculativeGuardMovement` do not apply and
every bounds check whose bound is not provable from the loop condition stays in the body — three of
ten instructions per character in the workload's newline scan, whose bound is a field.

`LoopRangeCheckVersioningPhase` (`-H:LoopRangeCheckVersioning`, default on) versions hot innermost
counted loops: the entry test `init(iv) < bound && extremum(iv) < bound && no overflow`, computed in
64 bits from `InductionVariable.computeEndpoints`, selects a check-free copy; array-length bounds
read behind an in-loop null check are re-read behind a hoisted one. The loop must be small (≤ 400
nodes) and hot by a **profiled** frequency: default frequencies of unprofiled loops claim billions of
iterations and would version 1,354 loops (+1.07% image, −0.27%); with the rule 122 loops, +0.07%.

Scan loop 10 → 8 instructions per character; fixed workload **−0.88%**. Twelve unit tests.

## AArch64 code generation (independent of PGO)

Two backend fixes found by reading the hot loop's generated code; both apply to every Native Image
on AArch64.

Array addressing: `base + (ext(i) << k) + header` has no addressing mode and, without derived
references, was emitted as `mov tmp, #header; add tmp, tmp, i, ext #k; ldr [base, tmp]`. The
lowering now emits `add scratch, base, i, ext #k; ldr [scratch, #header]` inside one LIR
instruction, for plain loads and stores whose address and index sum have a single user (a shared
index, as in the card-table barrier, is cheaper computed once; without that rule the text grew
0.4%). Instructions −0.37%, fixed workload **−0.86%**. Ten unit tests.

Compressed reference decode: `UncompressPointerOp` widened a 32-bit input with `mov w, w` before the
shifted add; the extended-register add (`add x, base, w, uxtw #3`) widens itself. Instructions
**−2.5%**, image −1.5%, fixed workload **−0.40%**. Three unit tests.

## More prior-art experiments (rejected, with numbers)

All measured on the fixed workload and a binary-format workload, each with its own profile, and on
the fixed-workload profile applied to the binary-format input (the "untrained path" case).

- Damping the inlining benefit in roots the profile never saw (LLVM lowers its threshold at cold
  sites): fixed workload neutral, untrained path +1.4% / +5.6% slower at ×0.5 / ×0.25, and the
  image *grew* 1.6% / 4.9% — less inlining makes callees standalone compilation units. Rejected.

Adopted from the survey: weighted profile merging and per-run dump files (LLVM/GCC) and the
sampling-overlap diagnostic (`gcov-tool overlap`). The merged text+binary-format image now matches
the specialized images on both inputs (fixed workload 72.59 vs 72.74 s; binary format 108.1 vs the
text-only image's 139.7 s).

## Binary-format reader: why the gap to C2 remained and what closed part of it

With every improvement above, the binary-format workload stayed 5–20% behind HotSpot C2 while the
text workload was 10–19% ahead. A characterization with hardware counters (process-wide, per pass)
ruled out allocation, GC, branch prediction and TLB behaviour: the image retired 18.8% more
instructions at a higher IPC, all of it in the reader, and made 23% more calls and 43% more
indirect branches. Two defects were found and one was fixed:

1. **Receiver profiles missed by context** (fixed, default on, see README "Context fallback"):
   −2.0% binary format, −1.4% text, scala-doku neutral. It removed the indirect-call excess over C2
   entirely but only 1% of the instructions — devirtualization was not the gap.
2. **Branch records taken on a different graph shape** (see README "Profile shape and the
   instrumentation stage"). A 70-line program that reads the same file with the Ion library alone
   reproduces the gap (CE+PGO 109 G instructions and 1 232 M calls per pass vs C2 76 G and 321 M;
   8.3 vs 6.4 s) and shows the mechanism in the priority inliner's call tree: a post-high-tier record
   for a loop exit whose real exit lives in an inlined callee says "never exits", the loop's slow
   path is priced at 100× the method's frequency, the expansion budget goes there, and the hot
   `nextValue()` subtree ends up with a negative benefit (−2349 for 2254 nodes). Instrumenting with
   `--pgo-instrument-aligned` gives the record its real counts; the reader then reaches 447 M calls
   and 6.6 s, and the full workload improves 2.8% — but the
   same profile costs the text workload 4%, so the stage stays a per-workload choice. A consumer-side
   flow-consistency check (`-H:+PGOConditionalFlowCheck`) recovers part of it on the reader and loses
   5% on the full workload; kept as an experimental option, off.

Rejected on measurement in the same investigation:

- Expansion-side bonuses (`PGOHotExpansionBonus`, `PGOHotInliningBonus`): within noise on the
  standalone reader; the binding constraint is the inlining decision's benefit/cost, not expansion
  priority.
- String-leaf code quality was investigated and is *not* a cause: a micro-benchmark of fresh-string
  `hashCode`, `HashMap.get`, `toLowerCase` and the unit-validation method shows parity with C2 when
  the image is built with PGO (1.05× for the whole method; `toLowerCase` 1.55× is the one outlier). A
  first run without PGO had shown 1.4–1.9× and was wrong for that reason.

## What building under hosted assertions found

An end-to-end gate (`mx gate --tags pgo`, see README "Validation") builds instrumented and
optimized images of a deterministic workload with `-J-ea -J-esa -H:+VerifyGraalGraphs
-H:+VerifyPhases` and compares their output with the JDK's. Its first runs failed in the compiler
rather than in the workload; each failure was a latent defect of the instrumentation that release
builds had masked:

- The instrumentation node classes were not exported to the compiler module, so graph verification
  could not reflect on them (`suite.py` now opens `com.oracle.svm.hosted.pgo`).
- The counter snippets used untyped pointer accesses (location `OFF_HEAP_LOCATION`) and `Unsafe`
  atomics (`ANY`), while their nodes declared no kill or a kill of `ANY`. Either way the snippet
  template's memory rewiring rejects the graph: a snippet may touch only private locations or
  locations its node kills, and a node that kills `ANY` forces every later floating read onto a kill
  inside the snippet. All table accesses now carry a named location declared private to the
  snippet, the nodes are not memory kills, and the rare paths that need atomics are foreign calls
  killing only that location. The instrumented image no longer acts as a full memory barrier at
  every instrumented call site and method entry.
- Snippet bodies contained hosted-only branches and bounds-checked array accesses (an exception
  path inside a snippet); the hosted paths used by unit tests are separate methods now.
- `SubstratePriorityInliningPhase` asserted that the priority inliner's graph cache is off whenever
  profiles are applied to expanded callee graphs, because the cached graph (with the profile of the
  first expansion's context) is shared with every later expansion of that callee in the
  compilation unit. Turning the cache off per the assertion was measured, three interleaved runs
  each on the binary-format reader workload: +1.2% with the post-high-tier profile (26.70 → 27.01 s)
  and +4.0% with the aligned profile (26.02 → 27.06 s), with 9% more profile queries. The shared
  graph carries the profile of the callee's highest-priority context; per-context application
  mostly produces misses that fall back to shortened contexts. The cache stays on, the assertion is
  replaced by that documented decision, and `-H:-UseGraphCache` remains available and is covered by
  the gate.

The workload's first version also exposed an assumption in the test itself: with instrumented and
optimized images inlining identically, the receiver-profile context fallback never engages. The
gate now builds a second instrumented image whose interface-call wrappers are compiled standalone
(`-H:NeverInline`), so the optimized image — which inlines them during decoding — queries the same
sites under deeper contexts; that profile must be served through the fallback (and must not be when
the fallback is disabled), and both images must still match the JDK.

## What checking the compiled graphs found

Identical program output does not show whether a profile was *applied as recorded*, so the gate
also reads the graph dumps of the instrumented, unprofiled and profiled builds
(`PgoEffectsCheck.java`, README "Validation"). Its first run on graphs that printed the right
output found:

- 18 of 72 profiled branches in the workload's graphs carried values that matched no record:
  branches whose successors have no bytecode position — the three conditions inside the inlined
  `String.equals` intrinsic (bci −1) and the type tests on exception edges (bci −4). The producer
  summed the distinct intrinsic conditions into one legacy entry because their unknown successors
  look identical (115 408:0 plus 0:57 704 became 0.5), and the consumer keyed successors by
  bytecode index, so both successors of such a branch received the same probability, replacing
  the injected 0.99/0.01 prior with 0.5 (or 0.41, or 0.999999 on both sides of an exception-edge
  test). Fixed on both sides: unknown-successor copies merge into a legacy entry only when they
  test the same condition, and a record applies only to successors that have a bytecode position
  (`validConditionalBci`), so these branches keep their prior. Unit tests
  `differentConditionsWithUnknownSuccessorsGetNoLegacyEntry`,
  `sameConditionCopiesWithUnknownSuccessorsStillSum`,
  `successorsWithoutBytecodePositionReceiveNoProbability`. The fix leaves 158 matched contexts of
  the binary-format reader workload unapplied (their branches keep the injected prior). Measured
  against images built by the previous compiler from the same profiles, three interleaved rounds
  each, ranges not overlapping: binary format with the post-high-tier profile 26.44–26.47 s vs
  26.71–26.88 s (−1.2%); binary format with the aligned profile 26.29–26.36 s vs 26.09–26.14 s
  (+0.8%); text with the post-high-tier profile 73.82–74.31 s vs 72.99–73.60 s (+1.3%). All three
  are below the bar for claiming either a win or a loss, but the two slowdowns are consistent, so
  the garbage values those branches used to receive were, on average, slightly better priors than
  the injected ones. The correct fix for that is to apply unknown-successor records by successor
  order where the record is unambiguous, not to restore the garbage; it is left open.
- The remaining 54 profiled branches equal the recorded ratios on the right successor, the string
  switch carries the recorded distribution, and the recorded counts equal what the program
  counted (one end-of-input exit after 200 000 records; 89 600 squares, 25 600 triangles, 12 800
  circles; 4 000 000 receivers at the monomorphic site).
- A monomorphic receiver profile alone does not devirtualize: the inline cache the inliner
  builds from the profile is expanded only in a sampled-hot root and only for a callee the
  samples attribute time to (`devirtualizeHotCallees`, `isCallSiteToHotCaller`). Samples are
  taken at safepoint checks, so a callee whose body is a counted loop is never sampled itself; the
  workload's area computation is an uncounted loop for that reason. With the callee sampled, the
  interface call becomes a guarded direct call and the interface call survives only on a path of
  frequency 1e-151 — which the gate checks; on the unseen odd input the guard fails every time
  and the output still equals the JDK's.
- The workload's own "mixed" distribution was wrong (`%` on negative seeds made it 99.96% squares
  instead of 70/20/10); the ratio check against the program's printed counts caught it.

## What running the test corpus found

`mx gate --tags pgo_unittests` builds and runs the 267 native unit tests plain, instrumented and
profile-optimized (about 7 minutes). All 267 pass in the plain image and in the image optimized with
the profile the instrumented run wrote. The instrumented image fails exactly one class, the three
`TestVirtualThreadsExecutionSample` tests: the PGO stack sampler occupies the per-thread recurring
callback that JFR's recurring-callback execution sampler installs only when the slot is free
(`JfrRecurringCallbackExecutionSampler.install`). Confirmed by rebuilding the instrumented image
with `-H:-PGOSampleStacks`, which makes all 267 pass. Documented in the README; the gate tolerates
exactly that class for the instrumented image and nothing else.

Setting this up found a trap in the `mx native-unittest` command itself: any argument, including
`--build-args`, counted as a "selector" and switched off the injection of the SVM test features, so
`mx native-unittest --build-args --pgo-instrument` built the default test group without its
features and 15 tests (`BootstrapMethodTest`, `ImageInfoTest`, `ForeignTests`, the service tests)
failed for that reason alone, with or without PGO. The command now recognizes selectors by shape.

## Validation standard

Each accepted iteration requires:

- focused parser, resolver, probability, serialization, and accounting tests;
- primary checkstyle;
- full Native Image distribution build;
- tiny instrument, dump, consume, and functional smoke test;
- matched application build;
- interleaved runtime measurements;
- source, profile, input, and binary provenance outside public documentation.
