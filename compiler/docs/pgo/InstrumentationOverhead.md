# Instrumentation overhead: finding, fix, and project progress

This note records the investigation into the training-time overhead of the CE conditional-profile
producer, the fix that was adopted, and where the project stands afterwards. It is a narrative
companion to the reference material in [Architecture](Architecture.md) and the decision log in
[Experiments and decisions](ExperimentsAndDecisions.md).

## The symptom

Validating the producer on public `mx benchmark` Native Image pipelines showed that instrumented
images were unusably slow on parallel workloads: about 1.2x for a sequential benchmark, 4x for a
moderately threaded one, and 16–21x for two parallel numeric workloads (Renaissance fj-kmeans,
DaCapo sunflow). Instrumented binaries were also 2.3–3x the size of the optimized image.

## Finding 1: the cost was contention, not the increment

A microbenchmark isolated the producer: a hot loop with five data-dependent branches per iteration,
identical work per thread, run with 1 to 64 threads. Disassembly of the instrumented kernel showed
that the per-successor code was already minimal — a constant offset (`mov/movk`) and a
load/add/store into the shared counter array, no bounds check, no call. Yet:

| threads | uninstrumented | instrumented | slowdown |
|---:|---:|---:|---:|
| 1 | 90.7 ms | 104.2 ms | 1.15x |
| 4 | 90.5 | 166.0 | 1.83x |
| 8 | 91.1 | 409.0 | 4.5x |
| 16 | 91.2 | 1008.3 | 11.1x |
| 64 | 92.4 | 4459.3 | 48x |

Single-threaded cost was 15% on a branch-dense loop. Everything above that was cores fighting over
the same cache lines of one shared `long[]`.

## Finding 2: the shared counters were wrong under concurrency

The increments were deliberately non-atomic ("profiles are approximate"). Under contention that
approximation was not small. The 4-thread microbenchmark should record exactly 3,276,800 events per
kernel branch; the shared array recorded 843,674 — 74% lost. The fj-kmeans and sunflow training
profiles had lost 72–79% of their events. Dominant-successor decisions still agreed 99.6–99.7% with
correct profiles because both sides of a branch lost events at similar rates, but absolute counts,
which any usefulness or confidence filter must rely on, were meaningless for parallel training.

## The fix: one private counter block per thread

Each attached thread owns a calloc'd native block of `2 × sites` longs, addressed through a
`FastThreadLocalWord`. Instrumented code increments its own block; nothing is shared on the hot
path. On thread exit the block is folded into a shared accumulator with atomic adds and freed. When
the profile is dumped, a safepoint VM operation adds the blocks of still-running threads to a
snapshot of the accumulator, and the serializer reads that frozen snapshot.

Getting the hot path right took four variants, each measured on the same fixed single-worker
application training run (shared-array baseline: 183.7 s):

| Variant | Per-site code | Training | Outcome |
|---|---|---:|---|
| block pointer + null check, shared fallback | load TL, `cbz`, load/add/store | +5.4% | correct but the branch diamond at every site is expensive in call-heavy code |
| block pointer, no null check | load TL, load/add/store | 0% | crashed a public benchmark: a foreign thread constructs its `Thread` object — instrumented JDK code — before the thread-start listener that allocates the block |
| displacement from a shared base, no check | load TL, `add`, load/add/store | +4.2% | safe and branch-free; the add on the address path still costs |
| block allocated at thread attach (adopted) | load TL, load/add/store | −1.8% | needs a listener callback at the end of `VMThreads.attachThread`, the only point that precedes every Java execution on a thread |

Two supporting changes fell out of the work:

- The shared accumulator is now a `CGlobalData` byte region sized after compilation rather than a
  fixed one-million-site image-heap array. Instrumentation images shrank by 14–20 MB.
- Counts are snapshotted before serialization, so the producer no longer records a few hundred
  one-event sites from its own file-writing code.

Two pitfalls worth recording: `Word.objectToUntrackedPointer(constant)` is a fixed node per site,
so address arithmetic built on it does not GVN across sites (a `CGlobalData` address is a floating
constant and does); and `Unsafe.ARRAY_*_BASE_OFFSET` evaluated in a build-time initializer carries
the HotSpot layout, not the Native Image one.

## Results

| Workload | Before | After |
|---|---:|---:|
| microbenchmark, 1 / 16 / 64 threads | 1.15x / 10.6x / 48x | 1.14x / 1.15x / 1.22x |
| fj-kmeans instrument-run | 21x | 1.15x |
| sunflow instrument-run | 16x | 1.19x |
| application training, single worker | 1.41x | 1.38x |
| 4-thread microbenchmark count fidelity | 26% of events | 100% |

Instrument-run peak RSS is at parity with the uninstrumented run. Profile content is unchanged in
character: on the fixed application workload the new producer's event total matched the old one
within 0.012% and the resulting PGO image was performance-neutral versus the previous profile.

## Project progress

| Milestone | Status | Key result |
|---|---|---|
| 1. Consume external `conditionalProfiles` | done | context-exact matching; small gain on the fixed workload |
| 2. CE producer (post-inlining instrumentation) | done | self-produced profile: −13.9% time on the fixed workload |
| 2b. Collection-timing and stage-qualified consumption experiments | done | better matching did not mean better speed; post-inlining data applied early remained best |
| 3. Branch identity v2 | done | fixed context-only counter collisions; −15.6% time, +18.5% throughput vs no PGO on the fixed workload |
| 3b. Public mx benchmark validation | done | no robust general win (−0.5% to +0.7%); v2 safety confirmed across unrelated apps; overhead problem exposed |
| 4a. Per-thread counters | done | parallel overhead 16–21x → 1.15–1.19x; exact counts; smaller instrument images |
| 4b. Profile usefulness / confidence filter | implemented, opt-in | low-count sites carry most prior contradictions; −1.8% suggestive on the fixed workload; tuning deferred to a separate phase-behaviour investigation |
| 5. Receiver-type profiles, consumer | done (consumer only) | stock path −1.3% regression traced to lost closed-world exactness; fixed → −0.4%; producer deferred (low ceiling) |
| 6. Sampling / hotness profiles, consumer | done (consumer only) | plumbing neutral by default; external samples too mismatched to evaluate; naive inlining bonus +5% regression; needs a same-build CE sampler |
| 7. Loop-header profiling + shortened-context fallback | done | two producer/consumer defects fixed; −8.8% on the fixed workload (now −21% vs no-PGO, 16.5% behind commercial PGO); public suites at noise |
| 8. Same-build stack sampler | done | allocation-free per-thread sampling; −2.4% beyond milestone 7; invalid sample-time receiver guards fixed; scala-doku +9.2% regression → +1.2% |
| 9. Confidence-gated hot-context inlining | done | inclusive count + root-share selection; −2.4% beyond milestone 8; now −26.8% vs no-PGO and 8.3% behind commercial PGO |
| 10. Receiver-frequency producer | retained, independently gated | safe producer recorded 1.568B events with 3.8% training overhead; isolated runtime +0.27%; kept for polymorphic workloads |
| 11. Exact post-inlining call edges | retained, independently gated | 27.22B attributed events, zero drops; isolated runtime −0.20%; public suites differ; producer/consumer separately disableable |
| 12. Switch successor profiles | retained, independently gated | complete atomic distributions; 103 active contexts/~719M events; +0.20% on fixed workload, image +64 KiB |

Standing caveats: conditional instrumentation covers `IfNode` branches but not switches; sampled
callee counts measure time rather than receiver dispatch frequency, and recurring-callback leaf
samples favor code that can service callbacks. The application result is same-input specialization
until a representative held-out input is measured. The fixed workload is now 26.8% below no-PGO and
8.3% above the timing-only commercial PGO reference.
