# Comment Guardrail Split + Output-Level Drop Net in CI

**Status:** Implemented (roadmap S7) — guardrail split (`FormatterGuardrails.STRICT_CLAIMS_PROPERTY`, off by default) + asserting output-level lexer net (`CommentPresenceDiagnosticTest` with the `KNOWN_DROPS` backlog); see [Outcome](#outcome) · Category: tests / correctness · Effort: M · Risk: LOW
**Planned at:** commit `9a89f7eb`, 2026-06-20

> See [comment-handling-findings.md](comment-handling-findings.md) for the full evidence map this plan implements.
>
> **Drift check (run first)**: `git diff 9a89f7eb..HEAD -- frmtr-core/src/main/java/dev/lanwen/frmtr/java/FormatterGuardrails.java frmtr-core/build.gradle.kts`

## What this plan delivers, and what changed

The original goal — "turn on the comment-accounting guardrail in CI" — was investigated and split into what actually
works. Two outcomes:

1. **The guardrail split (DONE).** The single `dev.lanwen.frmtr.debug.guardrails` toggle conflated an incompatible check
   (`claimComment` "claimed at most once" fail-fast — 172 benign hits, because `CommentTracker` couples claim+render and
   first-claim-wins de-dupes) with the accounting/transform checks. The split — moving the dup-claim fail-fast behind an
   off-by-default `dev.lanwen.frmtr.debug.guardrails.strict-claims` property — is **implemented and green** on
   `impl/comment-guardrail-split` (`82f7780b`). With dup-claim gated off, **zero** duplicate-claim failures remain.
2. **The CI net is NOT `assertAllCommentsAccounted` (changed).** The investigation (output-level lexer comment
   comparison, branch `inv/comment-presence`, PR #1) proved `assertAllCommentsAccounted` is unreliable in both
   directions: **12 false positives** (comment present in output but unregistered) and **3 false negatives** (real drops
   it misses, incl. an AST-invisible copyright header). So it cannot be the gate. The durable "no comment dropped" net is
   the **output-level lexer comment-token multiset** check.

## Part 1 — the split (implemented; merge `impl/comment-guardrail-split`)

`FormatterGuardrails` now gates two things independently: `dev.lanwen.frmtr.debug.guardrails` →
`assertAllCommentsAccounted` + `TransformSnapshot` (dev aid); `…strict-claims` → the `claimComment` dup-claim fail-fast
(off by default, deferred to B1/B2). `FormatterGuardrailsTest` updated to toggle the right property per check. No
behavior change off the toggles. This branch is ready to merge as-is; it is the enabler, not the gate.

## Part 2 — adopt the output-level lexer net as the CI gate (to do)

The investigation's `CommentPresenceDiagnosticTest` (`frmtr-core/src/test/java/dev/lanwen/frmtr/CommentPresenceDiagnosticTest.java`,
branch `inv/comment-presence`) already computes, for any source + options: normalized comment-token multiset of the
input vs `Frmtr.format` output, with literal absence = a real drop. It currently **reports** (does not assert).

Steps to make it the gate:
1. **Promote to asserting.** Make it fail when any non-excluded `(fixture, shape)` drops a comment, over the same corpus
   it already scans: every golden fixture input @ its options, plus the collapsed/expanded perturbations generated
   exactly as `IdempotencePropertyTest.perturb` does.
2. **Add a documented exclusion list = the S9 backlog.** Seed it with the ~42 known real-drop `(fixture, shape)` cases
   from [comment-data-loss.md](comment-data-loss.md), each annotated with the dropped comment and a reference to S9.
   This makes the gate **green now** while catching any *new* drop immediately. (This is the same documented-findings
   pattern B3 Layer-2 used.)
3. **Wire into the suite** so it runs on every PR (it is a normal `frmtr-core` test; no system property needed — the
   comparison is output-level, independent of the guardrail toggles).
4. As S9 fixes each drop, **remove its exclusion** in the same change. The net is fully green with an empty exclusion
   list when S9 completes.

The normalization must tolerate the formatter's legitimate comment transforms (block re-indentation, trailing-whitespace
strip, `*`-prefix) so only genuine absence fails — `CommentPresenceDiagnosticTest` already does this; keep it.

## Scope
**In scope:** merge `impl/comment-guardrail-split` (Part 1); promote `CommentPresenceDiagnosticTest` to an asserting,
exclusion-list-backed CI test (Part 2); `ARCHITECTURE.md` + `docs/testing-strategy.md` doc updates.
**Out of scope:** fixing the drops (S9); CI-enabling `assertAllCommentsAccounted` or the strict-claims property (the
former is unreliable, the latter deferred to B1/B2 — see [comment-handling-findings.md](comment-handling-findings.md)).

## Done criteria
- [ ] `impl/comment-guardrail-split` merged (split landed; `strict-claims` off by default).
- [ ] `CommentPresenceDiagnosticTest` asserts no comment dropped, with the S9 backlog as a documented exclusion list,
      and runs in `./gradlew test` (green).
- [ ] A deliberately-introduced new drop (a scratch edit) makes the net fail — then reverted (proves it bites).
- [ ] `assertAllCommentsAccounted` is NOT wired as a CI gate; docs explain why.

## STOP conditions
- The lexer net fails on a case not in the S9 backlog (a drop the investigation missed) → add it to S9 and the exclusion
  list; do not weaken the comparator.
- Promoting the net requires touching formatter/printer source → that is S9, not this plan.

## Maintenance notes
- Keep the accounting guardrail (`assertAllCommentsAccounted`) as an opt-in dev aid; it is useful while editing comment
  code but is not trustworthy enough to gate CI (12 FP / 3 FN). The lexer net is the gate.
- The strict-claims invariant becomes CI-worthy only after B2 makes comment ownership deterministic
  ([comment-handling-findings.md](comment-handling-findings.md), bucket C).
- **Update (B2 landed):** B2's ownership consolidation plus the Stage 3 candidate-ladder decoupling
  (`CommentTracker.speculatively`) made the strict-claims invariant hold across the whole `frmtr-core` suite, so Stage 4
  enabled `dev.lanwen.frmtr.debug.guardrails.strict-claims` **on by default** in `frmtr-core/build.gradle.kts` — it is now
  a CI gate. `assertAllCommentsAccounted` (the comment-*drop* guardrail) remains off in the build, because a residual set
  of raw-text-embedded comments (multi-catch union alternatives, for-loop variable comments, switch labels, labeled
  statements, unnamed-variable patterns) reaches output as raw token text without being raw-accounted; CI-enabling it is a
  separate follow-up. The `CommentPresenceDiagnosticTest` lexer net remains the durable no-drop gate.

## Outcome

Both parts landed on `main`. **Part 1 (split):** `frmtr-core/src/main/java/dev/lanwen/frmtr/java/FormatterGuardrails.java`
now exposes two independent toggles — `ENABLED_PROPERTY` (`dev.lanwen.frmtr.debug.guardrails`) for
`assertAllCommentsAccounted` + transform snapshot, and the separate off-by-default `STRICT_CLAIMS_PROPERTY`
(`dev.lanwen.frmtr.debug.guardrails.strict-claims`) for the `claimComment` dup-claim fail-fast; `FormatterGuardrailsTest`
toggles each property independently. **Part 2 (the gate):** `CommentPresenceDiagnosticTest`
(`frmtr-core/src/test/java/dev/lanwen/frmtr/CommentPresenceDiagnosticTest.java`) is now an asserting test, not a
reporter — it compares input vs `Frmtr.format` output comment-token multisets over every golden fixture plus
collapsed/expanded perturbations (sharing the `SourceShapePerturbation` engine with `IdempotencePropertyTest`), failing
on any drop not listed in the documented `KNOWN_DROPS` exclusion map (the S9 backlog) and also failing on a *stale*
exclusion. It runs in the normal `frmtr-core` suite with no system property. As planned, `assertAllCommentsAccounted` was
**not** wired as a CI gate (kept as an opt-in dev aid) and strict-claims stays off by default pending B1/B2.
