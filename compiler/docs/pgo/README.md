# Experimental conditional PGO for Native Image CE

This directory documents the experimental conditional-branch profile-guided optimization (PGO)
producer and consumer implemented in public GraalVM Community Edition Native Image.

The current implementation intentionally covers one profile category only: iprof
`conditionalProfiles` for `IfNode` branches. Call counts, virtual calls, sampling, monitor,
`instanceof`, switch, code-layout, and image-heap profiles are outside the current scope.

> [!WARNING]
> This implementation is experimental. Precise CE profiles use stage, context, successor signature,
> condition kind, and occurrence to distinguish transformed graph sites. Legacy external profiles
> remain context-only and cannot distinguish every transformed branch. See
> [Experiments and decisions](ExperimentsAndDecisions.md).

## Documentation

- [Architecture](Architecture.md)
- [Conditional iprof subset](ProfileFormat.md)
- [Experiments, evidence, decisions, and limitations](ExperimentsAndDecisions.md)
- [Instrumentation overhead: finding, fix, and progress](InstrumentationOverhead.md)

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

## Branch identity

CE-produced profiles contain a `ceConditionalProfilesV2` section. Its site identity combines stage,
full method/BCI context, ordered successor BCIs, condition kind, and an occurrence ordinal. A
structural fingerprint is retained as advisory drift telemetry rather than a hard key.

The producer gives each selected physical graph site its own counter and aggregates only identical
v2 identities at dump time. The legacy `conditionalProfiles` section includes only contexts that map
to one unambiguous v2 site.

The consumer tries exact v2 identity first. If the requested stage does not match, it may use the
legacy context only when that context maps to exactly one v2 site. Ambiguous contexts never fall back.
Graph-local node IDs are not used because they are not stable across builds.

External legacy profiles remain context-only and retain the original ambiguity risk.

## mx benchmark integration

The existing Native Image PGO configuration drives the CE conditional pipeline:

```bash
mx benchmark renaissance-native-image:<benchmark> -- \
    --jvm=native-image --jvm-config=pgo -- <benchmark-arguments>
```

It builds a post-inlining instrumented image, runs one training iteration, builds the final image with
`--pgo`, and runs the requested evaluation iterations. The harness accepts precise CE conditional
profiles without requiring an unrelated sampling section.

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
