# Conditional PGO architecture

## Scope

The implementation provides a Native Image CE producer and consumer for conditional branch counts.
It reuses the existing public PGO application infrastructure rather than introducing an independent
optimization pipeline.

## Consumer

### Parsing and resolution

`IprofConditionalParser` reads the iprof 1.0.0/1.1.0 subset described in
[ProfileFormat.md](ProfileFormat.md). It validates the required type and method tables, contexts, and
conditional record triples.

`ConditionalProfileContextResolver` converts profile methods and hosted methods to one canonical JVM
descriptor representation. Profile entries are resolved against `HostedUniverse` before compilation.

`SimpleConditionalProfilesLookup` implements `PGOProfilesLookup` for conditional data only. Every
other profile category remains absent. When `ceConditionalProfilesV2` is present, exact site identity
has priority. If an exact stage-qualified site is absent, the lookup may adopt the legacy context only
when that context maps to one precise site; ambiguous contexts never fall back. Fingerprint drift is
reported but does not reject an otherwise exact identity.

### Early consumption

`--pgo=<file>` installs the lookup as the `PGOProfilesLookup` image singleton before compile-queue
creation.

Profiles are applied at two points:

1. A reusable HighTier wrapper creates a fresh `PGOApplyProfilesPhase` for each root graph and runs it
   immediately before `AbstractInliningPhase`.
2. The existing priority-inliner expansion path reads the same singleton while decoding cutoff
   graphs and extends each node context with the explicit caller position.

A fresh apply phase is required because `PGOApplyProfilesPhase` is a `SingleRunSubphase` and cannot be
shared across parallel graph compilations.

### Post-inlining consumption

`--pgo-post-inlining=<file>` creates an independent lookup that is not installed as the inliner
singleton. Its apply wrapper is appended to hosted HighTier.

Both consumer options can be specified. Their parsing, resolution, lookup counters, and application
accounting remain separate.

### Application accounting

`PGOApplyProfilesPhase` reports how many profiled successor probabilities exist and how many matched
successors in the current graph. `SimpleConditionalProfilesLookup` classifies each distinct resolved
context as:

- fully applied;
- partially applied;
- matched but not applied;
- unused.

This distinguishes profile-key compatibility from actual graph mutation.

## Producer

### Instrumentation selection

Both producer modes use `ProfilingUtilities.relevantConditionalNodesFromGraph`. It excludes:

- implicit-exception conditionals;
- unknown or invalid BCIs;
- unsuitable nodes with compiler-injected probabilities.

The utility groups candidates by `NodeSourcePosition` and can return more than one node for one
position. This behavior is central to the known branch-identity limitation.

### Post-inlining mode

`--pgo-instrument` appends `BranchProfileInstrumentationPhase` to hosted HighTier. It records branches
that survive HighTier inlining and optimization. This mode does not place counters in the graph while
priority inlining decisions are made.

### Consumer-aligned mode

`--pgo-instrument-aligned` uses two hooks:

1. A root instrumentation phase immediately before `AbstractInliningPhase`.
2. `SubstratePriorityInliningPhase.createGraph`, adjacent to profile application, with the exact
   `replaceePosition` caller context.

Both aligned instrumentation and the consumer construct full contexts with
`PGOApplyProfilesPhase.createPointContext`.

### Runtime counters

`DynamicCounterNode` cannot be used because the Native Image AArch64 backend does not implement its
benchmark-counter LIR operation.

The producer uses:

- `BranchProfileCounterNode`, lowered through an SVM snippet template;
- one physical counter for every selected graph site;
- integer indexes embedded in generated code;
- a private native counter block per thread, plus shared global counters that exited threads fold
  into;
- immutable metadata containing stage, context, successors, condition kind, structural fingerprint,
  and occurrence ordinal.

Identical precise identities are aggregated only while serializing. Conflicting same-context sites
remain separate in `ceConditionalProfilesV2`; ambiguous contexts are omitted from legacy output.

Embedding hosted-created counter objects directly produced unrelocated compressed image-heap offsets.
The indexed array avoids late object constants in generated code.

#### Per-thread counter blocks

The first producer incremented a shared image-heap `long[]` directly. That was cheap in isolation
(five instructions per successor on AArch64, no bounds check) but every core wrote the same cache
lines, so parallel workloads slowed 16–21x and racing non-atomic increments silently lost most events.

`BranchProfileThreadCounters` is a `ThreadListener` that owns a `FastThreadLocalWord<Pointer>`:

- `afterThreadAttach` (a new listener callback invoked at the end of `VMThreads.attachThread`,
  before the thread can run any Java code or even has a `Thread` object) callocs a block of
  `2 * sites` longs and stores its address in the thread-local. The site count is sealed in
  `afterCompilation` and kept in an `AfterCompilation` primitive field, so blocks are exactly sized;
  untouched pages stay unmapped. If calloc fails the thread-local points at the shared counters.
- Generated code loads the thread-local pointer and increments `block[slot]` with a private
  `LocationIdentity`, so the load is hoisted out of loops. There is no null check and no base
  arithmetic: the per-site code is the same load/add/store with a constant offset as before.
- `afterThreadExit` folds the block into the shared counters with atomic adds and frees it.
- The shared counters are a zero-initialized `CGlobalData` byte region sized after compilation
  (replacing the fixed 15.3 MiB image-heap array; instrumentation images shrank 14–20 MB).
- Dumping takes a snapshot: the shared counters plus, in a safepoint `JavaVMOperation`, every live
  thread's block. Serialization reads the frozen snapshot, so profile-writing code is not counted.

Increments within a block are single-writer, so per-thread counts are exact; only the exit-time
merge needs atomics. Overhead is flat in thread count and single-threaded training is no slower than
the shared-array producer (see ExperimentsAndDecisions).

Why the attach callback: the thread-start listener runs after a foreign thread has constructed its
`Thread` object, and that constructor is instrumented JDK code. Without a block at that point the
alternatives were a null check per site (measured +5.4% training time) or a displacement add per
site (+4.2%). The attach callback is the only point that precedes every Java execution on a thread.

### Reachability and initialization constraints

Compilation-created metadata fields use `@UnknownObjectField` and `@UnknownPrimitiveField` with
`AfterCompilation` availability so static analysis does not constant-fold placeholder values.

Low-level Native Image runtime packages are excluded from heap-backed instrumentation because some
methods execute before the image-heap base is initialized.

### Dumping

A runtime teardown hook writes the deterministic conditional-only iprof file selected by:

```text
-XX:ProfilesDumpFile=<path>
```

The default path is `default.iprof`. Never-executed contexts are omitted. Counts are frozen in a
snapshot before serialization begins.

## Build lifecycle

The important lifecycle order is:

1. Parse profile after option registration.
2. Resolve contexts in `beforeCompilation`, once `HostedUniverse` exists.
3. Install the early lookup before compile-queue/inliner construction.
4. Register root, aligned, and post-inlining graph phases through `registerGraalPhases`.
5. Compile graphs in parallel.
6. Report lookup and successor-application accounting in `afterCompilation`.
7. For instrumentation images, dump counts from the runtime teardown hook.

## Packaging caveat

An incremental build can update project class directories while leaving the generated GraalVM
`lib/svm/builder/svm.jar` stale. When changing hosted integration, verify the packaged class or use a
forced build before evaluating Native Image behavior.
