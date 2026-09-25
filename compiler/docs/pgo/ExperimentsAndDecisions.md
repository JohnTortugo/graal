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

Decision: context-only counter deduplication is a known correctness defect when successor pairs
conflict. Current speedups must not be presented as proof of semantically complete PGO.

## Branch identity v2 direction

A precise site identity should include:

```text
stage
+ full method/BCI caller context
+ normalized successor-BCI signature
+ deterministic occurrence discriminator
```

For same-context sites with the same successor signature, evaluate a deterministic CFG traversal
ordinal. A condition-shape fingerprint can validate the match, but should not be the sole identity.
Graph-local node IDs are rejected because they are not stable across builds.

A future format should use a separate CE extension section while preserving legacy iprof
compatibility. The consumer should resolve entries to individual graph branches and skip ambiguous
legacy matches rather than using first-wins behavior.

Before adopting this design:

1. Prove discriminator stability across repeated builds.
2. Give each physical selected site its own counter.
3. Add tests for same-context/different-successor and same-successor/multiple-occurrence cases.
4. Retrain and benchmark as an isolated milestone.
5. Evaluate a representative held-out workload to test generalization.

## Validation standard

Each accepted iteration requires:

- focused parser, resolver, probability, serialization, and accounting tests;
- primary checkstyle;
- full Native Image distribution build;
- tiny instrument, dump, consume, and functional smoke test;
- matched application build;
- interleaved runtime measurements;
- source, profile, input, and binary provenance outside public documentation.
