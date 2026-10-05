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

The producer's stack sampler (`-H:±PGOSampleStacks`, default on in instrumented images) takes a
stack at the thread's next safepoint check through the per-thread recurring callback. Two
consequences: a leaf method without a safepoint check (a counted loop, straight-line code) is never
sampled itself and its time is attributed to the caller frame that polls next; and JFR's
recurring-callback execution sampler, which uses the same per-thread slot, records no samples in
an instrumented image. Build the instrumented image with `-H:-PGOSampleStacks` when JFR execution
sampling of it matters (sampling-based hotness is then unavailable).

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
-H:PGOHotSmallRootInliningBoost=<k>    larger boost for hot roots at or below the typical graph size, decaying to the base boost (default 1 = off; experimental)
-H:-PGOReceiverContextFallback         receiver profiles: do not fall back to the same call under a shorter inlining context
-H:+PGOConditionalFlowCheck            withdraw branch records that cover far fewer executions than the graph implies (default off; experimental)
-H:-PGOInlineIntrinsicsInHotRoots      call the shared array/string intrinsic stubs in hot roots too (default: emit inline)
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

The same rule applies to receiver profiles (`virtualInvokeProfiles`): the instrumented image records a
call's receivers under its own inlining decisions, which cannot include the sampling-driven hot-root
budget the optimized image uses, so a callee the instrumented image compiled standalone is inlined
several frames deep in the optimized image and would otherwise never find its receiver profile. In a
binary-format reader workload this left the hottest interface calls (monomorphic in the profile)
indirect and turned two-type static profiles into 0.5/0.5 type switches: 793 of 115 061 receiver
queries hit; with the fallback 21 761 hit and the calls became guarded direct calls. Measured on the
same build, option off/on: binary-format input −2.0%, text input −1.4%, image +0.36%, scala-doku
within noise. `-H:-PGOReceiverContextFallback` disables it; the build summary reports how many
receiver hits used a shortened context.

## Profile shape and the instrumentation stage

A conditional record is a per-successor count of one `IfNode` as it existed in the instrumented
image's graph. `--pgo-instrument` records after hosted high tier, i.e. after inlining and
canonicalization. When the instrumented graph inlined a call before a branch and `IfNode`
canonicalization split the branch into the inlined paths, the record only covers the executions left
on the residual branch. Applied to a graph where that call is *not* inlined — the callee graph the
priority inliner expands, or a root before inlining — such a record under-counts the branch by orders
of magnitude. In the binary-format reader this made a loop whose real exit lives in an inlined
callee look like it never exits (record 0 : 60 against 3 000 061 : 60 in reality), the inliner
priced the loop's never-executed slow path at 100× the method's frequency, spent its budget there and
judged the reader's hot `nextValue()`/`stringValue()` chain not worth inlining: the standalone
reader of the format ran 1 232 M calls per pass against C2's 321 M.

`--pgo-instrument-aligned` records at the stages where the consumer applies profiles (root before
inlining, callee at expansion) and does not have this problem: the standalone reader drops to 447 M
calls and 6.6 s per pass (C2 6.4 s) with `-H:PGOHotRootInliningBoost=16`, and the full binary-format
workload improves 2.8% at the default boost. It is **not** the default because the same change costs
the text workload 4% (72.9 → 75.8 s): the inlining shape of its hot roots changes (`readLine` and
field validation stay out of the stream loop). Applying the post-high-tier profile additionally at
the end of high tier (`--pgo-post-inlining`) does not recover it (77.0 s). Choosing the stage per
workload is therefore still a user decision; the long-term fix is to make the aligned shape at least
as good on the text workload, after which it becomes the default.

`-H:+PGOConditionalFlowCheck` is a consumer-side mitigation for post-high-tier profiles: after
applying the records of a graph it rebuilds the control-flow frequencies and withdraws, one per round
and fewest-events first, any record whose event total is below `PGOConditionalFlowCheckRatio` (0.1)
of the executions the graph implies for its branch (the graph instance's profiled entry count times
the branch block's relative frequency; `PGOConditionalFlowCheckMinEvents` = 1000 guards small
counts). On the standalone reader it recovers part of the aligned result (returns 1 236 M → 847 M,
−4.8% time) but on the full agent it withdraws 5–16% of all applied records and costs 5%, so it is
off by default; the build summary reports how many records it checked and withdrew.

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

These cover the profile readers and writers, the context resolver, the receiver-profile
context fallback (`VirtualInvokeProfileResolutionTest`), the call-count lookup, and the
decision functions behind the experimental options (`FlowCheckSelectionTest`,
`HotRootBoostTest`), which are factored out as pure static methods for that purpose.

Then run the end-to-end gate, which builds images under hosted assertions and graph
verification (`-J-ea -J-esa -H:+VerifyGraalGraphs -H:+VerifyPhases`):

```bash
mx gate --tags pgo
```

The gate compiles `substratevm/src/native-image-tests/pgo/PgoWorkload.java`, a deterministic
program whose output depends on the mechanisms PGO acts on (a context-dependent interface
receiver, a monomorphic interface call in a sampled-hot loop, a loop whose exit sits in an
inlined helper, a small-method reader with a refill path, recursion, an exception-driven unwind
loop, fresh strings, a `HashMap` lookup, a string switch and `Number.doubleValue` on mixed
boxes) and prints what it counted (records, shapes of each kind), and then:

1. builds `--pgo-instrument`, `--pgo-instrument-aligned`, an instrumented image whose
   interface-call wrappers are compiled standalone (`-H:NeverInline=...`), and an instrumented
   image with the split histogram, and trains them;
2. builds the optimized variants: default, merged profiles (`a,b:2`), aligned profile, the
   shallow profile with and without `-H:-PGOReceiverContextFallback`,
   `-H:+PGOConditionalFlowCheck`, the small-root boost, `-H:PGOHotRootInliningBoost=16`, and
   `-H:-OptMethodDuplication`, and `-H:-UseGraphCache`;
3. checks every optimized build summary: zero unresolved conditional contexts, receiver profiles
   applied, the fallback engaged on the shallow profile (and only there when disabled), the flow
   check ran only when enabled;
4. runs every image on the two training inputs and on an input the profile never saw (with an
   odd record count, so the monomorphic site's guard fails and its fallback runs) and requires
   its output to be identical to the JDK's;
5. checks the *effects* of PGO on the compiled graphs. The instrumented, the unprofiled and the
   default profiled build dump the workload's graphs (`-H:Dump=:1 -H:MethodFilter=...`), and
   `PgoEffectsCheck.java` reads the dumps with the compiler's graph-file reader and verifies:
   - instrumented: every branch carries one counter per successor, every indirect call a receiver
     counter, every graph with direct calls a call-count marker;
   - unprofiled: no node carries a profiled probability, the interface call in the hot loop is an
     interface call;
   - profiled: every branch probability marked `PROFILED` equals, on the right successor, the
     count ratio the profile file records for that branch under the consumer's context lookup
     (exact chain, then outermost frames dropped), including the clamping of never-taken
     successors; the profile records exactly the counts the program printed (one end-of-input
     exit after `records` records, the square/triangle/circle split, the receivers of both
     interface call sites); the two shape-distribution branches and the end-of-input branch
     carry the program's own ratios; the monomorphic interface call is a guarded direct call to
     the recorded implementation with the interface call left only on a cold path; the string
     switch carries the recorded distribution.

Then run the corpus gate, which answers "does PGO break anything" on code that is not the
workload: the whole native unit test corpus (`mx native-unittest`, several hundred tests of
reflection, JNI, threads, JFR, serialization and so on) is built and run as a plain image, as a
`--pgo-instrument` image (which also trains), and as an image optimized with that profile:

```bash
mx gate --tags pgo_unittests
```

The instrumented and the optimized image may fail only tests the plain image fails too. One
documented exception is tolerated for the instrumented image: `TestVirtualThreadsExecutionSample`,
because the instrumentation's stack sampler occupies the per-thread recurring callback that
JFR's recurring-callback execution sampler needs (see "Sampling controls"). This gate takes
about 7 minutes. To reproduce a single step by hand:

```bash
mx native-unittest --build-args --pgo-instrument --run-args -XX:ProfilesDumpFile=/tmp/tests.iprof
mx native-unittest --build-args --pgo=/tmp/tests.iprof
```

Also run:

```bash
mx checkstyle --primary
mx build
```

## Rules the assertions enforce

The gate found, and the implementation now follows, these constraints:

- An instrumentation snippet may touch only locations it declares private, or locations its
  node kills. The branch counter, receiver and call-count snippets read a thread-local pointer
  and read/write their native tables under named locations (`PGOBranchCounters`,
  `PGOReceiverTables`, `PGOCallCountTables`), all declared private, and their nodes are not
  memory kills; the rare paths that need atomics (a thread without a table, a full table) run
  as foreign calls that kill only the table location. A node that kills `ANY` while its
  snippet contains no kill of `ANY` fails `SnippetTemplate`'s memory rewiring, because every
  floating read after the node would have to be re-attached to a kill inside the snippet.
- A snippet body cannot contain a hosted-only branch (`SubstrateUtil.HOSTED`) or anything that
  can throw; the hosted paths used by unit tests are separate methods.
- The priority inliner's graph cache shares one decoded graph per callee across all expansions
  in a compilation unit, so the profile applied to it is that of the callee's first (highest
  priority) expansion context. Upstream asserts the cache is off on this path; that assertion
  is replaced by a documented decision, because per-context application measured worse on the
  binary-format reader workload (`-H:-UseGraphCache`: +1.2% with the post-high-tier profile,
  +4.0% with the aligned profile, three interleaved runs each) — most deeper contexts have no
  record of their own and fall back to shortened contexts, whereas the shared graph carries the
  hottest context's profile. `-H:-UseGraphCache` selects per-context application and is one of
  the gate's variants.
