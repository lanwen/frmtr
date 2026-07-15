# AGENTS.md

## Architecture

- Keep `ARCHITECTURE.md` up to date whenever a change affects the build shape, package layout, public API, formatter pipeline, CLI behavior, or testing strategy.
- Architecture updates should be made in the same change as the code that changes the architecture.

## Tests

- Use AssertJ for assertions.
- Before pushing Java source, Gradle build logic, or formatter fixture changes, run the full Gradle test suite
  (`./gradlew test`) unless that exact suite has already been run for the current change. Gradle cache and up-to-date
  results are acceptable. For changes without Java code, such as docs-only, website-only, workflow, or release-automation
  script changes, use targeted checks instead of the full suite.
- Do not add tests that only prove properties can be assigned or copied. Tests should exercise behavior with meaningful
  logic behind it.
- Formatter output regressions should be covered as `frmtr-core/src/test/resources/format/**` fixtures, not inline
  `Frmtr.format(...)` assertions. Keep inline tests for API/debug/error behavior or helper contracts that cannot be
  represented as a full-file formatter fixture.
- Format fixtures should read like realistic code with meaningful names that hint at the scenario being tested. Don't pad
  identifiers (e.g. `aaaa`/`Xxxx`/`Yyyy`) just to reach a column; pick realistic domain names whose natural length
  demonstrates the width/wrapping behavior under test.

## Pull requests

- PR titles must follow the release automation schema in `docs/release-automation.md`. Prefer `feat`, `fix`,
  `chore(scope)`, and other Conventional Commits-style titles so release bumping and changelog generation remain
  predictable.
- Optional long-form release notes go between `<!-- frmtr-changelog-details:start -->` and
  `<!-- frmtr-changelog-details:end -->` in the PR body; read "Changelog Detail Markers" in
  `docs/release-automation.md` for how to write good ones.

## Comments

- Keep comments aggressively compact — **2-3 lines at most**, for both new and changed methods (and classes).
- Give a quick *hint* at what the code does and why — enough to jump into context fast — not a technical walkthrough,
  and never a restatement of what the code already says.
- Write comments in the present tense, describing the code as it stands — as if nothing came before it. Don't narrate
  history: "X landed", "now does Y", "Y again", "used to", "retired the old Z", or a PR/issue number as the rationale are
  all smells. Reference the past only when it is crucial to avoid breaking something, and then state the hazard, not the
  changelog.
- The same present-state rule governs prose docs — proposals, the roadmap `README`, `ARCHITECTURE`: state where things
  stand and what to do next, so the doc reads as a map, not a changelog of what broke and how it was fixed. A finished
  item states its current state; keep provenance only where it still guides the next decision.
- If a method needs more than that to be understood, or the comment reads cryptic, treat it as a signal that the method
  or class is too complex: prefer improving the code (clearer names, smaller units) over writing a longer comment.

## Formatter helper comments

The specific case of the rule above for extracted helpers:

- When extracting formatter helper modules, add Javadocs in the same style as `RawSource`, `CommentedTokenText`, and `FormatterPragmas`: hint at what concern the helper owns, why the boundary exists, and what it leaves to the caller — still within the 2-3 line limit.
- Add method-level Javadocs when a helper method preserves source-formatting nuance that is not obvious from the method name.
