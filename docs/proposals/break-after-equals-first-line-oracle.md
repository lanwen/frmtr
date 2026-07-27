# The Chain First-Line String Oracle at the Declaration Seam

Status: 🔵 Design investigation (issue #465, sub-issue of #439). No code changes in this doc; a
follow-up implementation issue is filed once a direction is picked.

## Context

`MethodCallChainPrinter.methodCallRootFirstLine` (`frmtr-core/src/main/java/dev/lanwen/frmtr/java/MethodCallChainPrinter.java:1915`)
is the last live chain-path consumer of `LayoutWidth.nodeLine`. It predicts a chain root's first
rendered line as a `String`, and that string crosses a node boundary: it is consumed by
`VariableInitializerLayout` (and, through it, `FieldDeclarationPrinter`) to decide whether a
method-call-chain initializer attaches after `name = ` or breaks onto its own indented
continuation. This is the one remaining place in the chain path where a width fact is produced by
one node and consumed by a structurally unrelated node, rather than ranked locally with
`bestFitting`/`bestFittingFirstLine`.

This revision corrects the consumer inventory (7 sites, one of them a different decision that is
explicitly scoped out), retracts the comment-hazard claim behind the earlier Option-A rejection now
that it has been root-caused to an unrelated bug, retracts Option C's source-neutrality claim now
that a live bug contradicts it, re-derives Option B's real cost against frmtr's actual primitives,
and adds a fourth option — deciding the seam structurally, biome-style — that the original draft
never considered.

## 1. Verified consumer-site inventory

`methodCallChainFirstLine` (`MethodCallChainPrinter.java:1891`) is exposed through two purely
delegating wrappers — `MethodCallPrinter.methodCallChainFirstLine` and
`ExpressionPrinters.methodCallChainFirstLine` — and threaded into `FieldDeclarationPrinter` via
`DeclarationPrinters.java:152` as a plain constructor argument. None of those three add behavior:
they are plumbing, not consumers. `FieldDeclarationPrinter` holds its own
`VariableInitializerLayout` instance and forwards the function unchanged, so a field initializer
and a local-variable initializer are the **same** consumer surface — there is no field-specific
decision to cover separately.

The actual value consumers — sites that call `methodCallChainFirstLine.apply(methodCall)` — are all
inside `VariableInitializerLayout`, and there are **7**:

| Site | Method | Decision |
|---|---|---|
| `:388` | `variableWithStatementTerminator` (tail-comment route) | attach/break after `=` |
| `:429` | `variableWithStatementTerminator` (final trailing-comment route) | attach/break after `=` |
| `:455` | `initializerFansWidthDrivenTwoSelectorChain` branch | attach/break after `=` |
| `:2218` | `variableWithForcedMethodCallChain` | attach/break after `=` |
| `:2550` | `dotBrokenObjectRootTailChain` | direct `openerLineWidth` probe, same decision, narrower case; falls through to `:429` when it declines |
| `:2638` | `mixedFieldMethodCallFirstLine` (called from `:1440`) | attach/break after `=`, mixed field-access/call root |
| `:2708` | `variableWithExpressionLambdaInitializer` | attach after `->` vs break — a **different** decision |

Six of the seven collapse onto **one structural question** — does the chain (or, for
object-creation roots, the constructor opener) fit after `name = ` on the declaration line, or must
the whole initializer break after `=`? `mixedFieldMethodCallFirstLine` (`:2634-2638`) is the same
question for the case where the chain root walk lands on something other than an object-creation
expression; it delegates straight back to `methodCallChainFirstLine` and feeds the same
`variableWithMethodCallChain` decision as the other five attach/break sites. **Five** of the six
(`:388`, `:429`, `:455`, `:2218`, `:2638`) call `variableWithMethodCallChain` directly
(`VariableInitializerLayout.java:2570-2604`), which runs the general decision:

1. `attachedSingleSegmentChainMustBreakAfterEquals` (a narrow object-creation/zero-arg-tail case,
   `:2482-2502`) forces break-after-`=` regardless of the passed-in `firstLine`.
2. Otherwise, an object-creation root gets one more chance to attach via its constructor opener
   width (`:2581-2599`) before falling back to break-after-`=`.
3. Otherwise, the general `openerLineWidth(variable, flatName + " = " + firstLine) > lineWidth`
   test decides attach vs. break (`:2600-2603`).

The sixth, `:2550` (`dotBrokenObjectRootTailChain`, `:2527-2565`), does **not** itself call
`variableWithMethodCallChain` — it runs an *earlier*, narrower version of the same comparison for a
specific comment-bearing empty-tail object-creation shape, inside the same branch of
`variableWithStatementTerminator` that contains `:429`. Only when `:2550`'s check declines does that
branch fall through to `:429`'s direct call into `variableWithMethodCallChain`. So all six sites feed
the one general decision, five directly and one (`:2550`) by falling through to a sibling call in
the same branch.

**`:2708` is out of scope.** `variableWithExpressionLambdaInitializer` decides whether an
expression-lambda body (`NAME = params -> chain`) attaches after `->` or breaks under it
(`VariableInitializerLayout.java:2692-2738`) — a structurally distinct seam with its own prefix
(`lambdaPrefix = parameters + " ->"`, not `flatName + " = "`) and its own two-tier fallback
(`variableInitializer(... + " " + bodyFirstLine)` then `variableInitializer(... + lambdaPrefix)`
alone). It reads `methodCallChainFirstLine` for the same reason — it needs the chain's first line
before choosing a statement shape — but any of Options A/B/D applied to the `name = ` seam would
need a second, parallel treatment for the `-> ` seam; this doc does not attempt that second
treatment and leaves `:2708` on the oracle unconditionally under every option below.

**Conclusion:** the `name = ` surface is one decision (attach-after-`=` vs. break-after-`=`),
reached through two decision functions, fed by six call sites that are otherwise plumbing, plus one
structurally separate lambda-arrow decision (`:2708`) that stays out of scope. Any option below
needs to replace the one `name = ` decision, not seven independent ones, and needs to state
explicitly that it leaves `:2708` alone.

## 2. Option A — declaration-owned ranking

### Is the historical blocker actually gone?

Confirmed: `canAttachFirstSegmentToSimpleRoot` no longer exists anywhere in the tree (`codedb_symbol`
returns zero hits on this branch). The sibling sub-issue deleted it as provably inert. That was the
gate that made the previous break-after-`=` attempt depend on source shape, so the specific
regression that sank that attempt is gone.

The seam still has one live source-shape consideration to manage, not avoid:
`methodCallChainIsSourceMultiline` participates in *shape selection* (which chain layout applies,
via `methodCallChainInitializerShape`) rather than in the attach/break decision itself. A "build
both statement shapes, rank" rewrite must keep this confined to shape selection and out of the
ranking decision, same as today — it is a scoping discipline to preserve, not a blocker to clear.

### The comment-hazard rejection was a different bug, not a structural blocker

The earlier version of this proposal treated a "second render pass over the same subtree drops a
comment" mechanism as an inherent risk of building two full statement-shape Docs and ranking them.
That mechanism has since been investigated and disproved as the actual cause of the drop it was
inferred from: the drop traced to a missing statement terminator
(`BlockLambdaArgumentLayout.huggableBlockLambdaArguments` never carrying `finalSegmentSuffix`),
which produced unparseable pass-1 output; the comment loss was reparse corruption downstream of
that, not a comment-ownership conflict between two ranked arms. A targeted check of the ownership
theory — instrumenting `CommentTracker.ownedComment` for double-claim denials across the corpus —
recorded zero denials, and the strict-claims guardrails stayed silent. There is no confirmed
mechanism by which building two full Doc shapes of the same comment-bearing subtree, then ranking
with `bestFittingFirstLine`, drops a comment.

This changes what Option A has to defend against. Comment-claiming is still a real design
consideration — `JavaCommentTrivia.claim` is a mutable identity-set side effect of rendering, so
building both shapes still means the comment gets *visited* twice, and a claim made while building
the losing arm is a side effect that happened for nothing — but "visited twice" is not "dropped."
The three comment-bearing call sites (`:388`, `:429`, `:2550`) render their chain once today and
compose the width decision around the pre-built Doc specifically to claim the comment exactly once
and avoid doing a wasted render; that is a real efficiency/cleanliness reason to keep their current
one-render discipline, but it is no longer a correctness argument that a two-shape rewrite is *unsafe*
for them. A two-shape rewrite covering the comment-bearing sites would need to build both statement
Docs from **one** already-rendered chain segment (mirroring the `groupedPromotedRootWithSingleSegment`
pattern used elsewhere in the chain path: one Doc, ranked candidates built by wrapping it, not by
re-rendering the underlying subtree per candidate) rather than rendering the `MethodCallExpr`
twice through two independent entry points. That shared segment must carry its own statement
terminator/suffix (the `finalSegmentSuffix` class of bug root-caused above) into **both** wrapping
candidates — the actual root cause was a terminator silently dropped on one render path producing
unparseable pass-1 output, not a comment-ownership conflict, so the discipline this seam needs is
"one rendered segment, terminator included, wrapped by both candidate shapes," not merely "render
the subtree once instead of twice." That is an implementation discipline, not a dead end.

### Blast radius (qualitative)

With the comment hazard reframed, the honest form of Option A — build both statement shapes as Docs
sharing one rendered chain segment, rank with `bestFittingFirstLine` — is a candidate for **all six**
`name = ` sites, not just the two comment-free ones the earlier draft scoped to. The remaining cost
is real implementation work, not a structural wall:

- A new "broken-after-`=`" Doc shape has to be built for each of the six sites' existing chain
  render (`Doc.concat(Doc.text(name + " ="), Doc.indent(Doc.concat(Doc.HARD_LINE, chain)))`, already
  the literal fallback text at `variableWithMethodCallChain`'s tail) and ranked against the
  attached shape with `bestFittingFirstLine` (needed, not `bestFitting`, because the chain body can
  hard-break in both arms — the switch-header precedent).
- The three comment-bearing sites need the single-render-shared-segment discipline described above;
  this is new code, not a reuse of an existing helper, and needs its own verification that the
  shared segment's comment claim is visited by exactly one candidate's render, not both.
- `attachedSingleSegmentChainMustBreakAfterEquals`'s narrow object-creation case still forces
  break-after-`=` unconditionally and is unaffected either way; it does not consume `firstLine` for
  its own decision, only for rendering the winning shape's text once chosen.
- `dotBrokenObjectRootTailChain` and `mixedFieldMethodCallFirstLine` route through the same general
  decision and would need the same two-shape treatment as the others.
- `:2708` (lambda-arrow) is explicitly out of scope (§1) and keeps using the oracle regardless.

This is a bigger win than the rejected draft credited (six sites reachable, not two) but the
per-site engineering cost — new Doc shapes, the shared-segment discipline for the comment-bearing
sites, and a corpus verification pass — is unchanged in kind. §5 revisits whether that cost is worth
paying now given Option D.

## 3. Option B — group-id / `ifBreak` primitive

### frmtr already has the primitive; the earlier draft did not check

The rejected draft described `Doc.group(id)` / `ifGroupBreaks` as machinery frmtr would need to
build. That is wrong: frmtr already ships the exact combinator pair biome uses.

- `Doc.group(Doc doc, String groupId)` (`Doc.java:94`) — "Builds a group with a stable identity so a
  dependent `IfBreak` can read this group's chosen mode by name instead of the ambient mode."
- `Doc.ifBreak(Doc breakDoc, Doc flatDoc, String groupId)` (`Doc.java:112`) — "Selects `breakDoc` or
  `flatDoc` based on the mode of the named group rather than the ambient surrounding group."
- `DocRenderer` carries a `Map<String, Mode> groupModes` (`DocRenderer.java:28`), populated when a
  `Group` carrying an id is resolved (`DocRenderer.java:113-116`: `groupModes.put(group.groupId(),
  next)`), and consulted when an `IfBreak` naming that id is reached (`DocRenderer.java:122-134`:
  `groupModes.getOrDefault(conditional.groupId(), Mode.FLAT)`).
- This is not a paper primitive — it is load-bearing production code today, used at
  `ChainSegmentPaddingLayout.java:108` (`Doc.group(padded.doc(), group.groupId())`, re-padding a
  Doc while preserving its group identity so a dependent `IfBreak` elsewhere still resolves).

So the "minimal combinator set" the issue asks Option B to sketch does not need building. The real
question is whether this primitive, as it exists, actually closes the gap the declaration seam has
— and it does not, for a reason the earlier draft's ordering argument gestured at but mischaracterized
as a missing `indent_if_group_breaks` combinator. The real gap is in the width authority, not the
Doc/renderer layer.

### The actual gap: no queue lookahead

`DocRenderer.render`'s `Group` case decides its own mode with `widths.fits(group.doc(),
options.lineWidth() - column)` (`DocRenderer.java:112`) — it measures the group's own subtree
against the remaining columns, nothing more. `DocWidths.Measurement.fits(Doc doc, int remaining)`
(`DocWidths.java:77`) is scoped identically: it takes exactly the one `Doc` passed to it and
measures only that subtree. Neither the renderer's group resolution nor the width authority behind
it ever looks past the group's own content into whatever comes next in the document.

Biome's printer does look ahead. `Printer::fits` (`crates/biome_formatter/src/printer/mod.rs:358-365`)
takes `queue: &PrintQueue` — the *entire remaining print queue*, not just the group being measured —
and `FitsMeasurer::fits` walks forward through that queue as part of the same fits computation. This
is how biome's `Fluid` assignment layout (§4 below) can measure "does `name = ` plus the group plus
whatever text follows the group on this line" fit, rather than "does the group alone fit."

Applying frmtr's *existing* group-id primitive to the declaration seam without also giving
`DocWidths` queue lookahead would only let a *later* Doc react to the chain group's resolved mode
(e.g., emitting different padding or a trailing token after the chain, mirroring
`ChainSegmentPaddingLayout`'s existing use) — it would not let the declaration's *own opener*,
printed *before* the chain group, choose its shape based on a mode the renderer has not resolved
yet at that point in the render walk. That ordering constraint (opener precedes the group that
decides it) is exactly what defeated the earlier draft's Option B sketch, and adding queue lookahead
to `DocWidths` does not remove it either: lookahead lets a *fits check happening while walking
forward* see more of the document, but the declaration's opener text is chosen and appended to the
output stream before the renderer ever reaches the chain group, at which point there is no "ahead"
to look into — the group hasn't been visited, so it has no queue content of its own for a lookahead
fits-check to consult except by pre-walking it, which is the same probe-ahead-of-render idea
`methodCallRootFirstLine` already performs as a plain string.

### Re-derived cost

Because the Doc/renderer primitive already exists, Option B's cost is not "build group ids, a mode
table, and `indentIfGroupBreaks`" (the earlier draft's ~2-3 week estimate, most of which does not
apply). The remaining, real cost is narrower and falls in a different place:

- Giving `DocWidths.fits` genuine queue lookahead (measuring past a group boundary into what the
  renderer would print next) is a width-authority change, not a Doc-node change — it would need the
  renderer to pass forward some representation of "what comes after this group in this render," which
  today's single-pass `render(Doc, indent, mode, widths)` walk does not carry as an explicit,
  measurable structure (there is no persistent print queue object separate from the recursive call
  stack). That is a real architectural addition, closer in spirit to biome's `PrintQueue`/`FitsMeasurer`
  split than to anything frmtr's renderer does today.
- Even with lookahead, §3's ordering analysis still holds: the declaration's `name = ` opener is
  chosen and emitted before the chain group is reached, so no lookahead-augmented fits-check
  running *at* that group can retroactively change what the opener already emitted. The primitive
  frmtr would need to build is not for *this* seam's shape (opener chosen ahead of its decider);
  it would be for a shape where the reader legitimately comes *after* the decider (the shape
  `ChainSegmentPaddingLayout` already uses the existing primitive for).
- `bestFitting`-candidate id-scoping (independently rendering multiple candidate arms, each
  potentially resolving the same group id) remains an open bookkeeping question if group ids were
  ever read across sibling candidate arms — orthogonal to the lookahead question, and not needed
  for this seam since this seam does not need the primitive at all.

**Effort re-estimate:** building the missing queue-lookahead primitive in `DocWidths` and
`DocRenderer` is a multi-day-to-low-single-digit-week renderer change (smaller than the earlier ~2-3
week estimate, since the Doc/group-id layer is already built) — but it still does not retire the
oracle at *this* seam, because this seam's ordering shape is the one the primitive cannot help with
regardless of lookahead. It would only be justified by a future seam whose reader genuinely comes
after its decider in document order.

## 4. Option C — documented irreducible

### Is the oracle source-neutral today? No — this is a live bug, not a verified invariant

The earlier draft verified `methodCallRootFirstLine`'s own branches (`compact.apply`,
`huggableBlockLambdaFirstLine`, `compactSource.compact` fallbacks) and concluded the oracle is
source-neutral "end-to-end." That conclusion does not survive checking the layer underneath it.
`CompactSourceText.compact` (`frmtr-core/src/main/java/dev/lanwen/frmtr/java/CompactSourceText.java:71-72`)
documents its own escape hatch: "calls that contain comments stay on normalized token text so the
compact path does not silently discard comment content" — i.e., for a comment-bearing node, `compact`
does not return AST-derived canonical text; it falls back to raw token text, and `normalizeWhitespace`
of raw token text is exactly the source-shape leak the chain-path migration exists to eliminate.
`SourceShapePolicy.fitsOnOneLine` (`SourceShapePolicy.java:134`) — the width gate this whole family
of oracle branches ultimately feeds — measures that fallen-back text un-cleaned.

A confirmed live repro once demonstrated this — a scoped, comment-bearing chain initializer that
rendered a 129-column over-width line and self-healed on a second pass — and was tracked as issue
#467. #467 is now fixed (PR #472), but its root cause turned out to be a terminator sitting outside
the width-measured group, not the `compactTokenText` divergence described above; fixing it left that
divergence itself untouched. The divergence is real (measured text can be up to one column wider
than emitted text whenever `compactTokenText`'s raw-token fallback fires) but currently has no known
repro of its own — it is tracked separately as **issue #473**.

This breaks Option C's premise, not just its evidence: the oracle is source-neutral *when the chain
is comment-free*, but for a comment-bearing chain it inherits `CompactSourceText`'s fallback, which
is not proven source-neutral. Documenting "the oracle is source-neutral by design" in
`ARCHITECTURE.md` right now would assert an invariant the codebase has not verified for one of this
seam's own live cases (three of the seven consumer sites in §1 exist specifically to handle
comment-bearing initializers). **Option C is blocked on #473** — it cannot ship until that divergence
is closed.

### What Option C would need to say instead

Option C is not dead, but it can no longer be shipped as "document a true-today invariant." It has
two honest forms:

1. **Depend on the bug fix.** Fix the `CompactSourceText` raw-token fallback (make comment-bearing
   nodes reconstruct compact text the same way `attachedSingleSegmentChainMustBreakAfterEquals`
   already does for its one narrow case — AST-reconstruct rather than fall back to raw tokens — while
   still routing the comment itself through the existing claim/`lineSuffix` machinery), then document
   the oracle's source-neutrality as a now-true invariant, with a regression test pinned to an
   exact-limit-boundary comment-bearing case.
2. **Document the target, not the current state.** State in `ARCHITECTURE.md` that this seam's
   oracle *is intended to be* source-neutral and currently is **not proven to be**, for comment-bearing
   chains, pending the `CompactSourceText` fix tracked in #473 — i.e., document a known gap with a
   forward pointer to the issue, rather than asserting an invariant.

Per this repo's own documentation rule (state current-state, not aspiration-as-fact), form 2 without
the fix landed is a worse outcome than shipping the fix first and writing form 1. Either way, Option
C's cost is no longer "~0.5 day, no code changes" — it is gated on a separate bug fix whose size is
not yet scoped (the fix's own corpus-verification pass has not been run).

## 5. Option D — decide the seam structurally (biome-style)

The earlier draft asserted biome "does the same string-oracle move" as `methodCallRootFirstLine`.
Checked against biome's source, that is wrong for the layout-selection step, and only true for one
fallback arm inside it.

### How biome actually decides

`AnyJsAssignmentLike::layout` (`crates/biome_js_formatter/src/utils/assignment_like.rs:654-751`)
picks one of several named layouts (`OnlyLeft`, `BreakAfterOperator`, `BreakLeftHandSide`,
`NeverBreakAfterOperator`, `Fluid`, ...) through an ordered chain of **structural** predicates: is
there no right-hand side; is the right side a suppressed/require/type-alias/chained-assignment
special case; does the left side need to break; `should_break_after_operator` (an AST pattern match
over binary-likeness, conditional-test shape, sequence expressions, class decorators, unary/await/
yield unwrapping, and literal/string checks — `assignment_like.rs:971-1032`, genuinely no rendered-
width read); is the left side short; is the right side a bare string literal; is the right side a
"poorly breakable member/call chain." None of those steps measure a rendered column. Only when
every structural rule falls through does biome reach `AssignmentLikeLayout::Fluid`
(`assignment_like.rs:750`), and only `Fluid`'s own arm (`assignment_like.rs:1099-1111`) uses the
group-id + `indent_if_group_breaks` machinery Option B examined — a *last-resort* fallback layout,
not the layout family's general mechanism.

One nuance the "mostly structural, no width" framing needs to keep honest:
`is_poorly_breakable_member_or_call_chain` (`assignment_like.rs:1164-1246`), one of the structural
predicates feeding `layout`, itself uses a numeric threshold — `line_width / 4` — to decide whether a
call's sole argument counts as "short" (`is_short_argument`, `:1249+`). That threshold is applied to
argument *text length* at doc-build time via a fixed fraction of the configured line width, not a
true-rendered-column fits-check against remaining budget; it is closer to a structural constant
(one quarter of a configured option) than to the render-time `openerLineWidth`/`fitsOnOneLine`
probes `VariableInitializerLayout` uses today. It is a width-flavored heuristic living inside an
otherwise structural predicate, not proof that biome's general layout selection is width-driven.

### What frmtr's seam would need, structurally

Reframing this seam's decision (attach after `name = ` vs. break) as biome-style structural rules
means classifying the chain by shape rather than by measured first-line width. Candidate structural
classes, drawn from what the current width-gated sites already special-case (§1's five/six sites),
each of which is a plausible rule in a biome-style ordered chain:

- **Root kind**: object-creation-rooted chain (has its own constructor-opener fallback today,
  `:2581-2599`) vs. plain-identifier/field-access-rooted chain vs. mixed field/call root
  (`mixedFieldMethodCallFirstLine`).
- **Chain arity**: single-segment chain (the narrow case `attachedSingleSegmentChainMustBreakAfterEquals`
  already forces unconditionally, `:2482-2502`) vs. multi-segment.
- **Trailing-tail shape**: zero-argument trailing selector (the case the forced-break rule targets)
  vs. an argument-bearing trailing call.
- **Comment presence**: comment-bearing initializer (three of six sites) vs. comment-free — today a
  routing decision, not a width one; a structural rule could subsume it without change.
- **Argument count / complexity of the root call** (biome's own `is_breakable_call` arity check —
  0 args never breakable, 1 short arg not breakable, 2+ or 1 long arg breakable) as a proxy for "is
  this chain worth trying to keep on the assignment line."

### What changes vs. today, qualitatively

Today's decision is *continuous* in the chain's predicted first-line width: a chain one character
under the limit attaches, one character over breaks, regardless of shape. A structural
classification collapses that into shape buckets — e.g., "single-segment object-creation chain
always breaks" (already true, via the forced rule) vs. "multi-segment chain with a short root always
attaches, long root always breaks" with no width check at the boundary. The qualitative risk is
exactly the kind of discontinuity the current width-near-the-limit corpus fixtures
(`format/variable-declaration*`, `format/method-call-chain*`) are shaped to catch: two initializers
that differ by one argument's name length, both structurally "multi-segment plain-root chain,"
would render identically under a structural rule even though one fits after `=` and the other
doesn't by one column. Fixtures anchored on that exact near-limit behavior would need to be re-audited
for whether the corpus actually contains that discontinuity today or whether it is untested; this
proposal cannot determine that without running the suite (deferred, see §6).

### Oscillation / idempotence fit

A purely structural rule is idempotence-safe by construction — it reads AST shape, never rendered
column or source shape, so it cannot oscillate between passes. This is a genuine advantage over
Option A (which still measures a predicted width) and a decisive advantage over Option C as it
stands today (which currently measures source-shaped text via the `CompactSourceText` bug). It does
not need `methodCallRootFirstLine` at all for the sites it covers — a structural classification
answers attach-vs-break without ever building or reading the predicted first-line string.

### Cost and risk

Lower implementation cost than Option A's two-shape rewrite (no new Doc-ranking machinery, no
`bestFittingFirstLine` wiring) but a materially different **kind** of risk: the rule set has to be
reverse-engineered from the corpus's existing width-driven behavior well enough to not regress
near-limit fixtures, which is exactly the class of change this repo's fixture suite is built to
catch and exactly the class of change that cannot be evaluated without running it. Getting the
buckets right (§ above) is a design task in its own right, plausibly comparable in size to Option A's
implementation cost even though the resulting code is simpler.

## 6. Deferred: the one-site prototype

The issue's Option-A deliverable calls for a one-site prototype and a corpus delta. This checkout is
read-only for the duration of this revision (another agent is actively editing
`MethodCallChainPrinter.java` and running gradle here), so no prototype, build, or corpus run was
attempted for this doc. The prototype is deferred; whoever implements should run it before committing
to Option A or Option D as the final recommendation. Concretely:

- **Site**: `initializerFansWidthDrivenTwoSelectorChain`'s branch at `VariableInitializerLayout.java:455`
  — comment-free (guarded by `trailingCommentLayout.preSemicolonInitializerComment(variable) ==
  Doc.EMPTY` per the original draft's reading), so it needs no shared-segment discipline and isolates
  the ranking mechanics from the comment question.
- **Where the decision actually lives.** The `openerLineWidth`-vs-`firstLine` comparison the
  prototype needs to replace is **not** inline at `:455`. `:455` builds `fannedChain` and then calls
  the shared `variableWithMethodCallChain` (`VariableInitializerLayout.java:2570-2604`) — the same
  function `:388`, `:429`, `:2218`, and `:2638` call, and the one that actually runs steps 1-3 in §1
  including the `openerLineWidth` comparison at `:2600-2603`. That function is shared by five of the
  six sites; editing it in place is not a one-site experiment.
- **Prototype scoping (required): isolated duplicate, not a patch to the shared tail.** The
  prototype must add a **new, separate** method — e.g. `variableWithMethodCallChainPrototype` — that
  copies `variableWithMethodCallChain`'s body (steps 1-3 of §1) and swaps only step 3's comparison for
  the ranked-Docs form (Option A) or the structural predicate chain (Option D, §5). Route `:455`'s
  call to this new method instead of to `variableWithMethodCallChain`. Do **not** edit
  `variableWithMethodCallChain` itself, and do **not** change what `:388`, `:429`, `:2218`, or `:2638`
  call — they must keep calling the original, unmodified `variableWithMethodCallChain` throughout the
  experiment. This is what keeps the prototype a one-site experiment: the other five sites' behavior
  is provably unaffected because their call target never changed, not because the shared function
  happened to behave the same after edits.
- **Diff shape for Option A's prototype**: inside the new `variableWithMethodCallChainPrototype`,
  replace step 3's `openerLineWidth`-vs-`firstLine` comparison with two Docs — the existing attached
  shape and a new `Doc.concat(Doc.text(name + " ="), Doc.indent(Doc.concat(Doc.HARD_LINE, chain)))`
  broken shape — ranked via `Doc.bestFittingFirstLine`; both built from the one already-rendered
  chain Doc, not two independent renders.
- **Diff shape for Option D's prototype**: inside the same new method, replace step 3 with a
  structural predicate chain over root kind / chain arity / trailing-tail shape (§5), with no width
  read at this site at all; run it against the same fixture set to see where it disagrees with the
  width-gated baseline.
- **Corpus metrics to capture for either prototype**: `./gradlew test` pass/fail on
  `format/variable-declaration*` and `format/method-call-chain*` fixtures (byte-identical or not, and
  which lines differ); a `--check`/idempotence pass over kafka and camel (the corpora already used
  elsewhere in this investigation lineage) counting files whose output changes and whether any
  newly-diverging file round-trips non-idempotently; and, for Option D specifically, a manual count of
  how many near-limit lines (within ±3 columns of `options.lineWidth()`) change attach/break
  classification versus the width-gated baseline, since that is exactly the discontinuity §5 flags as
  the open risk.

Both the recommendation below and any final choice between Options A and D should be treated as
**conditional on this prototype's outcome**, not as settled by this document alone.

## 7. Recommendation

**Recommend running the deferred one-site prototype (§6) for Option D first, with Option A as the
fallback if D's structural buckets do not hold up against the corpus; do not pursue Option B for this
seam; do not ship Option C until the `CompactSourceText` bug is fixed.**

This differs from the rejected draft's recommendation (Option C now, A only partially, B deferred
indefinitely) because two of the three premises that draft anchored on have flipped:

- **Option C's premise is now false, not just under-evaluated.** The oracle is not source-neutral
  today for comment-bearing chains — §4 traces this to a live `CompactSourceText` bug, not a design
  choice. Documenting "source-neutral by design" now would assert a fabricated invariant. C becomes
  viable only after that bug is fixed, at which point it is still a reasonable outcome, but it cannot
  ship first.
- **Option A's blast radius is larger than the rejected draft credited.** The comment-hazard finding
  that limited A to 2 of 5 sites was root-caused to a different bug (§2, ground truth A); with a
  shared-segment discipline, all six `name = ` sites are structurally reachable by the ranked-Docs
  approach. A is a larger, more real win than previously assessed — but its cost (new Doc-shape code,
  shared-segment discipline, corpus verification) is also real and unreduced.
- **Option B is not the cheap win the "primitive already exists" framing might suggest.** frmtr
  already has biome's group-id/`ifBreak` combinators (§3) — but this seam's problem (opener chosen
  ahead of its decider) is not the problem those combinators, or the queue-lookahead biome adds on
  top of them, solve. Building queue lookahead into `DocWidths` is a real, scoped, cheaper-than-before
  renderer change, but it would not retire the oracle at this seam regardless. It stays deferred to a
  future seam whose reader legitimately comes after its decider.
- **Option D is new and, on a fair read of biome, is closer to biome's actual design than the width-
  gated oracle is.** Biome's own layout selection is structural first, width-based only in a
  last-resort fallback (§5) — the opposite of what the rejected draft claimed. D also sidesteps both
  Option A's residual width-measurement risk and Option C's current correctness problem, at the cost
  of a harder-to-derive rule set and a real discontinuity risk at near-limit fixtures that only the
  deferred prototype (§6) can settle.

Between A and D, D is offered as the first prototype target because it removes the oracle from the
seam entirely (no `methodCallRootFirstLine` string, no predicted-width comparison, hence no exposure
to further `CompactSourceText`-class bugs) and is idempotence-safe by construction — but this
recommendation is explicitly conditional: if the §6 prototype shows D's structural buckets disagree
with the corpus's near-limit width-gated behavior often enough to be a real regression risk, A (§2)
is the fallback, scoped to all six sites via the shared-segment discipline rather than the two sites
the rejected draft limited it to.

**Sequencing:**
1. Land the fix for **issue #473** (`CompactSourceText` raw-token-fallback measured-text divergence)
   — this is a prerequisite for Option C, which is blocked on #473 regardless of whether C ships now,
   since the divergence is a source-neutrality hazard independent of this proposal.
2. Run the §6 prototype for Option D at `:455`; if the near-limit corpus check shows no material
   discontinuity, extend it to the other five `name = ` sites.
3. If D's discontinuity risk turns out to be real, fall back to Option A (§2) at all six sites using
   the shared-segment discipline.
4. `:2708` (lambda-arrow) stays out of scope for this round regardless of which of A/D is chosen; it
   keeps using the oracle unconditionally (§1).
5. Leave Option B's queue-lookahead gap documented (§3) as a known, scoped renderer limitation for a
   future seam that genuinely needs a reader-after-decider primitive — not for this one.

**Effort estimate:**
- Issue #473 (`CompactSourceText` measured-text divergence fix): unscoped by this doc — needs its own
  investigation; treat as a dependency, not a sub-task, of this proposal.
- Option D prototype (§6, one site): ~1-2 days including the near-limit discontinuity check.
- Option D (if the prototype holds, extended to all six `name = ` sites): ~2-4 days beyond the
  prototype — deriving and wiring the structural rule set, no new Doc/ranking machinery.
- Option A (fallback, all six sites via shared-segment discipline): ~4-6 days — larger than the
  rejected draft's ~3-5 day estimate for 2 sites, because it now covers all six and needs the
  shared-segment discipline built and verified for the three comment-bearing sites.
- Option B (queue lookahead in `DocWidths`/`DocRenderer`): a multi-day-to-low-single-digit-week
  renderer change if a future seam needs it — smaller than the rejected draft's ~2-3 week estimate
  (the Doc/group-id layer already exists), but not undertaken for this seam regardless of size,
  since §3 shows it would not retire the oracle here even after landing.
- Option C (blocked on #473; after it lands): ~0.5 day doc paragraph + DoD amendment, as originally
  scoped — now correctly sequenced after, not instead of, the bug fix.
