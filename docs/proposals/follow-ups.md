# Outstanding Follow-ups Backlog

Status: Living backlog

This is a living backlog, compiled **2026-06-22** after the B2 close-out (PR #9). It consolidates
outstanding follow-ups sourced from the merged-PR follow-up checklists (#1–#8), the per-proposal
future-work sections, the `KNOWN_DROPS` backlog, and a stranded-branch sweep.

Each item carries its **status tag** and its source in parentheses. Item references link to the
relevant proposal docs in this directory where natural.

## B2 Doc-IR review follow-ups

_(source: PRs #5–#8 + #9 review)_

- Extract the `Fill` greedy pair-walk into a shared helper — decision logic is hand-duplicated in
  `DocRenderer.renderFill` and `DocExplainRenderer`; the same seam now exists for `ConditionalGroup`
  selection. **[open — highest structural debt]** (#6)
- Reshape `Fill` from a raw alternating `List<Doc>` to `contents + separator` (by-construction
  safety). **[optional — superseded for safety by the even-length validation guard added in #9]**
  (#6)
- `groupModes` debug assertion ("a named group must render before its dependent `IfBreak`; unknown
  id ⇒ flat"). **[open — deferred until group-identity has a consumer]** (#6)
- `--explain` decision node for `ConditionalGroup`/`Fill` width-driven choices (currently invisible
  in `--explain`). **[open]** (#7; also S3 future work)
- `ConditionalGroup` sizing: switch `DocWidths` from "first alternative" to "min finite flat width"
  to remove the first-must-be-narrowest dependency. **[open — output-moving, defer to a consumer]**
  (#7)
- Dead empty-list defensive branches in `DocRenderer.renderConditionalGroup` / `DocWidths.measure`
  whose comments describe code the #9 factory guard made unreachable — drop or downgrade. **[open]**
  (#9 review)
- Tiny docs: non-empty precondition on `ThrowsClausePrinter.brokenThrowsClause` (#8); "BreakParent
  needs an enclosing group" implicit dependency across two methods + a pre-existing double-hard-line
  in the gap-comment path (#5). **[open]**

## B2 consumer migration (the unrealized payoff)

_(source: [doc-ir-combinators.md](doc-ir-combinators.md), #7 review)_

- Retire `LayoutWidth` / `Optional<Doc>` width-probe scaffolding (~23 files use
  `currentIndentedWidth`, ~12 `blockStatement`). **[blocked — not byte-identical (source- vs
  output-column) and not expressible by `ConditionalGroup` for the method-call chain; recorded in
  [doc-ir-combinators](doc-ir-combinators.md) Outcomes]**
- `Fill` adoption in arrays / method arguments (like throws; may fix real overflows; opinionated
  style change needing a taste call). **[open — enum packing declined]**
- `ConditionalGroup` and group-identity have no consumer — need Prettier-shaped use cases (e.g. a
  closing delimiter mirroring an opener's break via group-identity) or accept as unused foundation.
  **[open]**

## Comment handling

_(source: #1, #3, `CommentPresenceDiagnosticTest`)_

- `KNOWN_DROPS` backlog — 24 documented comment-drop cases (19 B1-dependent shape-sensitive
  attachment failures; 4 need attachment-independent ownership; 1 Guava file-header orphan). The
  primary correctness debt; shrinks as B1/B2 ownership refactors land. **[open]**

## B1 — source-shape consolidation

_(source: [source-shape-policy-consolidation.md](source-shape-policy-consolidation.md), #2)_

- B1 stages 4–8: ~115 source-peeking call sites and ~17 raw `contains("\n")` probes remain
  (`ExpressionLambdaArgumentLayout`, `VariableInitializerLayout`, `MethodCallChainPrinter`,
  `ControlConditionPrinter`, `ConditionalExpressionPrinter`); #2 landed stages 1–3. Gates 19 of the
  24 KNOWN_DROPS. **[open — large]**

## B3 — correctness safety net

_(source: [semantic-preservation-safety-net.md](semantic-preservation-safety-net.md))_

- Layer 3 corpus harness (Layers 1–2 done): format pinned real-world OSS, assert parse-stability +
  idempotence + AST-equivalence per file; opt-in nightly/on-demand CI. **[open — see B3 plan]**

## Stranded / unmerged work

_(source: merged-PR check + branch sweep)_

- `impl/verify-on-write` (S8) — "Add opt-in `--verify` write safety valve", 1 commit, never merged.
  Decide: merge, or is it superseded by B3's internal `debug.verify`? **[stranded — decision
  needed]**
- `improve/audit-correctness-proposals` (S6–S9 proposal docs: atomic-writes, comment-accounting,
  verify-valve, comment-data-loss) — 3 commits, never merged; these docs are not on `main`, so the
  roadmap doesn't track S6–S9 even though their implementations largely landed. Decide: merge for
  provenance or discard. **[stranded — decision needed]**
- Branch cleanup: `proto/fill-adoption`, `eval/enum-fill`, `b2/throws-fill` (superseded by #8),
  merged `b2/*`, and 11+ `worktree-agent-*` branches to prune. **[housekeeping]**

## Roadmap — proposed, not started

_(source: [README.md](README.md) roadmap)_

- M1 — JMH benchmark harness (needed to validate M2/M3 perf claims) + the
  [performance-followups-from-jfr.md](performance-followups-from-jfr.md) investigation lanes.
  **[open]**
- M4 — LSP editor integration: Phase 1 format-on-save ready (zero core change); Phase 2
  `Frmtr.formatRange(...)` needs new core capability ("biggest adoption driver"). **[open]**
- S1 display-width (tabs/CJK/emoji); S2 `.editorconfig` support; S4 one-line adoption (pre-commit /
  GitHub Action). **[open — proposed]**
- 4 diagnostics-exposure TODOs (`StatementRuleEnvelope:120`, `JavaFormatter:199`,
  `ExpressionRuleEnvelope:55`, `BodyDeclarationRuleEnvelope:91`) — surface recovered/rejected nodes
  + parse problems via a future diagnostics API. **[open]**
