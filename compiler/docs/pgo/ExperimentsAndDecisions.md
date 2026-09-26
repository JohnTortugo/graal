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

## Validation standard

Each accepted iteration requires:

- focused parser, resolver, probability, serialization, and accounting tests;
- primary checkstyle;
- full Native Image distribution build;
- tiny instrument, dump, consume, and functional smoke test;
- matched application build;
- interleaved runtime measurements;
- source, profile, input, and binary provenance outside public documentation.
