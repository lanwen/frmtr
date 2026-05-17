# AGENTS.md

## Architecture

- Keep `ARCHITECTURE.md` up to date whenever a change affects the build shape, package layout, public API, formatter pipeline, CLI behavior, or testing strategy.
- Architecture updates should be made in the same change as the code that changes the architecture.

## Go projects

- Use `slog` for logging.
- Prefer typed attributes such as `slog.String` and `slog.Any` instead of raw key/value pairs.
- If context is available, use the contextual logging variant.
