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

## Known identity limitation

The context identifies a bytecode source location and caller chain, not necessarily one transformed
graph branch. Multiple `ControlSplitNode`s can share a context. The current CE producer also stores one
fixed successor pair per context, so conflicting sites can be merged incorrectly.

A future precise CE extension should include:

```text
stage
context
normalized successor-BCI signature
deterministic occurrence discriminator
condition-shape validation fingerprint
```

Graph-local node IDs must not be serialized because they are not stable across builds. Legacy
context-only entries should be used only when unambiguous; ambiguous entries should be skipped rather
than selected first.
