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

## Formatter helper comments

- When extracting formatter helper modules, add Javadocs in the same style as `RawSource`, `CommentedTokenText`, and `FormatterPragmas`: explain what concern the helper owns, why the boundary exists, and what decisions it intentionally leaves to the caller.
- Add method-level Javadocs when a helper method preserves source-formatting nuance that is not obvious from the method name.
