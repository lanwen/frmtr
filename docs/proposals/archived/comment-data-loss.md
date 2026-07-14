> **Status: Implemented.** Landed on `main`: the ~42-case comment-drop backlog is drained (fixes across `CompilationUnitPrinter`, `SwitchPrinter`, `CommentedExpressionListPrinter`, and the shape-dependent ownership recoveries), each pinned by a fixture. Archived 2026-07-14; retained as a provenance record. (Two later, unrelated D3-flip perturbation drops are tracked separately in `CommentPresenceDiagnosticTest.KNOWN_DROPS`.)

# Fix Comment Data Loss

**Status:** Implemented / complete (roadmap S9) — the ~42-case backlog is drained and every fix is fixture-pinned; the S7 lexer net is green over the corpus + all perturbations; see [Outcome](#outcome) · Category: correctness / data-loss · Effort: L (iterative) · Risk: MED

> **Update 2026-07-14:** `CommentPresenceDiagnosticTest.KNOWN_DROPS` is no longer empty on `main` — it now parks **two** later, unrelated D3-flip perturbation drops (`method-chain-member-access @ expanded`, `source-multiline-object-chain-initializer @ collapsed`), byte-identical to pre-flip behavior and tracked under the printer-contract-inversion Phase-D comment × width work. They are a different lineage from S9's original backlog, which remains fully drained.
**Planned at:** commit `9a89f7eb`, 2026-06-20 · **Depends on:** [comment-accounting-in-ci.md](comment-accounting-in-ci.md) (S7 — the output-level lexer net + the exclusion list that is this backlog) · **Evidence:** [comment-handling-findings.md](../comment-handling-findings.md)

> **Executor instructions**: Iterative backlog — fix **one cluster at a time**, each its own commit/branch, re-running
> the full suite between clusters. The authoritative remaining work-list is the **exclusion list in the S7 lexer net**
> (`CommentPresenceDiagnosticTest`); drain it. Honor STOP conditions.
>
> **Drift check (run first)**: confirm S7 landed — `frmtr-core/src/test/java/dev/lanwen/frmtr/CommentPresenceDiagnosticTest.java` exists and asserts, with the backlog as documented exclusions. If not, STOP (no regression gate to verify against).

## Why this matters

An output-level lexer comment-token comparison (the only reliable witness — see the findings map) shows the formatter
**genuinely drops comments**: **~42 `(fixture, shape)` cases, 86 individual comments**. This is real data loss, the
thing a formatter must never do. It was hidden because: the dup-claim guardrail aborted `format` before any check ran;
`assertAllCommentsAccounted` both over- and under-reports; AST-equivalence verify ignores comments; and two golden
outputs encode the loss as "expected." Most drops need reshaped input to trigger — which is exactly **B1**'s thesis
(comment ownership must not depend on incidental whitespace). A few drop on ordinary input.

## Work-list (drain the S7 exclusion list; clusters below)

> **All clusters below are now drained/completed** — `KNOWN_DROPS` is empty. The enumerated work-list is kept as
> provenance of what S9 covered, not as open TODOs.

### P0 — drops on NORMAL (verbatim @default) input — fix first; the golden output is itself lossy
- **`annotation-block-comment-gap`** — the `/* … */` between `@Deprecated` and the method is dropped (input 2 block
  comments → golden output 1). Fix the annotation/method-gap comment placement; **re-baseline `frmtr-default.output.java`**
  to include the recovered comment.
- **`comment-complex-block-statements`** — one of two `/* dead code */` block comments dropped (37 → 36). Fix +
  re-baseline.
- **`StatementPrinterTest` (inline)** — every `} // end nested switch N` block-trailing line comment dropped. Fix the
  block/closing-brace trailing-comment path; this is a plain printer bug, not shape-dependent.

### P1 — drops on whitespace-perturbed (collapsed/expanded) input — shape-dependent ownership (B1)
Grouped by construct (each entry is one or more `(fixture, shape)` pairs in the S7 exclusion list):
- **control-condition / `if`** — `comment-preservation-control-condition`, `comment-preservation-if-statement`.
- **labeled-statement** — `comment-preservation-labeled-statement @collapsed` (severe: many comments → few).
- **try-resource** — `comment-preservation-try-resources`, `try-resource-layout`.
- **method-arguments** — `comment-preservation-method-arguments`, `block-orphan-method-call-comments`.
- **switch** — `switch-entry-leading-comments`, `switch-statement-rules`.
- **block-comment / annotation gap** — `annotation-block-comment-gap @collapsed`, `comment-complex-block-statements`,
  `comment-preservation-block-comment-shapes`.
- **`@formatter:*` pragma lines** — `formatter-pragma-{begin-with-on,class,end-with-off,multiple,spacing} @expanded`
  (verified genuine drops: the `// @formatter:on/off` line is lost; confirm the protected-region logic still tracks the
  marker after reshaping).
- **text-block-adjacent** — `text-block-language-and-escapes` (the `// leading`/`// trailing` comments *around* the
  block; interiors are string tokens and never counted).
- **records / enums / conditionals / misc** — `record-component-spacing`, `enum-declaration-layout`,
  `conditional-expression-space-indentation`, `unnamed-variables-patterns @expanded`, `correctness-data-loss @expanded`,
  `empty-statement @expanded`, `qualified-type-receiver-annotations @expanded`, `variable-declarations @collapsed`.
- **class-members / interface (guardrail-missed — found only by the lexer net)** —
  `comment-preservation-class-members` (collapsed: `TODO(jlevy)` blocks; expanded: the Guava copyright **file-header**
  block, AST-invisible), `comment-preservation-interface-declaration @collapsed`.

(The S7 exclusion list is authoritative for exact pairs and counts; this grouping is for fix-ordering.)

## Method per cluster
1. **Reproduce.** Remove the cluster's entries from the S7 exclusion list and run
   `bash -l -c "./gradlew :frmtr-core:test --tests '*CommentPresenceDiagnosticTest' --console=plain"` — it must fail
   naming the dropped comment(s). (For P0 golden cases, the lexer net also fails on the verbatim fixture.)
2. **Root-cause.** Find which node should own the comment and why `JavaCommentPlacementPolicy` / the construct printer
   stops claiming it in that shape. Drops surfacing at top-level finalization = no path claimed it; AST-invisible cases
   (copyright header) = the comment isn't attached to any node and needs an orphan/file-level path.
3. **Fix shape-independently.** Ownership must not depend on source layout (B1). Prefer fixing the placement policy when
   the same adjacency rule recurs across clusters.
4. **Pin with a fixture.** Add a `format/**` fixture whose input is the reshaped (or, for P0, the verbatim) form, with
   the comment preserved in the expected output. For the two P0 golden fixtures, **re-baseline the existing
   `frmtr-default.output.java`** in the same change (the old expected output is lossy). Use the `adopt-fixture` skill.
5. **Un-park.** Remove the cluster's S7 exclusions so the lexer net re-covers it.
6. **Verify.** `bash -l -c "./gradlew test --console=plain"` → `BUILD SUCCESSFUL`; AST-equivalence verify still green; no
   other fixture regressed.

Commit per cluster (imperative subject); end each body with: `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`.

## Done criteria
- [x] The S7 lexer-net exclusion list is **empty**; the net is green over the whole corpus + all perturbations.
- [x] Each fix has a `format/**` fixture; the two P0 golden outputs are re-baselined to include the recovered comment.
- [x] `bash -l -c "./gradlew test"` exits 0; no fixture/assertion weakened.

## STOP conditions
- S7's lexer net has not landed → STOP (no gate).
- A cluster fix regresses another fixture or fails AST-equivalence and the conflict isn't resolvable within the
  cluster's root cause → STOP; it likely needs the B1 placement-policy consolidation, not a local patch.
- The same drop recurs across many constructs with one shared root cause → STOP and escalate to **B1** rather than
  patching each construct.

## Maintenance notes
- These fixes are the test-pinned down payment for **B1** (source-shape consolidation): when several resolve to the same
  "ownership keyed on source adjacency" cause, fold the remainder into the B1 `SourceShapePolicy`. The AST-invisible
  orphan drops also motivate **B2** (deterministic, attachment-independent comment ownership). See
  [comment-handling-findings.md](../comment-handling-findings.md).
- Keep the lexer net (S7) on throughout — it is the safety net proving each fix works and catching new drops while you
  edit placement logic.

## Outcome

Complete on `main`. The S7 lexer net
(`frmtr-core/src/test/java/dev/lanwen/frmtr/CommentPresenceDiagnosticTest.java`) is the live gate, and its `KNOWN_DROPS`
map — which *was* this backlog — is now **empty**. The P0 cluster (drops on **verbatim @default input**, where the
golden output was itself lossy) and every collapsed/expanded perturbation cluster are drained:
`annotation-block-comment-gap`, the `StatementPrinter` block-trailing `} // …` drops, `comment-complex-block-statements`,
the control-condition/if, labeled-statement, try-resource, method-argument, switch, records/enums/conditionals, and
member/interface-body cases all preserve their comments at every shape, each pinned by a fixture. With no exclusions
left, S9 is done and the net stands as a regression guard. The net fails on any *new* drop and on any *stale* exclusion,
so a future regression cannot hide and cannot be parked.
