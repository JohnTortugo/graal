# GraalVM Native Image Community Edition (CE)

This guide applies to the `substratevm` directory.

## Scope

- `substratevm` is an `mx` suite for GraalVM Native Image. It provides the native-image tool that is part of GraalVM.
- Most code lives under `src/`, with one directory per module, for example `src/com.oracle.svm.core`, `src/com.oracle.svm.hosted`, `src/com.oracle.svm.graal`, and `src/com.oracle.svm.test`.
- Java sources are typically under `src/<module>/src/com/oracle/...`.
- Use `docs/` for developer documentation, `ci/` for CI configuration, and `mx.substratevm/` for suite metadata.
- Treat `mxbuild/`, `svmbuild/`, and `sources/` as generated output.

## Build and Development Commands
Run commands from this directory.

- `mx build`: compile the suite; run this before tests or image builds so changed sources are rebuilt.
- `mx native-unittest`: preferred local unit-test entry point for this suite.
- `mx helloworld`: quick smoke test for the Native Image toolchain using a basic Java hello-world application.
- `mx checkstyle`: validate style and formatting expectations before sending a change.
- `mx native-image`: use this as the native-image build-tool when building an image is requested as part of substratevm development workflows.

## Change Hygiene

- If you touch documented behavior, update `docs/`.
- Do not commit generated output from `mxbuild/`, `svmbuild/`, `graal_dumps/`, or `sources/`.
