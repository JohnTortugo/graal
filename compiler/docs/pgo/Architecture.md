# Experimental CE PGO architecture

## Scope

The implementation provides Native Image CE producers and consumers for conditional branch counts
and sampled Java call stacks. It reuses the existing public PGO application and priority-inlining
infrastructure rather than introducing an independent optimization pipeline. The consumer also
accepts external receiver-type profiles.

## Consumer

### Parsing and resolution

`IprofConditionalParser` reads the relevant iprof 1.0.0/1.1.0 categories. It validates type and method
tables, conditional record triples, receiver profiles, and stack contexts.

`ConditionalProfileContextResolver` converts profile methods and hosted methods to one canonical JVM
descriptor representation. Profile entries are resolved against `HostedUniverse` before compilation.
Hidden-class binary names use a slash (`Foo$$Lambda/0x...`), while JVMCI descriptors render that
separator as a dot; writer and resolver convert explicitly between the two forms.

`SimpleConditionalProfilesLookup` implements `PGOProfilesLookup`. For conditional data, exact
`ceConditionalProfilesV2` identity has priority. If an exact stage-qualified site is absent, lookup
may use an unambiguous legacy context and may progressively drop outermost caller frames. Ambiguous
successor shapes never fall back. Fingerprint drift is telemetry and does not reject an otherwise
exact identity.

### Early and post-inlining consumption

`--pgo=<file>` installs the lookup singleton before compile-queue creation. Profiles are applied at
two points:

1. A reusable HighTier wrapper creates a fresh `PGOApplyProfilesPhase` for each root graph and runs it
   immediately before `AbstractInliningPhase`.
2. The priority-inliner expansion path reads the same singleton while decoding cutoff graphs and
   extends each node context with the explicit caller position.

A fresh apply phase is required because `PGOApplyProfilesPhase` is a `SingleRunSubphase` and cannot be
shared across parallel graph compilations.

`--pgo-post-inlining=<file>` creates an independent lookup that is not installed as the inliner
singleton. Its apply wrapper is appended to hosted HighTier. Both consumer options can be specified;
their parsing, resolution, counters, and application accounting remain separate.

### Sampling hotness

`SamplingHotness` turns resolved `samplingProfiles` into a method-rooted `PrefixTree`. For every frame
of every sampled stack it adds the suffix beginning at that frame, so each context-insensitive
compilation root gets a merged calling-context tree below that method. This supplies:

- a cursor for root-relative callee hotness;
- sampled callee method profiles at indirect calls;
- a graph `GlobalProfileProvider` reporting whether the root was sampled and its global self-time
  share.

`SamplingInliningProvider` installs those values in the priority inliner. Each call-tree node carries
both inclusive samples and root-relative hotness. The prior smooth bonus scales continuously with
hotness. A second, confidence-gated policy selects a context only when the profile has at least 5,000
samples, the context has at least 50 samples, and it covers at least 5% of the root; selected contexts
receive expansion priority +5 and local-benefit multiplier +1. Normal call-tree budgets still cap
code growth. All thresholds and bonuses are validated hosted options.

This specializes selected caller contexts through inlining. It does not create separate AOT method
variants: although `PrefixTree` retains hot/cold-root state APIs, the public compile queue has no
analysis-time variant creation and call-target redirection for context-specific roots.

Method-profile, hot-caller, self-time, apply-while-expanding, smooth-bonus, and selected-context
mechanisms remain independently option-gated.

A sampled callee count is **not** a receiver dispatch count: sampling weights time anywhere below a
callee, so a rarely dispatched long-running receiver can outrank a frequently dispatched tiny one.
Consequently, receiver-based hot-callee devirtualization requires a dynamic receiver type profile at
the call site. Sampling-only method profiles can guide the regular cost-benefit inliner but cannot
select receiver guards.

### Application accounting

`PGOApplyProfilesPhase` reports how many profiled successor probabilities exist and how many match
successors in the current graph. `SimpleConditionalProfilesLookup` classifies each resolved
conditional context as fully applied, partially applied, matched but not applied, or unused. Sampling
telemetry reports complete, truncated, unresolved, and dropped stacks and hot/cold compilation roots.
This distinguishes profile-key compatibility from actual graph mutation.

## Conditional producer

### Instrumentation modes

`--pgo-instrument` appends `BranchProfileInstrumentationPhase` to hosted HighTier. It records branches
that survive HighTier inlining and optimization. This mode does not place counters in the graph while
priority-inlining decisions are made.

`--pgo-instrument-aligned` instruments roots immediately before `AbstractInliningPhase` and instruments
callee graphs in `SubstratePriorityInliningPhase.createGraph`, adjacent to profile application, using
the exact caller context. Both modes use `PGOApplyProfilesPhase.createPointContext`.

### Site selection and identity

Every `IfNode` with a source position is instrumented except implicit-exception branches and positions
with unknown BCIs. Multiple physical copies of one bytecode branch (for example, a peeled loop guard
and in-loop exit) each get a counter and precise identity. The legacy context entry is their sum only
when they route to the same successor BCIs, with negated conditions normalized. Rewired copies remain
ambiguous and are omitted from legacy output.

Each precise identity includes stage, full method/BCI context, ordered successor BCIs, condition kind,
a structural fingerprint, and an occurrence ordinal. Identical precise identities are aggregated only
while serializing.

### Runtime counters

`DynamicCounterNode` cannot be used because the Native Image AArch64 backend does not implement its
benchmark-counter LIR operation. The producer instead uses `BranchProfileCounterNode`, lowered through
an SVM snippet, with integer indexes embedded in generated code.

Each attached thread owns a private native counter block addressed by a `FastThreadLocalWord<Pointer>`:

- `afterThreadAttach`, at the end of `VMThreads.attachThread` and before any Java execution, allocates
  `2 * sites` longs. If allocation fails, the pointer uses shared counters.
- Generated code loads the thread-local pointer and performs a non-atomic load/add/store. The block is
  single-writer, so counts are exact and cores do not contend on cache lines.
- `afterThreadExit` atomically folds the block into shared `CGlobalData` counters and frees it.
- Dumping snapshots shared counters plus every live thread's block in a safepoint VM operation.

The site count and compilation-created metadata use `AfterCompilation` field availability so static
analysis does not fold placeholder values. Low-level runtime packages that can execute before the
image-heap base exists are excluded from heap-backed instrumentation.

## Stack-sampling producer

`StackSampleRecorder` registers a recurring callback for each Java thread. The period is selected at
run time by `-XX:PGOSamplingIntervalMillis` and defaults to 10 milliseconds.

The callback may interrupt code under a no-allocation contract. It therefore walks the current Java
stack from the interrupted return address and inserts the raw instruction-pointer chain into a
per-thread native open-addressed table using primitive operations only. It does not allocate Java
objects and does not update shared hot-path counters. Tables have fixed bucket and address-arena
capacities; full tables report dropped samples instead of allocating.

Per-thread tables are linked through a `CGlobalData` list. At teardown, after sampling stops, the
dumper decodes instruction pointers through image code info, expands inlined frames, maps each frame
to method/BCI identity, merges equal stacks, and emits `samplingProfiles`. `CodeInfoEncoder` includes
method signatures and modifiers whenever the sampler is registered, even without JFR, because stack
profile identities require signatures.

## Dumping

A runtime teardown hook writes one deterministic iprof selected by:

```text
-XX:ProfilesDumpFile=<path>
```

The default is `default.iprof`. Conditional counts are frozen before serialization; never-executed
conditional contexts are omitted. Sampling stops before per-thread native tables are decoded. The
output contains method/type tables shared by conditional, receiver, and sampling categories.

## Build lifecycle

The important lifecycle order is:

1. Parse profile after option registration.
2. Resolve contexts in `beforeCompilation`, once `HostedUniverse` exists.
3. Install the early lookup before compile-queue/inliner construction.
4. Register root, aligned, and post-inlining graph phases through `registerGraalPhases`.
5. For instrumentation builds, register branch metadata and stack sampling and encode required frame
   metadata.
6. Compile graphs in parallel.
7. Report lookup, successor-application, and sampling-hotness accounting in `afterCompilation`.
8. At instrumented-image teardown, stop sampling, snapshot counters, decode stacks, and serialize one
   iprof.

## Packaging caveat

An incremental build can update project class directories while leaving the generated GraalVM
`lib/svm/builder/svm.jar` stale. When changing hosted integration, verify the packaged class or use a
forced build before evaluating Native Image behavior.
