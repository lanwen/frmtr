# Semantic-Preservation Safety Net: AST-Equivalence, Idempotence, and a Real-World Corpus

Status: Layers 1-2 implemented; Layer 3 proposed

## Summary

A formatter must never change program meaning. Today that property is guarded only by hand-written
golden fixtures under `frmtr-core/src/test/resources/format/**`, plus two narrow runtime guardrails
in `FormatterGuardrails` (comment accounting and per-transform JavaParser identity preservation).
Golden fixtures verify *the output we expected* — they cannot catch a meaning-changing bug in a
construct nobody wrote a fixture for. This proposal adds three independent, automatically-checkable
correctness layers that do not depend on anyone having anticipated the bug:

1. **Layer 1 — AST-equivalence check** (proposed-new): an opt-in verify mode that re-parses the
   formatter *output* and asserts it is structurally equivalent to the input modulo trivia
   (whitespace, comment placement, and the deliberate import reorder). Small, high-value, can ship
   first.
2. **Layer 2 — idempotence property test** (extends an existing assertion): `format(format(x)) ==
   format(x)` over generated and real inputs, not just the fixtures that already check it on line 29
   of `FrmtrTest`.
3. **Layer 3 — corpus harness** (proposed-new): a CI task that formats large real-world OSS Java
   codebases and asserts parse-stability + idempotence + AST-equivalence across hundreds of
   thousands of real files.

The throughline matches the roadmap (`docs/proposals/README.md`, items B1/B2/B3): **this net is what
lets B1 and B2 be done fearlessly.** Today a refactor of source-shape coupling (B1) or the Doc IR
(B2) is validated only against the fixtures someone remembered to write.

## The risk today

The motivating incident is already encoded in the repository. The fixture
`frmtr-core/src/test/resources/format/correctness-data-loss/` exists precisely because an earlier
change **silently dropped enum separators** — a semantic change (it altered which enum constants
were declared / how the constant list parsed) that the golden-file suite did not catch and that was
found by chance. The enum constant list is still a known-fragile area: `EnumDeclarationPrinter` is
the file currently dirty in the working tree (`git status`), and the recent commit history
(`91d511d9 Fix formatter line width and enum comments`, `0ba5a8f0 Address formatter review
findings`) shows repeated correction there.

The structural problem is that the only meaning-preservation checks today are:

- **Golden fixtures** (`FrmtrTest.formatsDiscoveredFixtureAndIsIdempotent`,
  `frmtr-core/src/test/.../FrmtrTest.java:25-33`). These assert `formatted == expected` for inputs a
  human chose. A bug in an unrepresented construct is invisible. The data-loss fixture only protects
  the *exact* cases it enumerates — it does not generalize.
- **`FormatterGuardrails`** (`frmtr-core/src/main/java/dev/lanwen/frmtr/java/FormatterGuardrails.java`).
  This is genuinely valuable but narrow and **off by default**: it is gated behind the system
  property `dev.lanwen.frmtr.debug.guardrails` (`FormatterGuardrails.ENABLED_PROPERTY`, read by
  `FormatterGuardrails.enabled()` via `Boolean.getBoolean(...)`). It checks two things:
  - **Comment accounting** — every `Comment` JavaParser exposes must be either claimed for structured
    rendering (`claimComment`) or raw-accounted (`accountRawComments`), else
    `assertAllCommentsAccounted` throws (called from `JavaPrinter` / `CommentTracker`).
  - **Per-transform identity preservation** — `TransformSnapshot.capture` records node, comment, and
    import-declaration identities before each transform and `assertPreserved` fails if a transform
    swaps the `CompilationUnit`, loses/adds a node or comment identity, or moves a comment off its
    import. Invoked from `JavaTransformPipeline.transform` (lines 31-34).

Crucially, **none of this re-parses the rendered output.** The enum-separator bug lived in the
*printer*, after the transform pipeline, in code the guardrails never observe. Comment accounting
checks that comments reach output; it says nothing about whether the printed tokens still parse to
the same program. There is no check anywhere that `parse(format(x))` is structurally equal to
`parse(x)`. That is the gap Layer 1 closes.

It is worth stating what is *not* at risk: the parser is configured consistently
(`JavaFormatter` constructor, lines 41-48: `setStoreTokens(true)`, `setAttributeComments(true)`,
language level from `FormatterOptions.javaLanguageLevel()`), so input and output can be parsed with
identical settings for a fair comparison.

## Layer 1 — AST-equivalence check (implemented)

> **Implemented.** Landed as `AstEquivalence` plus a `FormatterGuardrails.assertAstEquivalent(...)` hook in
> `JavaFormatter.format`, gated by the `dev.lanwen.frmtr.debug.verify` system property (off by default, parallel to
> `dev.lanwen.frmtr.debug.guardrails`). The check re-parses the formatted output with the same `JavaParser`
> configuration used for the input and compares structurally via JavaParser's `EqualsVisitor` after normalizing both
> trees identically: comments stripped; imports sorted with `ImportSortTransform.FORMATTER_IMPORT_ORDER` (a dropped or
> duplicated import still fails, reported by name); `EnclosedExpr` parentheses unwrapped (sound because precedence is in
> the tree shape and the comparison is structural, not a printed-string compare — see the deviation note below);
> single-parameter lambda parenthesization canonicalized; redundant block-level empty statements dropped; and modifier
> order sorted. Text-block **content** is compared by its JLS String value (each text block is replaced on both sides by
> a `StringLiteralExpr` of `translateEscapes()`), sound because the formatter renders text blocks verbatim and so
> preserves their value. It is enabled for the whole `frmtr-core`
> test suite (via the test task system property) so every golden fixture is also AST-checked — the whole suite passes
> with it on, demonstrating zero false-positives on the existing corpus (and that the formatter preserves every
> fixture's text-block values) — and is skipped for recovered (partially parsed)
> inputs. Negative tests in `AstEquivalenceTest` prove it catches a dropped/renamed member, a dropped enum constant,
> distinct numeric-literal lexemes, a precedence change, and a dropped or duplicated import.
>
> **Deviation from Option A as originally written.** The proposal recommended a normalized *re-print string* compare
> (Option A). That is unsound for the formatter's parenthesis handling: the formatter both adds clarifying parentheses
> to mixed-precedence expressions (`a && b || c` → `(a && b) || c`) and removes redundant ones, and JavaParser's pretty
> printer does not re-insert precedence parentheses, so stripping `EnclosedExpr` and string-comparing would equate
> `(1 + 2) * 3` with `1 + 2 * 3`. The implementation therefore uses Option B's **structural** comparison
> (`EqualsVisitor`) for the decision and keeps the canonical re-print only to build the human-readable failure message.
> Two other formatter-owned transforms surfaced and are normalized the same way: modifier reordering and block-level
> empty-statement removal.
>
> **Text-block content (now in scope).** The first cut compared text blocks by their *whitespace-stripped* value, which
> was unsound (it masked a change confined to interior whitespace). At that time the formatter's recognized-content
> probes (`TextBlockPrinter`) *also* deliberately re-laid-out embedded HTML/JSON/Java/TypeScript, **changing** the
> literal's JLS value, so a sharper JLS-value compare produced real differences on the `text-block-language-and-escapes`
> fixture (e.g. `{"glossary":{"title": "example 'glossary'"}}` → `{ "glossary": { "title": "example 'glossary'" } }`).
> Those were not false positives — the formatter genuinely changed string values — so as an interim measure text-block
> content was excluded. That root cause has since been fixed: the content probes were removed and `TextBlockPrinter` now
> renders text blocks verbatim (a formatter must never change a string literal's value). With the formatter
> value-preserving, text-block content is back **in scope**, compared by its JLS value (`translateEscapes()` on both
> sides). `AstEquivalenceTest` locks all three facets: re-indentation verifies clean, a content change — including one
> confined to significant interior whitespace — is flagged, and replacing a text block with a non-text-block expression
> diverges. Running this across the whole fixture corpus now also guards that the formatter keeps preserving text-block
> values.

**Goal:** after producing formatted output, re-parse it with the same parser configuration and
assert it represents the same program as the input, ignoring trivia (whitespace, comment text and
placement) and the formatter's one deliberate structural transform (import sorting — see Risks).

### What "equivalent modulo trivia" means here

We compare two `CompilationUnit`s (input tree and output tree). The comparison must ignore:

- positions / ranges (`Node.getRange()` — always differs after reformatting),
- token storage and original whitespace,
- comments (placement *and* content — comments are not program meaning; comment **loss** is already
  covered by the `FormatterGuardrails` comment-accounting path and by Layer 2/3 round-tripping),
- import declaration order (the `ImportSortTransform` deliberately reorders;
  `frmtr-core/src/main/java/dev/lanwen/frmtr/java/ImportSortTransform.java`).

and must be sensitive to everything else: identifiers, literals (including their exact lexeme so
`0x1p-1` vs `0.5` is not silently "normalized"), operators, modifiers, type arguments, **the
presence and count of enum constants and their separators**, statement order, etc.

### Approach options

**Option A — normalized re-print comparison (recommended).** JavaParser's `Node.equals` is a
structural equality that already ignores `Range`/position and tokens but is sensitive to comments
and to child ordering, so it cannot be used directly. Rather than fight it, normalize both trees and
compare a canonical string:

1. Parse input → `unitA`; parse output → `unitB` (same `ParserConfiguration` the formatter used).
2. Clone both, strip all comments (`Node.getAllContainedComments()` + `removeComment`, or
   `node.setComment(null)` while walking), and for imports, sort both unit's import lists with the
   same comparator the formatter uses (`ImportSortTransform.FORMATTER_IMPORT_ORDER`) so the
   deliberate reorder cancels out.
3. Render each normalized tree with JavaParser's own concrete-syntax printer
   (`LexicalPreservingPrinter` is *not* wanted here — use the default
   `DefaultPrettyPrinter`/`node.toString()` with comments disabled), producing a canonical,
   trivia-free string.
4. Assert the two canonical strings are equal. On mismatch, emit a minimized diff for the failure
   message.

This is robust, easy to reason about, and reuses JavaParser's own structural notion. It is the same
*technique* the existing fixture test already trusts for parse-stability (`FrmtrTest.latestJavaParses`
re-parses output with a `JavaParser` at a fixed language level), extended from "does it parse" to
"does it parse to the *same thing*."

**Option B — recursive structural walk.** Write a dedicated comparator that walks both ASTs in
lockstep, comparing node type + scalar properties, skipping comment children and treating import
lists as sets. More precise control over which differences are "trivia," but more code to maintain
and more places to get a new JavaParser node type wrong. Recommended only if Option A produces
false-positives that cannot be normalized away.

**Recommendation:** start with Option A. It is the smallest change that catches the enum class of
bug, and its normalization steps (comment strip, import sort) are exactly the two known legitimate
transforms.

### Where to hook it

Two hook points, serving different audiences:

- **Inside the formatter, as a new opt-in verify mode in `Frmtr` / `JavaFormatter`** (recommended
  primary hook). Add a system-property toggle parallel to the existing guardrail one — proposed
  name `dev.lanwen.frmtr.debug.verify` (mirroring `FormatterGuardrails.ENABLED_PROPERTY =
  "dev.lanwen.frmtr.debug.guardrails"`). When enabled, `JavaFormatter.format` would, after
  `DocRenderer.render`, re-parse the result and run the Option-A comparison against the input tree it
  already parsed (`parseResult.compilationUnit()` in `JavaFormatter.printDoc`, lines 65-84). On
  mismatch, throw an `AssertionError` routed through the existing
  `Frmtr.format` catch (`Frmtr.java:17`) so it surfaces as a `FormatterException.internal(...)` —
  consistent with how guardrail `AssertionError`s already surface.

  This belongs near `FormatterGuardrails` conceptually. Recommendation: add the check as a new method
  on `FormatterGuardrails` (e.g. `assertAstEquivalent(CompilationUnit input, String output,
  FormatterOptions options)`) so all "debug-only correctness invariants" live behind one boundary and
  one enablement story, even though it needs its own toggle because re-parsing has real cost.

- **As a test-only harness** for Layer 2 and Layer 3, so the corpus and property tests can call the
  comparator directly without round-tripping a system property. Extract the Option-A normalization
  into a small package-private/`testFixtures` class (proposed `AstEquivalence` in `frmtr-core`) that
  both the runtime verify mode and the tests use.

### Performance / when on

Re-parsing doubles parse cost and adds a normalized re-print per file, so it must be **opt-in, never
on by default** in `Frmtr.format`. Intended usage:

- always on in `frmtr-core` unit/fixture tests (cheap at fixture scale; turn it on for
  `FrmtrTest.formatsDiscoveredFixtureAndIsIdempotent` so every golden fixture is also AST-checked);
- on in the Layer 3 corpus CI job;
- available to developers via `-Ddev.lanwen.frmtr.debug.verify=true` while iterating on a printer;
- off in the shipped CLI / Gradle plugin hot path.

## Layer 2 — idempotence property test (implemented)

> **Implemented.** Landed as `IdempotencePropertyTest`
> (`frmtr-core/src/test/java/dev/lanwen/frmtr/java/IdempotencePropertyTest.java`), a JUnit 5 `@ParameterizedTest` pair
> running over a corpus deliberately broader than the golden fixtures. The corpus is built three ways: every golden
> fixture *input* verbatim, two **parse-preserving** mechanical perturbations of each (whitespace *collapsed* to the
> minimum and *expanded* with extra blanks — rebuilt from JavaParser's own token stream, rewriting only whitespace tokens
> and emitting identifier/keyword/literal/separator/operator/comment tokens verbatim, so literal and text-block interiors
> and comment text are never touched and the program parses to the same tree), and a dozen diverse hand-written snippets
> (generics, lambdas, switch expressions, records, annotations, text blocks, varargs, enums with/without a trailing
> separator, sealed hierarchies, comment-dense members) not present as golden outputs. Generation is fully deterministic
> (mechanical token rewrites, no randomness). The corpus parser matches the default-options formatter's
> `BLEEDING_EDGE` language level, but it does **not** accept everything the formatter accepts: the formatter can RECOVER
> (best-effort parse) inputs the corpus parser rejects as a `COMPILATION_UNIT` (e.g. unnamed-class / unnamed-pattern
> fixtures). Only cleanly-parsing inputs are in scope; RECOVER-only inputs are intentionally skipped (AST-equivalence is
> ill-defined on a best-effort tree, per the Risks section). That skip is no longer silent — a `@BeforeAll` logs the
> skipped fixture names and count and asserts a minimum corpus size, so coverage erosion surfaces.
>
> **Properties asserted (and the one deliberately not).** Over the well-shaped corpus (verbatim fixture inputs + hand-written
> snippets) the test asserts **strict one-pass idempotence** (`format(format(x)) == format(x)`) *and* **semantic
> preservation** (`AstEquivalence.equivalent(parse(x), parse(format(x)))`, the Layer 1 comparator, asserted explicitly so
> it reads as a property rather than only as a verify-mode side effect — and also enforced inside `Frmtr.format` because
> the suite runs with `dev.lanwen.frmtr.debug.verify=true`). Over the perturbed corpus it asserts **semantic preservation
> + parse-stability + eventual convergence** but *not* one-pass idempotence, because the formatter is genuinely not
> one-pass idempotent on arbitrarily-reshaped input (a `return` collapsed onto one over-long line first wraps with its
> binary chain flat and needs a second pass to break the chain). The convergence assertion is **eventual**: repeated
> formatting must reach a *fixed point* within a small number of passes (currently 5). Empirically most reshaped inputs
> reach a fixed point within a couple of passes; a couple need a third; and a few never converge at all — those are
> recorded as findings (see below), including one non-terminating case whose output grows on every pass. (The earlier
> claim that the formatter "converges in two passes" was empirically false and has been corrected.) **Convergence *to the
> formatting of the original* (`format(perturbed(x)) == format(x)`) is deliberately never asserted**: the formatter
> preserves intentional source shape, so two differently-shaped equivalent inputs may format differently, and asserting
> otherwise would be wrong.
>
> **Findings surfaced by the broadened corpus — all fixed and regression-guarded.** The broadened corpus surfaced eight
> perturbed shapes (≈5 root-cause clusters) that exposed real formatter defects, originally parked in the test's
> `EXCLUDED_AS_FINDINGS` with a per-entry diagnosis. They were genuine bugs, not perturbation artifacts, and have **all
> since been fixed**; `EXCLUDED_AS_FINDINGS` is now empty and every perturbed input is in the asserted corpus. Each fix
> is pinned by a dedicated golden fixture:
>
> - *Enum-separator / parameter-comment data loss (non-reparseable):* `correctness-data-loss`, `enum-declaration-layout`,
>   `comment-preservation-block-end-comments` (collapsed) dropped a required separator — a last enum constant's trailing
>   line comment swallowed the terminating `;`/`,`; an enum constant comment sharing the enclosing class's opening-brace
>   line was mis-attributed to the brace (dropping the constant's comma); and a parameter's leading block comment was
>   misread as empty parentheses. Fixed in `EnumDeclarationPrinter`, `MemberBlockPrinter`, and
>   `CommentedMethodSignaturePrinter`; guarded by `enum-constant-trailing-comment-before-semicolon`,
>   `enum-constant-comment-on-brace-line`, and `parameter-leading-block-comment-collapsed`.
> - *Module directive data loss / malformed module:* `comment-preservation-module-declaration` (collapsed and expanded)
>   dropped **every `requires`/`uses` directive** (collapsed) or duplicated the `module` keyword (expanded) because the
>   commented-module reconstruction split the body by source line. `CommentedModulePrinter` now splits by directive `;`;
>   guarded by `comment-preservation-module-single-line`.
> - *Non-terminating reformat loop:* `comment-preservation-method-chain-segments` (collapsed and expanded) grew the
>   output ~12-16 characters every pass because blank-line-separated leading comments routed a method through the raw
>   signature fallback, which re-indented preserved blank lines deeper each pass. Fixed in
>   `CommentedMethodSignaturePrinter`; guarded by `method-leading-comments-blank-separated`.
> - *Oscillation into malformed output:* `block-lambda-arrow-parens-always`/`block-lambda-arrow-parens-avoid` (collapsed)
>   appended a method-chain statement's `;` after the final segment's `//` comment on a later pass, commenting it out.
>   `StatementPrinter` now threads the `;` before the comment; guarded by
>   `method-chain-final-segment-trailing-comment`.
>
> **Not a finding (corrected).** `formatter-pragma-spacing` (collapsed) was previously listed as a finding for moving a
> line-based `// @formatter:on` onto a shared line. That is a **perturbation artifact**, not a formatter bug: a
> line-significant pragma defines its protected region by its line position, so the perturbation now keeps pragma / ignore
> markers (`@formatter:off`/`@formatter:on`/`frmtr-ignore*`) on their own line — the same way it leaves string interiors
> untouched — and the fixture is back in the green corpus.

**What already exists:** `FrmtrTest.formatsDiscoveredFixtureAndIsIdempotent`
(`FrmtrTest.java:29`) already asserts `Frmtr.format(formatted, options).equals(formatted)` for every
golden fixture, and lines 30-32 already assert the output re-parses at Java 25. So idempotence is
checked — but only over curated fixtures. The CLI/tooling never asserts it
(`FormatterRunner.checkFile` compares `formatted` to `original`, not `format(format(...))`).

**Proposed additions:**

- **A dedicated property test** (`frmtr-core/src/test/java/.../FormatterIdempotenceTest`) that does
  not rely on the fixture being hand-written. Inputs sourced three ways:
  1. **Reuse fixture *inputs* directly** — every `input.java` already discovered by
     `ResourceFixtureSource` (glob `format/**/input.java`) is a free, diverse idempotence corpus;
     assert `format(format(input)) == format(input)` independent of the golden `expected`.
  2. **Generated inputs** — a small randomized/structured Java snippet generator (or a property
     library such as jqwik if a new test dependency is acceptable) producing nested expressions,
     enums with/without trailing separators, switch expressions, lambdas, and comment placements —
     the constructs the history shows are fragile. Each generated source: must parse; `format` once;
     assert second `format` is a fixed point; and (when Layer 1 lands) assert AST-equivalence between
     the generated source and its formatting.
  3. **Real inputs** — the same OSS files Layer 3 fetches, run through the idempotence assertion (the
     two layers share a corpus).

- **Fold AST-equivalence into the same test** so each input is checked for *both* idempotence and
  meaning-preservation. Idempotence catches "the formatter disagrees with itself"; AST-equivalence
  catches "the formatter changed meaning even though it's self-consistent." A dropped-enum-separator
  bug could be idempotent (stable wrong output) yet fail AST-equivalence — which is exactly why both
  layers are needed.

## Layer 3 — corpus harness (proposed-new)

**Goal:** run the formatter over large bodies of real Java that nobody curated and assert, per file:
(a) it parses, (b) the output re-parses (parse-stability), (c) it is idempotent, and (d) it is
AST-equivalent to the input (Layer 1). A single bug in a real construct then fails CI loudly.

### Which OSS repos

Pick a handful that maximize syntactic diversity and language-level coverage, matching the roadmap's
"JDK, Spring, Guava, …":

- **JDK `src`** (modern language features, modules, switch expressions, sealed types),
- **Guava** (heavy generics, builders, fluent chains — exercises method-chain printers),
- **Spring Framework** (annotations, large interface hierarchies),
- one or two smaller, comment-dense projects (to exercise the comment machinery the guardrails
  already protect).

Keep the list small and pinned to specific commits/tags for determinism.

### How it is vendored / fetched without bloating the repo

**Do not vendor sources into the repo.** Fetch at CI time, pinned by commit SHA, and cache:

- A new Gradle task (proposed `:frmtr-core:corpusVerify` or a dedicated `frmtr-corpus` test source
  set) that clones/downloads pinned archives into a build-dir cache (`build/corpus/`), or relies on a
  CI cache keyed on the pinned SHAs.
- Network access is required only in the corpus job. Per the sandbox network policy, the GitHub/
  archive hosts must be allow-listed; the job must degrade gracefully (skip with a clear message, not
  fail) when the corpus cannot be fetched, so offline/local builds are unaffected.
- Reuse the existing tooling entry points rather than new plumbing: drive formatting through
  `dev.lanwen.frmtr.tooling.FormatterRunner` or `Frmtr.format` directly, walking files with
  `FileDiscovery` (already used by the CLI, `frmtr-cli/.../FileDiscovery.java`).

### What is asserted, per file

1. Input parses with the configured language level (skip files that legitimately do not — e.g.
   preview features the chosen level rejects).
2. `format(input)` succeeds (no `FormatterException.internal`).
3. **Parse-stability:** `format(input)` re-parses successfully (reuse `FrmtrTest.latestJavaParses`
   logic).
4. **Idempotence:** `format(format(input)) == format(input)`.
5. **AST-equivalence:** Layer 1 comparator on input vs output.

Aggregate failures into a report (count + first N minimized diffs) rather than dying on the first
file, so a run characterizes the blast radius of a regression.

### CI cost & opt-out

- Run as a **separate, scheduled / opt-in CI job** (nightly + on-demand label), *not* on every PR,
  because it is minutes-to-tens-of-minutes and network-dependent.
- Gate behind a property/env (proposed `-Pcorpus` or `CORPUS=1`) so it is never part of the default
  `./gradlew build`.
- Cache the fetched corpus aggressively (keyed on pinned SHAs) so reruns are fast.
- Failures block merge to `main` only via the scheduled job's status, with the offending files and
  minimized diffs attached.

## Rollout order

Mirrors the roadmap's suggested sequencing ("B3 layer 1 — small, catches real bugs now"):

1. **Layer 1 comparator + verify mode.** Land `AstEquivalence` (Option A) and the
   `dev.lanwen.frmtr.debug.verify` toggle. Turn it **on inside the existing fixture test** so every
   golden fixture is now also AST-checked — this alone would have caught the enum-separator incident.
2. **Layer 2 property test.** Add `FormatterIdempotenceTest` over fixture inputs + a small generator,
   folding in the Layer 1 comparator. No new runtime code paths.
3. **Layer 3 corpus harness.** Add the fetch+cache task and the opt-in CI job, reusing
   `FormatterRunner` / `FileDiscovery`. Wire the same comparator and idempotence assertion.
4. Update `docs/proposals/README.md` to replace `TODO-LINK-B3` with this file and flip B3 status as
   appropriate. (This proposal does not edit `README.md`.)

## Risks & false-positives

- **Import sorting changes AST order (the headline false-positive).** `ImportSortTransform`
  deliberately reorders `CompilationUnit.getImports()`
  (`ImportSortTransform.java:32-43`, stable sort, static-first then by name), so a naive
  AST comparison of input vs output *will* report a difference even though the program is unchanged.
  **Recommended handling:** in the Layer 1 normalizer, sort *both* trees' import lists with the
  formatter's own comparator (`FORMATTER_IMPORT_ORDER`) before comparing — the reorder then cancels.
  Equivalently, compare import declarations as a multiset. Do **not** simply ignore imports: a
  *dropped* or *duplicated* import is a real semantic change and must still fail. (Java import order
  is not semantically meaningful, so canonicalizing it is sound; this is the one place the
  formatter legitimately changes structural order, which is exactly why the existing
  `TransformSnapshot` guardrail allows "reorder in place" but forbids add/remove —
  `FormatterGuardrails.assertImportDeclarationsPreserved`.)
- **Comments are trivia, but comment *loss* is meaning-relevant to users.** Layer 1 ignores comment
  placement/text by design, so it will not catch a dropped comment. That is acceptable because
  comment loss is already covered by `FormatterGuardrails.assertAllCommentsAccounted` and by the
  golden fixtures; Layers 1-3 are about *program* meaning. Keep the guardrail comment-accounting path
  on in the corpus job so both kinds of loss are caught in the same run.
- **JavaParser normalization quirks.** The default pretty printer may canonicalize some constructs
  (e.g. redundant parentheses, `,` in array initializers). If such canonicalization differs between
  input and output trees it could mask a real change or create a spurious one. Mitigation: the
  comparator normalizes *both* sides through the *same* printer, so any deterministic canonicalization
  cancels; add targeted unit tests for known-tricky constructs (numeric literals, varargs, trailing
  commas, enum trailing separators) asserting the comparator flags real changes and accepts pure
  reformatting.
- **Language-level / parse-recovery inputs.** The formatter supports parse-error *recovery*
  (`FormatterOptions.ParseErrorBehavior.RECOVER`, exercised throughout `JavaFormatter`). For recovered
  (non-`PARSED`) trees, AST-equivalence is ill-defined. Skip Layer 1 when
  `parseResult.hasParseProblems()` is true (the formatter is intentionally only round-tripping a
  best-effort tree there) and restrict the corpus to cleanly-parsing files.
- **Generator producing invalid Java (Layer 2).** Guard the generator by parsing its output first and
  discarding non-parsing samples, so failures indict the formatter, not the generator.
- **Corpus flakiness from network / upstream drift.** Pin SHAs; cache; skip-with-message on fetch
  failure so the absence of network never turns into a red build for unrelated PRs.

## Success metrics

- Layer 1 enabled in the fixture suite with zero false-positives on the existing corpus, and a
  regression test proving it *fails* on a reintroduced enum-separator drop (a deliberately broken
  printer in a unit test).
- Layer 2 idempotence + AST-equivalence green over 100% of fixture inputs and the generated sample
  budget on every PR.
- Layer 3: a published per-run number — *N files formatted, parse-stable %, idempotent %,
  AST-equivalent %* — with the standing target of **100%** on the pinned corpus, and any drop
  blocking the scheduled job.
- Net effect: a meaning-changing bug in *any* construct present in the corpus fails CI without anyone
  having written a fixture for it.

## Relationship to B1 and B2

This safety net is the precondition that makes B1 and B2 safe to attempt at all.

- **B1 (centralize source-shape coupling)** moves every "respect the source layout here?" decision
  behind one policy and removes direct raw-source peeking from printers. That is a sweeping change to
  *which* tokens get emitted in countless input-dependent situations. Golden fixtures can only confirm
  the cases someone wrote down; **Layer 1 + Layer 3 confirm that, across hundreds of thousands of real
  files, the refactor never changed program meaning, and Layer 2 confirms the new policy is
  idempotent** — which the roadmap explicitly calls out as "hard to reason about" under today's
  source-shape coupling (`README.md` B1).
- **B2 (enrich the Doc IR)** — introducing `lineSuffix`, `fill`, `breakParent`/`conditionalGroup` and
  retiring the hand-rolled comment-placement and width-probe machinery — rewrites the rendering of
  comments and wrapping wholesale. The exact failure mode this net targets (a printer change that
  silently alters the token stream, like the enum separators) is *most* likely during precisely that
  kind of IR migration. With Layers 1-3 in place, a B2 step that changes meaning fails immediately and
  pinpoints the construct, instead of waiting to be "caught by chance."

In the roadmap's own words: **"B2 + B1 shrink the code and the bug surface, B3 lets you make those
changes fearlessly."** Layer 1 is the cheap down-payment that delivers most of that confidence on its
own.

## Non-goals

- Verifying *comment* preservation (owned by the existing `FormatterGuardrails` comment-accounting
  path; Layers 1-3 verify *program* meaning).
- Changing default formatter output, line-wrapping policy, or comment placement.
- Editing or rebaselining existing fixtures, or vendoring OSS sources into the repository.
- Turning any new check on by default in the shipped CLI / Gradle plugin hot path.
- Replacing JavaParser or changing parser configuration.
- Implementing the layers as part of this proposal task.
