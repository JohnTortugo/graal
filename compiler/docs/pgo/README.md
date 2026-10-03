# Experimental PGO for Native Image CE

This directory documents the experimental profile-guided optimization (PGO) producer and consumer
implemented in public GraalVM Community Edition Native Image.

The producer records four iprof categories in one training image:

- `conditionalProfiles` / `ceConditionalProfilesV2` for `IfNode` branches;
- `samplingProfiles` from recurring per-thread Java stack samples;
- `virtualInvokeProfiles` with concrete receiver frequencies at indirect calls remaining after
  priority inlining;
- `callCountProfiles` with exact invocation counts for direct/monomorphic edges remaining after
  priority inlining.

Call counts, monitor, `instanceof`, switch, code-layout, and image-heap profiles are outside the
current scope.

> [!WARNING]
> This implementation is experimental. Precise CE conditional profiles use stage, context, successor
> signature, condition kind, and occurrence to distinguish transformed graph sites. Legacy external
> profiles remain context-only and cannot distinguish every transformed branch. Sampling-based
> method profiles measure time below a callee, not receiver dispatch frequency, and therefore never
> select receiver guards without a separate dynamic receiver type profile. See
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

The training image records branch counters and recurring stack samples. The sample period defaults
to 10 milliseconds and can be changed at run time:

```bash
./app-instrumented \
    -XX:PGOSamplingIntervalMillis=20 \
    -XX:ProfilesDumpFile=app.iprof \
    <training-arguments>
```

Build the optimized image:

```bash
native-image --pgo=app.iprof -cp <classpath> <main-class> -o app-pgo
```

Several training runs — different inputs, different lengths, several processes — are combined by
listing their files; an optional `:weight` scales a file's counts, as `llvm-profdata merge
--weighted-input` and `gcov-tool merge -w` do. Without weights a longer run weighs more:

```bash
native-image --pgo=text.iprof,binary-format.iprof:4 -cp <classpath> <main-class> -o app-pgo
```

Type and method ids are remapped by identity and the counts of identical contexts are added, so
only files from the same instrumented image (or images whose contexts still resolve) merge
usefully; unresolved contexts are reported and dropped as for a single file.

To collect from many runs or processes without an extra step, let every run write its own file
(`%p` is the process id, `%t` the time in milliseconds) and pass the directory:

```bash
./app-instrumented -XX:ProfilesDumpFile=profiles/run-%p-%t.iprof <input A>
./app-instrumented -XX:ProfilesDumpFile=profiles/run-%p-%t.iprof <input B>
native-image --pgo=profiles/ -cp <classpath> <main-class> -o app-pgo
```

`--pgo` applies conditional profiles to root graphs before priority inlining and to decoded
priority-inliner expansion graphs. Sampling profiles provide root-relative call-tree hotness,
sampled callee method profiles, and global hot-caller/self-time data.

## Experimental stage modes

Post-inlining producer (current performance baseline):

```bash
native-image --pgo-instrument ...
```

Consumer-aligned conditional producer:

```bash
native-image --pgo-instrument-aligned ...
```

The aligned producer instruments root graphs immediately before priority inlining and instruments
callee graphs while the priority inliner expands them. Both producer modes emit stack samples.

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

## Sampling controls

The sampling consumer mechanisms remain independently gateable for experiments:

```text
-H:PGOHotExpansionBonus=<n>            smooth expansion priority bonus (default 0)
-H:PGOHotInliningBonus=<n>             smooth inlining-benefit bonus (default 1)
-H:PGOHotContextMinProfileSamples=<n>  minimum profile confidence (default 5000)
-H:PGOHotContextMinSamples=<n>         minimum inclusive context samples (default 50)
-H:PGOHotContextMinRatio=<share>       minimum root-relative context share (default 0.05)
-H:PGOHotContextExpansionBonus=<n>     selected-context expansion priority (default 5)
-H:PGOHotContextInliningBonus=<n>      selected-context benefit multiplier (default 1)
-H:-PGOApplyProfilesWhileExpanding     do not apply profiles to hot expanded graphs
-H:-PGOSamplingMethodProfiles          do not prefer sampled callee method profiles
-H:-PGOSamplingHotCaller               do not mark sampled roots as hot callers
-H:-PGOSamplingSelfTime                do not expose sampled self time
-H:-PGOProfileReceivers               do not instrument or emit receiver frequencies
-H:-PGOUseReceiverProfiles            parse but do not apply receiver profiles
-H:-PGOProfileCallCounts              do not instrument or emit exact call-edge counts
-H:-PGOUseCallCounts                  parse but do not expose call counts to optimizations
-H:-PGOProfileSwitches                do not instrument switch successors
-H:-PGOUseSwitchProfiles              do not apply switch successor probabilities
-H:-PGOUseCodeLayout                  retain deterministic method-name code order
-H:PGOHotLeafMaxCodeSize=<bytes>       profile-driven force-inlining of small hot leaves (default 0 = off)
-H:PGOHotLeafMinFrequency=<n>          minimum call frequency for hot-leaf absorption
-H:PGOHotLeafMinSharedCalls=<n>        require values shared by this many calls
-H:PGOHotRootInliningBoost=<k>         inlining budget multiplier for sampled-hot roots (default 4.0; 1 = off)
-H:PGOHotRootMinInclusiveShare=<share> inclusive sample share a root needs for the boost (default 0.002)
```

Two optimizations added during this work are not profile specific but are gated by frequencies
that only a profile makes trustworthy:

```text
-H:-TrustFinalInstanceFields           do not treat closed-world final instance fields as constants after construction
-H:-LoopRangeCheckVersioning           do not version hot counted loops to remove body range checks
-H:-LoopRangeCheckVersioningRequireProfile  version loops with default (unprofiled) frequencies too
```

Receiver-based hot-callee devirtualization additionally requires a dynamic receiver type profile.
Stack samples alone cannot safely choose receiver guards because they measure time, not dispatch
frequency.

## Diagnostics

Profile resolution is reported before compilation. After compilation, each active consumer channel
reports:

- compiler queries, hits, and misses;
- distinct resolved contexts used and unused;
- contexts whose successor probabilities were fully applied;
- partially applied contexts;
- matched contexts for which no successor probability was applied;
- a prior-comparison table: applied sites bucketed by recorded event count, split into those whose
  dominant successor agrees with the probability the node already had and those that contradict it,
  with contradictions of compiler-injected probabilities called out separately;
- resolved, truncated, unresolved, and dropped stack-sample counts, plus hot/cold compilation roots.

A lookup hit alone is not treated as proof that profile records were applied.

## Context fallback

When a conditional site's full inlining context has no profile entry, the consumer falls back to the
same branch under progressively shorter contexts (outermost callers dropped). This covers callees that
the profile changed inlining for. `-H:-PGOContextFallback` disables it; the number of such fallbacks
is reported per stage.

## Usefulness filter (opt-in)

Two hosted options let a matched site keep its static probability when the profile says little:

```text
-H:PGOConditionalMinEvents=<n>      withhold sites with fewer than n recorded events
-H:PGOConditionalMinBias=<share>    withhold sites whose dominant successor share is below <share>
```

Both default to off. Withheld sites are reported separately from misses. See
[Experiments and decisions](ExperimentsAndDecisions.md) for why the defaults are off.

## Branch identity

CE-produced profiles contain a `ceConditionalProfilesV2` section. Its site identity combines stage,
full method/BCI context, ordered successor BCIs, condition kind, and an occurrence ordinal. A
structural fingerprint is retained as advisory drift telemetry rather than a hard key.

The producer gives each selected physical graph site its own counter and aggregates only identical
v2 identities at dump time. The legacy `conditionalProfiles` entry sums physical copies only when
they route to the same successor BCIs; ambiguous contexts are omitted.

The consumer tries exact v2 identity first. If the requested stage does not match, it may use the
legacy context only when that context maps to exactly one unambiguous successor shape. If a full
context is absent, it can try progressively shorter contexts. Graph-local node IDs are not used
because they are not stable across builds.

External legacy profiles remain context-only and retain the original ambiguity risk.

## mx benchmark integration

The existing Native Image PGO configuration drives the CE pipeline:

```bash
mx benchmark renaissance-native-image:<benchmark> -- \
    --jvm=native-image --jvm-config=pgo -- <benchmark-arguments>
```

It builds an instrumented image, runs one training iteration, builds the final image with `--pgo`,
and runs the requested evaluation iterations. One profile carries both conditional and stack-sampling
data.

## Validation

Run the focused PGO tests from the `substratevm` suite:

```bash
mx unittest com.oracle.svm.hosted.pgo com.oracle.svm.core.pgo
```

Also run:

```bash
mx checkstyle --primary
mx build
```
