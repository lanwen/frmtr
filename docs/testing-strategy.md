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

## Adopted Prettier-Java Corpus

The adopted upstream `prettier-java` fixture set lives under
`frmtr-core/src/test/resources/format/prettier-java`.

The adopted tree preserves verbatim upstream `input.java` and `prettier.output.java` files. `frmtr.output.java` snapshots
are checked in for fixtures whose upstream syntax JavaParser can parse. These formatter snapshots use frmtr's normal
style while taking the line-width matrix from the Prettier compatibility fixture options, so public default-width changes
do not rebaseline the adopted corpus. An explicit upstream-compatibility subset is compared directly against
`prettier.output.java` using an 80-column, two-space, raw-trailing-whitespace-preserving compatibility baseline.

Fixture-local `frmtr.options.properties` metadata, inherited from parent fixture directories, records option-matrix
overrides such as pragma-gated mode, lambda arrow-parens mode, binary-operator position, or wider line width without
changing Java fixture inputs or expected outputs.

Fixtures using upstream syntax unsupported by the bundled JavaParser dependency stay in the adopted tree, are explicitly
enumerated in tests, and are skipped by formatter assertions until parser support exists. `frmtr-output-examples`
preserves formatter snapshots from earlier parseable adaptations of unsupported fixtures as examples only.

## Recovery Fixtures

Recovery behavior is documented in [error-recovery-behavior.md](error-recovery-behavior.md). Recovery tests should assert
external behavior: returned formatted text, idempotence, exception behavior, and visible debug labels. They should not
test internal recovery data structures directly unless those structures expose a stable package-private contract that is
intentionally isolated.

Dedicated recovery fixtures, when added, should live separately from the normal parseable formatter fixture corpus.
Recovery fixtures do not need to produce parseable Java because recovered output intentionally preserves invalid source
islands.
