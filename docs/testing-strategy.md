# Testing Strategy

Status: current architecture detail.

This document explains the test coverage summarized in [ARCHITECTURE.md](../ARCHITECTURE.md).

## Module Coverage

The test suite covers:

- `:frmtr-core`: `Doc` rendering behavior, formatter output, idempotence, reparse validity, comments, parse errors, and
  fixture corpus checks.
- `:frmtr-tooling`: file-oriented run summaries, deterministic ordering, de-duplication, diffs, write behavior, and
  per-file failure handling.
- `:frmtr-cli`: CLI selector parsing, glob/directory discovery, ignore handling, stdout/write/check behavior, end-of-run
  summaries, explicit no-file diagnostics, option validation, and exit codes.
- `:frmtr-gradle-plugin`: TestKit functional coverage for task registration, zero-configuration Java defaults, `check`
  lifecycle wiring, no-op non-Java projects, Gradle and source-set source filters, build-directory exclusion, check diff
  output, Java language-level inference, and explicit Gradle language-level overrides.
- `:frmtr-native-image-support`: JavaParser metamodel coverage for native-image reflection registration, including
  known-risk AST fields used by field and variable declarations.
- `:frmtr-cli:nativeTest`: native-image compatibility coverage for JavaParser reflection-sensitive syntax. It is explicit
  native coverage and is not wired into the default JVM `check` lifecycle.

## Golden Fixtures

Golden resources live under `frmtr-core/src/test/resources/format`, using directory-local companion files such as
`input.java` and `frmtr.output.java`.

New formatter rules should include golden coverage plus idempotence and reparse checks where practical. Output-changing
rules should prefer fixture coverage that exercises representative source shape, comments, source spacing, and relevant
formatter options instead of asserting only a narrow printer helper result.

Normal fixtures are discovered by convention under `format/**/input.java`, excluding `format/unsupported/**`. Each normal
fixture must include at least one recognized expected output next to the input: `frmtr.output.java` for the default
configuration or `frmtr-<width>.output.java` for a line-width-specific expectation. Adding a normal fixture should only
require adding the directory-local `input.java` and matching `frmtr*.output.java` files, not registering the fixture in a
Java list.

Unsupported syntax fixtures live under `format/unsupported/**` and must include `input.java` plus `error.txt`. These
fixtures assert the expected formatter error for inputs the bundled JavaParser dependency cannot parse.

Option-changing behavior should be covered by independent frmtr fixtures with behavior-oriented names and expected output
files, not by compatibility metadata from another formatter. `@format` remains the public require-pragma opt-in marker.
Active formatter ignore pragmas are `frmtr-ignore`, `frmtr-ignore-start`, and `frmtr-ignore-end`.

## Absorbed Prettier-Derived Cases

frmtr absorbed useful cases from the earlier prettier-derived corpus into behavior-named frmtr fixtures. This preserves
the coverage signal without keeping a dedicated prettier harness, prettier fixture tree, or prettier output snapshots.

## Recovery Fixtures

Recovery behavior is documented in [error-recovery-behavior.md](error-recovery-behavior.md). Recovery tests should assert
external behavior: returned formatted text, idempotence, exception behavior, and visible debug labels. They should not
test internal recovery data structures directly unless those structures expose a stable package-private contract that is
intentionally isolated.

Dedicated recovery fixtures, when added, should live separately from the normal parseable formatter fixture corpus.
Recovery fixtures do not need to produce parseable Java because recovered output intentionally preserves invalid source
islands.
