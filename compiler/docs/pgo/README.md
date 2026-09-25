# Experimental conditional PGO for Native Image CE

This directory documents the experimental conditional-branch profile-guided optimization (PGO)
producer and consumer implemented in public GraalVM Community Edition Native Image.

The current implementation intentionally covers one profile category only: iprof
`conditionalProfiles` for `IfNode` branches. Call counts, virtual calls, sampling, monitor,
`instanceof`, switch, code-layout, and image-heap profiles are outside the current scope.

> [!WARNING]
> This implementation is experimental. Method/BCI calling context does not uniquely identify every
> transformed graph branch. The current producer can merge distinct branch sites that share a
> context. See [Known branch-identity limitation](#known-branch-identity-limitation) and
> [Experiments and decisions](ExperimentsAndDecisions.md).

## Documentation

- [Architecture](Architecture.md)
- [Conditional iprof subset](ProfileFormat.md)
- [Experiments, evidence, decisions, and limitations](ExperimentsAndDecisions.md)

## Basic workflow

Build an instrumented image:

```bash
native-image --pgo-instrument -cp <classpath> <main-class> -o app-instrumented
```

Run the training workload and choose the output file:

```bash
./app-instrumented -XX:ProfilesDumpFile=app.iprof <training-arguments>
```

Build the optimized image:

```bash
native-image --pgo=app.iprof -cp <classpath> <main-class> -o app-pgo
```

`--pgo` applies conditional profiles to root graphs before priority inlining and to decoded
priority-inliner expansion graphs.

## Experimental stage modes

Post-inlining producer (current performance baseline):

```bash
native-image --pgo-instrument ...
```

Consumer-aligned producer:

```bash
native-image --pgo-instrument-aligned ...
```

The aligned producer instruments root graphs immediately before priority inlining and instruments
callee graphs while the priority inliner expands them.

Consume a profile only at the end of hosted HighTier:

```bash
native-image --pgo-post-inlining=post.iprof ...
```

Use independent early and post-inlining channels:

```bash
native-image \
    --pgo=aligned.iprof \
    --pgo-post-inlining=post.iprof \
    ...
```

Instrumentation and consumption options are mutually exclusive in one image build. The two
instrumentation modes are also mutually exclusive.

## Diagnostics

Profile resolution is reported before compilation. After compilation, each active consumer channel
reports:

- compiler queries, hits, and misses;
- distinct resolved contexts used and unused;
- contexts whose successor probabilities were fully applied;
- partially applied contexts;
- matched contexts for which no successor probability was applied.

A lookup hit alone is not treated as proof that profile records were applied.

## Known branch-identity limitation

The legacy key is a method/BCI calling-context chain. Graal can retain several `ControlSplitNode`s
with the same `NodeSourcePosition`, including nodes with different successor BCIs. The current CE
producer stores one true/false successor pair per context. A later same-context site can therefore be
merged into the first site's counter.

The measured performance results are useful experimental evidence, but the producer should not be
considered semantically complete until branch identity includes stage, context, successor signature,
and a deterministic occurrence discriminator. The proposed direction is documented in
[ExperimentsAndDecisions.md](ExperimentsAndDecisions.md#branch-identity-v2-direction).

## Validation

The focused tests can be run from the `substratevm` suite:

```bash
mx unittest \
    com.oracle.svm.hosted.pgo.IprofConditionalParserTest \
    com.oracle.svm.hosted.pgo.ConditionalProbabilityMathTest \
    com.oracle.svm.hosted.pgo.profiles.ConditionalProfileContextResolverTest \
    com.oracle.svm.core.pgo.BranchProfileIprofWriterTest
```

Also run:

```bash
mx checkstyle --primary
mx build
```
