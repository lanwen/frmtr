# Comment-Handling Findings → B-Work Map

**Status:** Implemented — reference/synthesis whose findings were actioned: S7 net + guardrail split landed, the verbatim-input comment-preservation fixes landed on `main`, the residual shape-dependent/orphan drops are routed to B1/B2; see [Outcome](#outcome) · **As of:** commit `9a89f7eb` (S6 + S8 merged), 2026-06-20

This is the consolidated, evidence-backed map of every comment-handling finding surfaced by the S7 guardrail
experiment and the output-level investigation behind it. Its job is to **route each finding to its fix and to the right
B-work** so B1 (source-shape consolidation) and B2 (Doc-IR / `lineSuffix`) start from a concrete work-list rather than
"fix comments." The actionable plans are [comment-accounting-in-ci.md](comment-accounting-in-ci.md) (S7 — the net + the
guardrail split) and [comment-data-loss.md](comment-data-loss.md) (S9 — fix the real drops).

## How we know what's real

Two checks were conflated and both turned out unreliable as data-loss detectors:

- `FormatterGuardrails.claimComment` "claimed at most once" fail-fast → **benign** (the formatter de-dupes by
  first-claim-wins; `CommentTracker` couples claim+render via `.filter(this::claim)`, so a duplicate claim skips the
  second render and output is correct). 172 hits, all benign. Confirmed by the S7 split: with this gated off, **zero**
  duplicate-claim failures remain.
- `FormatterGuardrails.assertAllCommentsAccounted` → **unreliable in both directions.** It checks "was this comment
  *object* registered as claimed/raw-accounted," a proxy for "did its text reach the output." It **over-reports** (12
  comments are present in the output but unregistered) **and under-reports** (it misses 3 real drops, including an
  AST-invisible file-header copyright block).

The authoritative witness is therefore an **output-level lexer comment-token multiset**: count `//` and `/* … */`
tokens in the input vs `Frmtr.format` output (normalized so re-indentation / trailing-whitespace / `*`-prefix changes
never read as a drop); literal absence = real data loss. Built and pushed as `CommentPresenceDiagnosticTest`
(branch `inv/comment-presence`, PR #1). Run over 189 golden cases + 352 perturbations.

## Findings, bucketed

### A. Real comment drops — DATA LOSS (correctness priority) → S9
**~42 `(fixture, shape)` cases, 86 individual comments.** Of the 53 guardrail-flagged, **41 are real drops**; **3 more**
were missed by the guardrail entirely.

- **On normal (verbatim @default) input — the loss is baked into the committed golden output:**
  - `annotation-block-comment-gap` — the `/* … */` between `@Deprecated` and the method is gone (input has 2 block
    comments, golden output has 1).
  - `comment-complex-block-statements` — one of two `/* dead code */` blocks lost (37 → 36 block comments).
  - `StatementPrinterTest` (inline) — every `} // end nested switch N` trailing comment dropped.
- **On whitespace-perturbed (collapsed/expanded) input — ownership breaks when layout moves:** control-condition /
  `if`; labeled-statement (severe — `comment1` 22→6, etc.); try-resource scopes; method-argument trailing comments;
  switch entry/leading comments; block-comment & annotation gaps; `@formatter:*` pragma lines (genuine, verified, not
  artifacts); comments adjacent to text blocks; records / enums / conditionals; **class-members incl. a Guava copyright
  file-header** and `TODO(jlevy)` blocks (guardrail-missed); interface declarations (guardrail-missed).

Disposition: fix per S9, each pinned by a fixture; re-baseline the two lossy golden outputs. **Evidence for B1** (see
routing).

### B. Accounting gaps — NOT data loss (12) → B2 evidence
Comment text is present in the output but rendered through a path that never recorded a claim/raw-account, so the
guardrail false-flags it. Centered on `block-lambda-arrow-parens-always`/`-avoid` (@default, @collapsed, @expanded) and a
few labeled-statement / unnamed-variable shapes. Not a correctness bug; it is why the accounting guardrail cannot be the
CI net. Disposition: do **not** chase 12 site fixes; they dissolve when comment rendering is unified under B2.

### C. Benign duplicate-claims (172) → strict-claims deferred to B1/B2
The speculative claim-then-skip pattern. Disposition: the `claimComment` fail-fast moves behind an off-by-default
`…strict-claims` property (S7 split, branch `impl/comment-guardrail-split`); it becomes satisfiable — and worth
CI-enabling — only after B1/B2 make comment ownership deterministic.

### D. AST-invisible orphan comments → B1/B2 evidence
The dropped Guava copyright header has an empty AST attachment (`getAllContainedComments()` never returns it), which is
exactly why both the guardrail and an AST-based comparator miss it. Proves comment handling cannot rely on JavaParser
attachment alone.

### E. The reliable net (adopt regardless of B-work) → S7
The output-level lexer comment-presence check. Disposition: promote `CommentPresenceDiagnosticTest` from a printing
diagnostic to an **asserting** CI gate, with a documented exclusion list for the S9 backlog (drained as S9 lands). This,
not `assertAllCommentsAccounted`, is the durable "no comment dropped" guarantee.

## Routing to B-work

**B1 — Centralize source-shape coupling** ([source-shape-policy-consolidation.md](source-shape-policy-consolidation.md)).
The ~37 perturbation drops in bucket A are B1's headline evidence and concrete work-list: comment ownership currently
depends on incidental whitespace (collapsing/expanding a layout changes whether a comment is claimed). B1's
shape-independent ownership is the durable fix; the per-cluster S9 fixes are the test-pinned down payment. Each S9
cluster that resolves to "ownership keyed on source adjacency" should fold into the B1 `SourceShapePolicy` rather than
staying a per-construct patch.

**B2 — Enrich the Doc IR (`lineSuffix` first)** ([doc-ir-combinators.md](doc-ir-combinators.md)). Buckets B, C, and D are
B2's evidence: the `CommentTracker` claim/render coupling (`.filter(this::claim)` everywhere) is the single root of the
benign duplicate-claims (C), the render-without-record accounting gaps (B), and the orphan-attachment fragility (D). B2's
`lineSuffix` "retires most of the comment-placement machinery"; once comment emission is deterministic and owned in one
place, exactly-once claiming becomes natural (the strict-claims guardrail can then go green) and the accounting gaps
disappear.

**Net (E)** is independent of both and should land first — it is the safety net that lets B1/B2 be done fearlessly for
comments specifically, the same way AST-equivalence verify (B3 layer 1) covers program meaning.

## Branch / status snapshot
- `main` `9a89f7eb` — S6 (atomic writes) + S8 (`--verify`) merged.
- `impl/comment-guardrail-split` `82f7780b` — guardrail split (dup-claim → `…strict-claims`), green, pushed; supersedes
  the "enable `assertAllCommentsAccounted` in CI" idea (that check is unreliable — bucket B/D).
- `inv/comment-presence` PR #1 — the output-level lexer net (diagnostic; promote per S7/E).
- Plans: S7 ([comment-accounting-in-ci.md](comment-accounting-in-ci.md)) and S9 ([comment-data-loss.md](comment-data-loss.md)).

## Outcome

The map was actioned on `main`. **Net (E) + the split** landed (see
[comment-accounting-in-ci.md](comment-accounting-in-ci.md)): the guardrail split is in
`frmtr-core/src/main/java/dev/lanwen/frmtr/java/FormatterGuardrails.java` (`STRICT_CLAIMS_PROPERTY`, off by default), and
the lexer net `frmtr-core/src/test/java/dev/lanwen/frmtr/CommentPresenceDiagnosticTest.java` is now an asserting CI test.
**The concrete comment-preservation fixes shipped:** whole-file orphan ordering in
`frmtr-core/src/main/java/dev/lanwen/frmtr/java/CompilationUnitPrinter.java` (orphan-before-first-type and trailing-orphan
emission), switch orphan interleaving in `SwitchPrinter.java` (`commentInterleaver.interleave(... orphanComments ...)`),
trailing-comment routing through `Doc.lineSuffix` in `CommentedExpressionListPrinter.java` and peers (the B2 `lineSuffix`
adoption), and fixture-pinned recoveries such as `block-orphan-method-call-comments`. **The residual bucket-A
perturbation drops were *not* all fixed and are intentionally routed onward:** they remain the `KNOWN_DROPS` backlog in
the net, tracked as B1 (shape-independent ownership) plus one B2 AST-invisible file-header orphan — matching this map's
routing. So the findings here are resolved or assigned, not open-ended.
