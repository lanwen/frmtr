# Comment Attribution Normalization

Status: 🔵 Proposed. Design only; nothing here is implemented yet.

> **Decisions settled (maintainer):**
> 1. **Separate tracks** from the containment-index perf work — build canonical binding on the existing
>    `JavaCommentMap` boundary; rewrite that proposal's JavaParser-parity tests to the canonical contract
>    here; leave its perf caller-migration independent.
> 2. **Accept principled output changes, review-gated** — where the canonical rule changes output on an
>    already-passing file (orphan-rescue, chain-segment re-anchoring), keep the rule and review the cluster
>    at its landing stage; add a mimic-JavaParser override only where a cluster is clearly worse.
> 3. **DROP/DUP is a hard completion gate** — `Cleaner.java` and `RecordCollectorTest.java` must reach
>    comment-count parity before the effort is done / the flag flips on by default; root-causing the drop
>    is in-scope and on the critical path.
>
> Also settled empirically (§ Scope): **preserve, don't normalize, the role** — the corpus has zero role
> flips, so idempotence needs only a whitespace-invariant *skeleton* with the role preserved.

## Why this doc exists

frmtr's layout engine is built to be **source-neutral**: a shape is chosen by a pure function of the
AST plus the rendered column, so re-formatting is a fixpoint. Chain fans, ranked broken layouts, and
the reprint-by-default break rules all hold to this. Yet a whole family of non-idempotence bugs keeps
surfacing on **comment-bearing** code (kafka `SharePartitionTest` / #395, `Cleaner` / #391,
`assertThrows` / #393, and the many `chainCommentsAreOnlyTrailingLine` cases that predate them).

They share one root cause, and it is *not* the layout engine. It is that the input the engine is handed
— the AST **plus its attached comments** — is itself **not source-neutral**. Comments are trivia, and
JavaParser binds a trailing comment to a node by a whitespace-sensitive rule. So two byte-equivalent
programs whose only difference is line breaks produce trees with the *same nodes* but comments hung off
*different* nodes. Any layout gate that branches on "does this node/subtree carry a comment" then
diverges across passes, no matter how pure the gate itself is.

This doc proposes closing the class: normalize comment attribution to a **canonical, whitespace-invariant
binding** at the one boundary where the formatter first observes it, so every downstream comment query is
source-neutral by construction. It is the structural sibling of
[layout-decision-model.md](layout-decision-model.md): that doc made *layout* source-neutral; this one makes
the *comment view layout* consumes source-neutral.

## The confirmed root cause

A probe using the project's exact parser (`javaparser-core:3.28.2`,
`setLanguageLevel(BLEEDING_EDGE).setStoreTokens(true).setAttributeComments(true)`) establishes the rule
JavaParser uses:

> A trailing comment binds to the **innermost node whose end-token immediately precedes the comment on
> the same physical line**; failing that it becomes a leading comment of the next node.

"On the same physical line" is the leak — it is a function of whitespace, not structure. Measured
attachment for three families, flat vs. line-broken layout of the *same program*:

| Program (comment) | Flat layout binds to | Broken layout binds to |
| --- | --- | --- |
| `Mockito.when(x).thenReturn(List.of(a, b)); // note` | `ExpressionStmt` | inner `MethodCallExpr List.of(a, b)` |
| `if (a && b) {} // c1` ↔ `if (a // c1` ⏎ `&& b)` | `IfStmt` | left operand `NameExpr a` |
| `assertThrows(X.class, () -> foo(a, b)) // note` | `ExpressionStmt` | last-arg `LambdaExpr` (or inner `ExpressionStmt` of its block) |

In every case the node **exists in both trees**; only the comment's owner moved. Because
`hasContainedComments(chain)` is computed from *which subtree the comment is bucketed under*, its truth
flips: the chain "contains" the comment in the broken layout but not the flat one, so the fan/hug (or
attach/break) verdict flips, and the two passes never converge (#395 is exactly this — it settles only
because one shape happens to re-attach to the statement on the next pass).

## Where attribution is established and read (the two choke points)

**Established** — once, by JavaParser at parse (`JavaFormatter` constructor sets the parser config;
`parser.parse(...)` in `JavaFormatter#format`). There is no post-parse re-attachment today. The only
AST transform between parse and print is import sorting, which does not touch comments.

**First observed by the formatter** — `JavaCommentMap.from(unit)`, built in
`JavaCommentPlacementPolicy#startRun`, invoked from `JavaFormatContext#startCommentRun` *before* the
render dry-run. `JavaCommentMap.recordNode` snapshots, per node identity (`IdentityHashMap`), three
JavaParser views: `node.getComment()` (own), `node.getOrphanComments()` (orphan), and a bottom-up
`contained` list. **Every downstream comment read originates from this snapshot.** Role decisions
(leading / trailing / gap) are already *recomputed from source positions* by `JavaCommentPlacementPolicy`
via `CommentIndex`; only the **candidate pool** (own / orphan / contained bucketing) comes straight from
JavaParser — and that pool is what flips.

So the normalization has a single, contained hook: **change how `JavaCommentMap` buckets comments**, from
"mirror JavaParser's whitespace-chosen attachment" to "compute a canonical binding from token
structure." Nothing above the `JavaCommentMap` / `JavaCommentPlacementPolicy` boundary changes; the AST
is never mutated (`node.setComment` is never called — JavaParser identity is preserved, matching the
existing invariant the map already documents).

## Prior art: Biome / Prettier (and what it tells us)

Biome and Prettier are the closest prior art, and their design (confirmed against Biome's source,
`crates/biome_formatter/src/comments{,/builder.rs}` and `biome_js_formatter/src/comments.rs`) splits the
problem into exactly the two layers we need to separate:

- **The skeleton is structural and whitespace-invariant.** Biome's `DecoratedComment` carries
  `enclosing` / `preceding` / `following` node fields computed from the tree walk and token positions —
  `preceding` is the node ending at our `prevTok`, `following` the node starting at our `nextTok`,
  `enclosing` their common ancestor. This is *identical* to the code-token-gap idea here, and it does not
  move with intervening whitespace. Biome inherits it from Prettier's offset-partition
  `decorateComment`.
- **The role choice deliberately reads line breaks.** The leading-vs-trailing-vs-dangling decision uses
  `CommentTextPosition` (`OwnLine` / `EndOfLine` / `SameLine`), derived from newline counts, plus
  per-construct handlers that test `is_own_line()` / `is_end_of_line()`. So Biome is **idempotent
  by *preservation*** — re-lexing its own output reproduces the same trivia character, hence the same
  placement — but it is *not* whitespace-invariant across equivalent-but-differently-spaced inputs.
- **Per-node overrides are essential, not optional.** The generic position rule mis-attaches for many
  constructs (if-statements, parameters, arrow bodies, empty blocks, JSX), so Biome routes every comment
  through `CommentStyle::place_comment(...)` first, and its bug history is a steady stream of *new*
  per-construct handlers. The lesson: budget for a small, growing set of structural overrides.

The decisive takeaway: **our non-idempotence bugs are skeleton flips, not role flips.** In #395 the comment
stays end-of-line (trailing the `;`) on both passes — its *character* is preserved; what moves is the
*node* it binds to, because JavaParser's "innermost node on the same line" is not Biome's clean
`preceding`/`enclosing` skeleton. So the minimal correct fix is to **adopt Biome's structural skeleton**
(which JavaParser does not give us) while **keeping the role derivation frmtr already does from position**
and letting `CommentTracker`'s existing role slots preserve it. That is strictly less disruptive than
dropping line breaks entirely, and it is proven prior art.

## The invariant

> **The comment→node *skeleton* — which node a comment binds to (`enclosing`/`preceding`/`following`) — is
> a pure function of the code-token stream and AST structure, never of inter-token whitespace. The
> *role* (leading / trailing / dangling) is preserved across frmtr's own re-layout.**

The skeleton clause is the new guarantee JavaParser does not provide; it makes `hasContainedComments`,
`MethodCallChainAnalysis.hasComments`, and every routing predicate built on them return the same answer on
every pass. The role clause is Biome's idempotence-by-preservation, which frmtr's position-based role
recomputation plus `CommentTracker`'s `LEADING`/`TRAILING`/`CONTENT_TRAILING`/`ENCLOSED_TRAILING` slots
already deliver — a comment recorded as trailing is re-emitted as a `lineSuffix` (end-of-line), an
own-line comment on its own line, so re-lexing frmtr's output reproduces the role.

**Open decision — preserve vs. normalize the role.** The invariant above (skeleton-invariant + role-
preserved) is what fixes the known bugs and matches Biome. A *stronger* variant would also drop the
line-break input from the role and normalize it purely from the token gap — collapsing
`foo(); // note` and `foo();`⏎`// note` to one output. That is more aggressive (it rewrites deliberate
own-line/end-of-line placements) and is **not required** for the residual #137 comment family. Recommend
shipping the preservation model first; escalate to full role-normalization only if a *role* flip (not a
skeleton flip) is found on the corpus that preservation cannot make idempotent.

## The canonical binding rule

The whitespace-invariant coordinate of a comment is the **code-token gap** it sits in: the pair
`(prevTok, nextTok)` of nearest non-comment tokens around it in the token stream (`setStoreTokens(true)`
is already on). The same program always yields the same gap for the same comment, regardless of layout.
From the gap we compute the Biome-style **skeleton** — `preceding` = the outermost node that *closed*
nearest before the comment; `following` = the outermost node that *opens* nearest after it; `enclosing` =
their lowest common ancestor — and derive bucketing from it. "Closed nearest before" (Biome/Prettier's
`preceding`), **not** "ends exactly at `prevTok`": a separator between the node and the comment — a `,` in
an argument list (`x, // note`) or an operator (`a && // note`) — must not null out `preceding`, since no
node ends *at* the separator token. The census `→ <NULL>` regressions are exactly the cases where the
naive "ends at `prevTok`" reading fails; using "nearest node that closed" folds most of them into the
skeleton and shrinks the null-owner fallback (rule 4) to genuine no-enclosing-node cases.

1. **Containment (the hub fix).** A comment is *contained in* node `N` iff its gap lies strictly within
   `N`'s code-token span (`N.firstCodeToken ≤ prevTok` and `nextTok ≤ N.lastCodeToken`). This is a pure
   token-range test, so `hasContainedComments(N)` no longer depends on which node JavaParser attached the
   comment to. This single change stabilizes the ~30-site `SourceShapePolicy.hasContainedComments` hub and
   the contained-based chain sub-signals.

2. **Ownership is role-gated (the census-critical rule).** The owner side depends on the preserved role:
   - **Trailing** (end-of-line) comment → owner is `preceding`, the **outermost** node ending at `prevTok`
     (not JavaParser's innermost — outermost is stable: `thenReturn(...)` and the whole chain collapse to
     one owner instead of flipping among them). Fixes `trailingLineComment`,
     `rootHasTrailingLineCommentBeforeFirstSelector`, `methodCallChainHasFinalTrailingLineComment`.
   - **Leading** (own-line) comment → owner is `following`, the outermost node starting at `nextTok`.
   - The role itself is *preserved* from the comment's trivia character, never re-decided from layout.

   **This gate is load-bearing.** The corpus census (§ Scope) shows that applying the statement-terminator
   override to *all* comments — not just trailing ones — mis-binds ~8,000 own-line comments (it drags a
   comment documenting the *next* statement backward onto the previous one) and inflates the re-bind rate
   from **26% to 87%**. The terminator override (rule 4) therefore fires **only for trailing comments**.

3. **Statement-terminator override (trailing only).** When a *trailing* comment's `prevTok` is a statement
   `;` or a statement/block-closing `}`, bind it to the **enclosing statement**, not to whatever inner
   expression ends just before the terminator. *(This is what makes #395 hug stably: `)))));  // note`
   binds to the `ExpressionStmt` on both passes, so the chain never sees a contained or trailing comment.)*

4. **Null-owner fallbacks (the census `→ <NULL>` family, ~287 cases, all regressions if unhandled).** When
   the gap has no node ending at `prevTok` / starting at `nextTok`, `preceding`/`following` is null and the
   comment would lose its owner. Fall back to the **enclosing** node by position:
   - *fluent-chain interior* (`.merge(x) // note` ⏎ `.groupBy(...)` — no node begins at a `.segment`
     token) → the enclosing chain `MethodCallExpr`;
   - *operator-trailing* (`a && // note` ⏎ `b` — `prevTok` is an operator, no node ends there) → the
     enclosing `BinaryExpr`;
   - *literal/arg-trailing with no terminating outer node* → the enclosing statement.

5. **File-header handler.** A leading block comment before `package`/the first declaration is a **detached
   header** (as Biome/Prettier treat it), not owned by `PackageDeclaration`. One per file in the census
   (~800 across the sample); without this it re-binds every license block.

Every override (rules 3–5) is keyed on **structure / token kind, never on line breaks**, so the invariance
holds. Expect a small, growing set — empty-block dangling and `->` arrow bodies are the likely next two —
each added as an explicit structural rule with its own fixture and a ratchet test (§ Verification). The
skeleton and overrides are stated over token coordinates so they can be validated as an *invariance*
property rather than argued case by case.

## Relationship to the containment-index proposal

[comment-containment-index.md](comment-containment-index.md) shares this exact hook (`JavaCommentMap`) but
has the **opposite compatibility contract**: it is a *performance* change whose parity tests assert the
map reproduces JavaParser's `getAllContainedComments()` order byte-for-byte, and it lists "changing
comment placement rules" as a non-goal. This proposal deliberately **retires that parity contract** for
the bucketing step: the map should reproduce the *canonical* binding, not JavaParser's. The two are
sequenced, not merged — the index's bottom-up single-walk structure and identity views are kept (and are
a good host for the canonical computation); its JavaParser-parity assertions are replaced by the
invariance property here. Landing the index first (as a pure-perf, parity-preserving step) and then
flipping the contract keeps each change independently reviewable.

## Scope — what must become invariant

From the routing-signal inventory, in priority order (each tier is a natural rollout stage):

1. **The hub.** `SourceShapePolicy.hasContainedComments` / `hasContainedLineComments` — the ~30-site gate.
   Fixed by rule (1) alone.
2. **Chain analysis.** `MethodCallChainAnalysis.hasComments` and its five sub-signals in
   `MethodCallChainSourcePlanner` (`rootHasComments`, `hasTrailingLineComments`,
   `rootHasTrailingLineCommentBeforeFirstSelector`, `hasInterSegmentLineComment`, `singleCommentedSegment`)
   — the trailing/inter-segment flips. Fixed by rules (1)+(2)+(3).
3. **The named relaxations that exist *because* of the flip.** `ChainFanLayout.chainCommentsAreOnlyTrailingLine`,
   `chainFansByCanonicalRuleWithTrailingLineComment`, `methodCallChainHasFinalTrailingLineComment`,
   `MethodCallPrinter#statementChain`'s final-trailing-comment branch. Once binding is stable these become
   **redundant** — the plain comment-free routing already converges. They are removed *last*, one at a
   time, each removal proven a no-op on the corpus (see rollout).
4. **The other host gates.** Binary/ternary (`hasLineComments`, `hasBetweenOperandComments`,
   `conditionalContainsLineComment`), lambda headers (`haveComments`, `hasBoundaryComments`), control-flow
   (`bodyOwnsLeadingLineComment`, `braceless*BrokeOnLeadingComment`, condition comment predicates),
   signatures (`parametersHaveLeadingLineComment`), switch (`hasLeadingOwnComment`,
   `hasRecoverableArrowLeadingComment`), imports (`hasDetachedLeadingComment`). These are already partly
   position-based; they inherit stability from a stable pool.

## Scope in numbers (empirical)

Three probes over the kafka corpus size the work concretely.

**Blast radius & the override set (re-bind census, 13,044 comments over an 800-file test-tree sample).**
Under the *role-gated* rule the canonical binding differs from JavaParser's attachment for **25.7%** of
comments — and the bulk of that (2,068) is **orphan-rescue**: comments JavaParser left unattributed that
the canonical rule binds sensibly, i.e. an *improvement*. The census confirms the work is **three override
handlers, not a long tail**:

1. **Role-gate the terminator override** (rules 2–3). Unconditional, it mis-binds ~8,000 own-line comments
   and pushes the re-bind rate to 87%; gated to trailing, it is 26%. This single gate is the difference.
2. **Null-owner fallbacks** (rule 4) — the 287 `→ <NULL>` regressions: fluent-chain interior,
   operator-trailing, literal/arg-trailing.
3. **File-header handler** (rule 5) — ~800 (one per file), the leading license block.

Construct volume ranks the rollout order: `ExpressionStmt` dominates every category, then
`MethodDeclaration` / top-level header+imports, then the block-statement family (`Try/For/ForEach/If/While/
Do/Switch`), then fluent chains as their own handler. Two *good* divergences to keep: orphan-rescue and
chain-segment trailing comments re-anchoring from an inner literal to the segment `MethodCallExpr`
(`.withMaxDeliveryCount(2) // note` binds to the call, not the `2` — better for a line-oriented formatter).

**Role-flip verdict (full 6033-file idempotence run).** The entire post-#400 comment residual is **5
files**: 3 skeleton flips (SharePartitionTest/#395, StreamsBuilderTest, StreamsProducerTest) and 2
DROP/DUP (`Cleaner.java` 56→58→57, `RecordCollectorTest.java` 26→22→19). **Zero role flips** — seven
attempts to force one all stayed role-preserving because frmtr *pins a construct broken rather than
stranding a mid-construct comment on its own line*. So the preservation model is empirically sufficient
for the skeleton flips.

The two DROP/DUP files were root-caused and, importantly, have **different** causes — only one is fixed by
this proposal:
- **`Cleaner.java` (DUP) — attribution, in-scope.** Two `//` lines sit in the gap `&&  /*here*/  (…)` of a
  `while` condition — split-contained under *both* the binary operand and the enclosed `(…)` operand, so
  the operand-break site *and* the enclosed-content site each render them (2→4). Assigning a single
  canonical owner (leading of the outermost `following` operand, before the `(`) collapses it to one
  render — the skeleton fix resolves this directly.
- **`RecordCollectorTest.java` (DROP) — render-path, separate fix.** The comment is *correctly* attributed
  to the cast argument; the drop happens in the **expression-lambda-body-as-argument** render path
  (`run(() -> call(a, (Cast) x, // note` …`))` drops it, confirmed with and without the `assertThrows`
  2-arg wrapper — it is the lambda-body seam, #393, not the wrapper). Canonical binding does **not** touch
  this — the attribution is already right. Because DROP/DUP is a hard completion gate (Decision 3), this
  lambda-body render-path fix is a **required companion in-scope**, tracked and fixed alongside the
  skeleton work but implemented in the lambda-argument layout, not the comment map.

**Structural interaction hazards (subsystem audit).** Beyond the layout gates:
- **Insulated (read raw JavaParser, not the map):** `FormatterGuardrails` (counts via the independent
  lexer multiset / identity sets — *cannot manufacture a false drop or dup*), `RawPreservedSource` /
  `RecoveredSourceRegions` (source-offset based; range-less → conservative crossing path), `contentBeginLine`
  (range/child based), and `ImportSortTransform` (runs upstream of the map).
- **Must preserve:** `CompactSourceText`'s comment-stripped *clones* are absent from the run index and
  deliberately use direct JavaParser scans behind the `SourceShapePolicy.hasContainedComments`
  `contains()` guard (clone ⇒ `false` ⇒ fallback). The re-bind must keep `contains()` semantics so clones
  stay on the fallback.
- **Consistency obligation (new scope item):** because the AST is not mutated, any code still reading raw
  `comment.getCommentedNode()` (`JavaCommentPlacementPolicy#unattachedTrailingComment`,
  `ControlConditionCommentLayout`) will **disagree** with the re-bound map. The change must route all
  attribution reads through the policy and eliminate direct `getCommentedNode()` reads — a concrete,
  enumerable migration, not a vague risk.
- **`CommentTracker` needs no code change** (it keys on raw `Comment` + node identity), but which query
  first-offers a comment shifts with the re-bind → emergent output drift → goldens re-baseline expected.
- **Parity tests are the reversed contract:** `JavaCommentMapTest.assertAllContainedCommentsMatchJavaParser`
  and its orphan-first / child-own ordering assertions must be *rewritten* to the canonical contract.

## Verification

Comment bugs are invisible to `--verify` / `AstEquivalence` (comments are trivia, not AST) — only the
lexer-multiset comment-count check and `CommentPresenceDiagnosticTest` catch drops. The verification plan
is therefore layered and comment-specific:

1. **Binding invariance property (the new core test).** For a corpus of comment-bearing files, for each
   file: parse the file *and* a whitespace-perturbed re-render of it (the formatter's own output is a
   convenient perturbation), build `JavaCommentMap` for both, and assert the canonical bindings are equal
   up to node identity mapped by structural position. This directly pins the invariant and is what the old
   JavaParser-parity tests are replaced by.
2. **Idempotence over the comment corpus.** `--write` then `--check` over the kafka corpus; the residual
   comment-family non-idempotent set (#391/#393/#395 and relatives) must go to zero, with **no new**
   non-idempotent files.
3. **No comment loss / duplication.** The lexer-multiset count guard (`grep -oE '//|/\*'` parity across
   passes) and a guardrail-enabled pass over the comment fixtures (`comment-preservation-*`,
   `chain-comment-ownership`, `method-chain-trailing-*`, the adopted prettier-java comment fixtures).
4. **Byte-identity where it must hold.** Every relaxation removed in tier (3) must be a byte-identical
   corpus diff at the removal step — if removing `chainCommentsAreOnlyTrailingLine` changes output, the
   binding is not yet doing its job and the removal is deferred.
5. **Override ratchet.** Each per-node override (rules 3–5) ships with its own fixture, and the invariance
   property runs over the accumulated override set so a *new* override cannot silently regress an old one —
   Biome's history says the override set grows, so the guard against override-vs-override interaction is
   part of the design, not an afterthought.
6. **`getCommentedNode()`-consistency gate.** A test/grep asserting no print-path code reads raw
   `comment.getCommentedNode()` once the map is the authority — so the AST-vs-map disagreement (§ Scope)
   cannot reappear.
7. **DROP/DUP is a hard completion gate (decided).** `Cleaner.java` and `RecordCollectorTest.java` must
   reach comment-count parity across passes (lexer multiset stable) before the effort is done / the flag
   flips on by default. Root-caused: **`Cleaner` (dup) is fixed by the skeleton** (single canonical owner
   collapses a split-contained `&& /*c*/ (…)` double-render); **`RecordCollectorTest` (drop) is a separate
   render-path bug** in the expression-lambda-body-as-argument layout (#393) that the skeleton does *not*
   fix, and is therefore a required in-scope companion fix. Early flag-gated stages may proceed, but the
   default-on flip is blocked on all 5 residual files at parity.

## Rollout (staged, each stage independently shippable)

1. **Land the containment index** ([comment-containment-index.md](comment-containment-index.md)) as the
   parity-preserving perf step, if not already complete, so the canonical computation has a single host.
2. **Add the canonical binding behind a flag**, computed alongside the JavaParser buckets, with the
   invariance property test (verification 1) green — but downstream reads still use the JavaParser buckets.
   Zero output change; this stage only proves the canonical binding is computable and invariant. *Land the
   role-gate and the three override handlers here* (they are what makes the binding match a sane
   attachment), verified by the census re-bind clusters, still with no downstream consumer switched.
3. **Retire direct `getCommentedNode()` reads** (the consistency migration) so the map is the sole
   attribution authority before any consumer reads the canonical pool.
4. **Flip `hasContainedComments` to the canonical pool** (tier 1). Measure the corpus diff: expect the
   #395-style over-width/hug flips to resolve; investigate every other diff as a potential regression.
5. **Flip the chain analysis signals** (tier 2). Re-run idempotence; the chain comment families converge.
6. **Remove the relaxations** (tier 3) one at a time, each a proven byte-identical no-op.
7. **Sweep the remaining host gates** (tier 4) as their pools are already stable — mostly free.

Stages 3–5 are where output changes; each is corpus-verified and comment-count-guarded before the next.

## Risks

- **User-visible comment moves.** Re-binding the skeleton *can* move where a comment renders. Because the
  recommended model **preserves** the leading/trailing role (§ invariant), the moves are confined to the
  node a comment associates with, not its own-line/end-of-line character — a smaller surface than full
  normalization. Still, the invariance property catches *stable*, not *correct* — a mis-specified skeleton
  or override could bind a comment to a stable-but-surprising node. Guard with the fixture suite and manual
  review of the stage-3/4 corpus diffs, and bias the skeleton toward "where JavaParser binds in the
  *fully-broken* layout," which matches Biome's `preceding`/`following` and an attachment the author could
  have written. Biome's experience says to expect a growing set of per-construct overrides here — treat a
  surprising diff as a missing override, not a reason to abandon the skeleton.
- **JavaParser range widening.** JavaParser widens a first child's range to swallow a preceding comment
  (`JavaCommentPlacementPolicy#contentBeginLine` already corrects for this). The token-gap formulation
  sidesteps range widening by keying on code tokens, not node ranges — but the recovered-source and
  raw-preserved paths (`RawPreservedSource`, `RecoveredSourceRegions`) read ranges and must be checked.
- **Range-less / recovered comments.** Comments in unparseable recovered regions have no reliable token
  gap; these keep the direct-JavaParser fallback the containment index already carries for detached clones.
- **Contract reversal cost.** Reversing the containment-index parity contract means its parity tests are
  rewritten, not just extended — a deliberate, reviewed change, not a silent drift.

## Non-goals

- Mutating the JavaParser AST (`setComment`) — normalization stays in the formatter-owned map.
- Changing `CommentTracker`'s claim-neutral two-pass ownership model — it already renders once and never
  duplicates; a stable pool only makes its dry-run first-offerer deterministic.
- Redesigning raw/recovered-source reconstruction beyond the fallback needed for range-less comments.
- Replacing JavaParser or changing the parser configuration.
