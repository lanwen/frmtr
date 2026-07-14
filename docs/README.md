# frmtr Documentation

Index of the `frmtr` documentation tree. Start with the architecture overview, then dive into the
topic-specific documents below.

- [../ARCHITECTURE.md](../ARCHITECTURE.md) — high-level architecture: module layout, public API, formatter pipeline, CLI/Gradle adapters, and testing strategy.
- [../CONTEXT.md](../CONTEXT.md) — terminology and glossary reference defining the vocabulary (selectors, run modes, native-image module, etc.) used throughout the docs.
- [java-formatter-internals.md](java-formatter-internals.md) — the `frmtr-core` formatter internals (document IR, printers, renderer) that are too detailed for `ARCHITECTURE.md`.
- [formatter-coverage.md](formatter-coverage.md) — audit map of which JavaParser AST kinds are owned by structured printers versus raw/compact source-derived text.
- [error-recovery-behavior.md](error-recovery-behavior.md) — implemented behavior for JavaParser parse-error recovery and the design notes behind it.
- [testing-strategy.md](testing-strategy.md) — how the test suite is structured, expanding on the testing summary in `ARCHITECTURE.md`.
- [release-automation.md](release-automation.md) — PR title schema, version selection, changelog markers, and the release/snapshot GitHub Actions workflows.
- [adr/](adr/) — architecture decision records capturing significant, hard-to-reverse design decisions.
- [proposals/](proposals/) — the improvement roadmap and concrete design proposals (active and archived).
