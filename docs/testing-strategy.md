# Testing Strategy

Status: current architecture detail.

This document explains the test coverage summarized in [ARCHITECTURE.md](../ARCHITECTURE.md).

## Module Coverage

The test suite covers:

- `:frmtr-core`: `Doc` rendering behavior, formatter output, idempotence, reparse validity, comments, parse errors, and
  fixture corpus checks.
- `:frmtr-tooling`: file-oriented run summaries, deterministic ordering, bounded parallel file processing,
  de-duplication, diffs, write behavior, and per-file failure handling.
- `:frmtr-cli`: CLI selector parsing, glob/directory discovery, ignore handling, stdout/write/check behavior, end-of-run
  summaries, explicit no-file diagnostics, option validation, and exit codes.
- `:frmtr-gradle-plugin`: TestKit functional coverage for task registration, zero-configuration Java defaults, `check`
  lifecycle wiring, no-op non-Java projects, root-project aggregation into Java subprojects, inherited module
  configuration overrides, module opt-out, Gradle and source-set source filters, build-directory exclusion, check diff
  output, Java language-level inference, and explicit Gradle language-level overrides.
- `:frmtr-native-image-support`: JavaParser metamodel coverage for native-image reflection registration, including
  known-risk AST fields used by field and variable declarations.
- `:frmtr-cli:nativeTest`: native-image compatibility coverage for JavaParser reflection-sensitive syntax. It is explicit
  native coverage and is not wired into the default JVM `check` lifecycle.

## Golden Fixtures

Golden resources live under `frmtr-core/src/test/resources/format`, using directory-local companion files such as
`input.java` and `frmtr-default.output.java`.

New formatter rules should include golden coverage plus idempotence and reparse checks where practical. Output-changing
rules should prefer fixture coverage that exercises representative source shape, comments, source spacing, and relevant
formatter options instead of asserting only a narrow printer helper result.

Formatter output regressions should normally be added as `format/**` fixtures instead of inline `Frmtr.format(...)`
assertions. The fixture path keeps output expectations next to source shape and automatically exercises golden output,
one-pass idempotence, and parse stability through `FrmtrTest`. Inline formatter tests are reserved for public API,
debug/explain/error behavior, or narrow helper contracts that cannot be expressed as a full-file fixture.

## Semantic-Preservation Property Checks

Beyond the curated golden fixtures, two layers of the semantic-preservation safety net
([docs/proposals/semantic-preservation-safety-net.md](proposals/semantic-preservation-safety-net.md), roadmap B3)
verify correctness without anyone having written a fixture for the bug:

- **AST-equivalence verify mode (layer 1):** the `dev.lanwen.frmtr.debug.verify` system property (on for the whole
  `frmtr-core` test suite) makes `JavaFormatter.format` re-parse its own output and assert structural equivalence to the
  input via `AstEquivalence`, so every formatted fixture is also meaning-checked.
- **Idempotence + semantic-preservation property test (layer 2):** `IdempotencePropertyTest` runs over a corpus broader
  than the golden set — every fixture input verbatim, two parse-preserving whitespace perturbations of each (token-stream
  rewrites that never touch literal or comment content), and diverse hand-written snippets. It asserts one-pass
  idempotence + AST-equivalence on well-shaped inputs and AST-equivalence + parse-stability on perturbed inputs. It
  deliberately does **not** assert convergence (`format(perturbed(x)) == format(x)`): the formatter preserves intentional
  source shape, so equivalent inputs of different shape may format differently. Perturbed shapes that expose genuine
  defects are excluded as documented findings rather than masked.

Normal fixtures are discovered by the `@ResourceFixtureSource(glob = "format/**/input.java")` JUnit source extension.
Each normal fixture must include at least one recognized expected output next to the input:
`frmtr-default.output.java` for the default configuration or `frmtr-<variant>.output.java` for a named option variant.
Non-default variants must include a matching `frmtr-<variant>.options.properties` sidecar that overrides only the options
that differ from `FormatterOptions.defaults()`, such as `line-width=40`. Adding a normal fixture should only require
adding the directory-local `input.java`, matching `frmtr-*.output.java` files, and sidecars for non-default variants, not
registering the fixture in a Java list.

Unsupported syntax fixtures live under top-level `unsupported/**` and are discovered by
`@ResourceFixtureSource(glob = "unsupported/**/input.java")`. They must include `input.java` plus `error.txt`, and assert
the expected formatter error for inputs the bundled JavaParser dependency cannot parse.

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
