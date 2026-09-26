# Conditional iprof subset

## Supported versions

The consumer accepts iprof versions:

```text
1.0.0
1.1.0
```

The CE producer emits version `1.1.0`.

## Sections

The conditional-only implementation reads and writes:

```json
{
  "version": "1.1.0",
  "types": [],
  "methods": [],
  "conditionalProfiles": []
}
```

`version`, `types`, and `methods` are required. `conditionalProfiles` is optional for input and is
empty when no conditional data is present. Other iprof categories are intentionally ignored.

## Type table

```json
{"id": 4, "name": "int"}
```

Type names can be primitive names, dotted class names, or array descriptors. The resolver converts
them to JVM descriptor form.

## Method table

```json
{
  "id": 17,
  "name": "classify",
  "signature": [100, 8, 4]
}
```

The signature array contains:

```text
[declaringTypeId, returnTypeId, parameterTypeId...]
```

The canonical method identity is:

```text
<declaring-class-descriptor>.<method-name>(<parameter-descriptors>)<return-descriptor>
```

For example:

```text
Lexample/Parser;.classify(I)Z
```

## Calling context

A context is an innermost-first chain:

```text
methodId:bci<callerMethodId:callerBci<...
```

Example:

```text
17:42<29:15<31:81
```

This means the conditional is at BCI 42 in method 17, which was called at BCI 15 in method 29,
which was called at BCI 81 in method 31.

Contexts may be partial. Exact matching is used; a context-insensitive or suffix fallback is not
applied automatically.

## Conditional records

Each entry contains a context and a flat array of triples:

```json
{
  "ctx": "17:42<29:15",
  "records": [50, 0, 9900, 70, 1, 100]
}
```

Each triple is:

```text
[successorBci, branchIndex, count]
```

The array length must be divisible by three.

For the current CE `IfNode` producer, every emitted entry has two records:

```text
[trueSuccessorBci, 0, trueCount,
 falseSuccessorBci, 1, falseCount]
```

The general format is variable-cardinality and can represent more records, including switch cases and
shared targets. Switch instrumentation is not implemented by the CE producer yet.

## Determinism

The producer:

- sorts active contexts deterministically;
- assigns type IDs lexicographically;
- assigns method IDs lexicographically;
- emits exact context chains;
- omits contexts with no runtime events.

## Strict validation

The parser rejects:

- unsupported versions;
- missing required sections;
- duplicate type or method IDs;
- empty type names;
- malformed context frames;
- non-array conditional sections;
- record arrays whose lengths are not multiples of three.

## Precise CE branch-site extension

CE-produced files also contain:

```json
"ceConditionalProfilesV2": [
  {
    "stage": "POST_HIGH_TIER",
    "ctx": "17:42<29:15",
    "successors": [50, 70],
    "conditionKind": "jdk.graal.compiler.nodes.calc.IntegerEqualsNode",
    "conditionFingerprint": "8f3a2c1d",
    "occurrence": 0,
    "records": [50, 0, 9900, 70, 1, 100]
  }
]
```

The exact identity is:

```text
stage + context + ordered successor BCIs + condition kind + occurrence
```

The fingerprint is advisory validation telemetry and does not reject an otherwise exact identity.
Occurrence is assigned deterministically among equal context/successor/condition shapes; graph-local
node IDs are never serialized.

Every selected physical site owns a counter. Serialization aggregates counters only when their exact
v2 identities match. The legacy section includes only contexts with exactly one precise identity.

The consumer tries exact v2 first. A legacy fallback is permitted only when the context maps to one
precise site, preventing ambiguous first-wins behavior.

## Legacy identity limitation

External legacy files identify only bytecode context. Multiple `ControlSplitNode`s can share a
context, so legacy data cannot distinguish every transformed graph site. CE v2 resolves this for
CE-produced profiles while preserving legacy input compatibility.
