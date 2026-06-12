# AGENTS.md

## Architecture

- Keep `ARCHITECTURE.md` up to date whenever a change affects the build shape, package layout, public API, formatter pipeline, CLI behavior, or testing strategy.
- Architecture updates should be made in the same change as the code that changes the architecture.

## Tests

- Use AssertJ for assertions.
- Do not add tests that only prove properties can be assigned or copied. Tests should exercise behavior with meaningful
  logic behind it.

## Formatter helper comments

- When extracting formatter helper modules, add Javadocs in the same style as `RawSource`, `CommentedTokenText`, and `FormatterPragmas`: explain what concern the helper owns, why the boundary exists, and what decisions it intentionally leaves to the caller.
- Add method-level Javadocs when a helper method preserves source-formatting nuance that is not obvious from the method name.
