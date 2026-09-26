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
- one shared, analysis-visible `long[]` for runtime counts;
- immutable metadata containing stage, context, successors, condition kind, structural fingerprint,
  and occurrence ordinal.

Identical precise identities are aggregated only while serializing. Conflicting same-context sites
remain separate in `ceConditionalProfilesV2`; ambiguous contexts are omitted from legacy output.

Embedding hosted-created counter objects directly produced unrelocated compressed image-heap offsets.
The indexed array avoids late object constants in generated code.

Counter increments are deliberately relaxed and non-atomic. This minimizes training overhead but can
lose increments under contention. Atomic behavior should be considered only after measuring whether
contention changes useful probabilities.

The current array reserves two counters for up to one million branch contexts. This is provisional
and adds approximately 15.3 MiB to an instrumentation image.

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

The default path is `default.iprof`. Never-executed contexts are omitted.

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
