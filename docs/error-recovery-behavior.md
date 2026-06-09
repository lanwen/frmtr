# Error Recovery Behavior

Status: proposed implementation plan.

This document captures the planned formatter behavior for JavaParser parse-error recovery. It is intentionally written as
the current supported recovery envelope to implement first, not as a permanent limit on what recovery may support later.

## Problem Statement

`frmtr` currently treats JavaParser parse problems as a complete formatting failure. JavaParser can sometimes recover from
syntax errors by returning a partial compilation unit with `UNPARSABLE` nodes, and formatters such as Biome use a similar
idea by representing invalid syntax as bogus nodes that are printed verbatim while valid surrounding syntax can still be
formatted.

From a formatter user's perspective, a small broken region in a file should not prevent formatting the valid code around
it when the parser has recovered enough structure to identify safe sibling boundaries. The formatter should preserve the
broken source text rather than trying to repair it, and it should keep the previous fail-on-parse-error behavior available
for users and automation that require it.

## Solution

Introduce parser recovery as the default parse-error behavior. When JavaParser returns a partial compilation unit with
parse problems, the formatter will format valid parsed siblings and preserve recovered source islands verbatim. The old
behavior remains available through an explicit fail mode.

The first implementation is conservative about where recovery can happen. It only recovers inside formatter-owned sibling
lists where the formatter already understands sequencing: whole-file structural lists, import declarations when safe,
module directives when safe, type members, block statements, switch entries, and switch statement-group bodies. Syntax
fragments such as expressions, switch selectors, labels, guards, type parameters, annotation pairs, and declaration
headers are not recovered surgically. If a syntax fragment contains unparsed descendants, recovery widens to the nearest
safe formatter-owned boundary; if no safe boundary exists, formatting fails.

Recovery is not a fixer. It never prints synthetic braces, semicolons, labels, expressions, or JavaParser placeholder
text. Recovered output may still be invalid Java because the invalid source is intentionally preserved.

## User Stories

1. As a developer editing Java, I want the formatter to keep formatting valid code around a broken statement, so that a
   temporary syntax error does not block routine formatting.
2. As a developer editing Java, I want broken syntax preserved exactly enough to continue editing it, so that formatting
   does not lose source text.
3. As a developer editing Java, I want valid siblings after a broken region to resume normal formatting, so that one
   syntax error does not poison the rest of the file.
4. As a developer editing Java, I want valid siblings before a broken region to format normally, so that partial recovery
   still improves the file.
5. As a developer editing Java, I want broken source before the first valid sibling to be preserved when safe, so that
   recovery works for prefix errors in owned lists.
6. As a developer editing Java, I want broken source after the last valid sibling to be preserved when safe, so that
   recovery works for trailing incomplete edits.
7. As a developer editing Java, I want broken member declarations between valid members to be preserved as raw islands,
   so that valid class body members still format.
8. As a developer editing Java, I want broken top-level declarations between valid top-level declarations to be preserved
   as raw islands, so that the rest of the file still formats.
9. As a developer editing Java, I want broken block statements between valid statements to be preserved as raw islands,
   so that method bodies can recover locally.
10. As a developer editing Java, I want switch statements and switch expressions to recover at switch-entry boundaries,
    so that valid cases can still format around broken cases.
11. As a developer editing Java, I want switch statement groups to recover at statement boundaries, so that valid
    statements inside a case group can still format around a broken statement.
12. As a developer editing Java, I want broken switch selectors, labels, or guards to use a wider raw boundary, so that
    the formatter does not reconstruct switch syntax unsafely.
13. As a developer editing Java, I want parser lexical failures with no usable AST to still fail clearly, so that the
    formatter does not guess syntax without JavaParser structure.
14. As a developer editing Java, I want a fail mode, so that CI or editor integrations can keep the old parse-error
    behavior.
15. As a CLI user, I want the CLI to use recovery by default but expose a fail option, so that command-line behavior
    matches the core API while preserving strict workflows.
16. As a formatter API user, I want `Frmtr.format(...)` to keep returning a string or throwing, so that the first
    recovery implementation does not force a public result type migration.
17. As a formatter API user, I want recovered parse problems to be silent on successful formatting for now, so that the
    existing API remains simple.
18. As a formatter maintainer, I want TODO markers where parse problems become available, so that a follow-up diagnostic
    API has clear integration points.
19. As a formatter maintainer, I want debug doc output to label recovered regions, so that recovery choices are visible
    when inspecting formatter IR.
20. As a formatter maintainer, I want debug labels to show human-readable line and column ranges, so that recovered
    regions can be located in source.
21. As a formatter maintainer, I want debug label names to remain diagnostic and evolvable, so that implementation details
    are not frozen as public API.
22. As a formatter maintainer, I want transforms skipped when parse problems are present, so that normalization does not
    mutate a partial AST before recovery printing.
23. As a formatter maintainer, I want transform skipping to be reconsiderable later, so that proven recovery-safe
    transforms can opt in in a future design.
24. As a formatter maintainer, I want comments inside recovered raw islands to be raw-accounted, so that guardrails do not
    report them missing and the formatter does not duplicate them.
25. As a formatter maintainer, I want only fully contained comments to be owned by raw islands, so that comments attached
    to valid siblings stay with normal comment placement.
26. As a formatter maintainer, I want comments crossing recovery boundaries to widen or fail the recovery plan, so that no
    comment is split between raw and structured output.
27. As a formatter maintainer, I want formatter pragmas inside raw islands preserved but inactive, so that unparsed source
    does not mutate formatter state.
28. As a formatter maintainer, I want parsed formatter pragmas outside raw islands to keep their normal state-machine
    behavior, so that recovery stays orthogonal to explicit raw regions.
29. As a formatter maintainer, I want explicit raw pragmas to override recovery formatting, so that user-requested raw
    preservation remains stronger than parser recovery.
30. As a formatter maintainer, I want recovered output to be idempotent, so that repeated formatting stabilizes even when
    the source remains invalid.
31. As a formatter maintainer, I want recovery fixtures separate from normal parseable formatter fixtures, so that invalid
    output expectations do not weaken the parseable fixture contract.
32. As a formatter maintainer, I want existing parse-error diagnostics tests split by intent, so that fail-mode diagnostics
    remain covered while default recovery gains its own coverage.

## Implementation Decisions

- Add a public `ParseErrorBehavior` enum to formatter options.
- The enum values are `RECOVER` and `FAIL`.
- `RECOVER` is the default behavior for formatter options.
- `FAIL` preserves the old behavior: any JavaParser parse problem causes a formatter exception.
- `requirePragma` remains a pre-parse gate. When pragma is required and no recognized pragma is present, the formatter
  returns the original source unchanged and does not attempt recovery.
- `debugDoc` continues to bypass pragma gating, but it follows the configured parse-error behavior. With default options,
  debug output recovers and labels recovered regions.
- The core `Frmtr.format(...)` API remains string-or-exception. Successful recovery does not expose parse problems yet.
- The parse wrapper will include TODO comments at the parse-result boundary for a near-term diagnostics API that can later
  expose recovered parse problems to debug output, API callers, and the CLI.
- Recovery uses JavaParser's recovered AST only. There is no manual tokenizer or source-scanner fallback when JavaParser
  does not return a compilation unit.
- When JavaParser returns no compilation unit, recovery fails with a formatter exception.
- When JavaParser throws before a partial result is available, recovery fails with a formatter exception.
- When recovery fails, the exception message keeps the existing parse-error prefix and adds recovery-specific context.
- Recovered formatting is not guaranteed to produce parseable Java.
- Recovery never prints synthetic repair tokens or JavaParser placeholder text.
- Source regions are represented by character offsets internally.
- Line and column positions are used for diagnostics and debug labels, not for raw slicing.
- Raw recovered islands are emitted as labeled text docs in the first implementation, not as a new document algebra node.
- Recovered raw islands preserve source text and source indentation exactly, except for the existing raw trailing
  horizontal whitespace option.
- When raw trailing whitespace preservation is disabled, recovered raw islands strip trailing spaces and tabs
  line-by-line.
- Formatter-created line breaks still use the configured line ending. Raw islands preserve their source line endings.
- Raw islands own their original whitespace before and after the broken region. Normal separator policy is not synthesized
  across the recovered gap.
- Valid siblings before and after a raw recovered gap format normally at the owning list's indentation level.
- Recovery supports prefix, between, and suffix raw gaps in known formatter-owned lists when safe boundaries exist.
- A node marked parsed may still contain unparsed descendants. Recovery decisions must inspect descendants, not only the
  node's own parsedness.
- If an unparsed descendant appears inside a syntax fragment rather than an owned sibling list, recovery widens to the
  nearest formatter-owned ancestor that can preserve a safe raw boundary.
- Known first-phase recovery lists are whole-file structural sequences, import declarations when safe, module directives
  when safe, top-level declarations, type members, block statements, switch entries, and switch statement-group
  statements.
- Switch recovery is in scope for the first implementation.
- Switch recovery starts at switch-entry and switch statement-group statement-list boundaries.
- Switch selectors, labels, and guards are not recovered surgically.
- A broken switch selector raw-preserves the whole switch construct when possible.
- A broken switch label or guard raw-preserves the affected switch entry when safe, otherwise the whole switch block.
- Import sorting and all other transforms are skipped whenever JavaParser reports parse problems.
- Transform skipping is global for the first implementation, even if the recovered region appears unrelated to imports.
- Later designs may allow individual transforms to declare recovery safety.
- Comments whose source range is fully inside a recovered raw island are accounted as raw-rendered.
- Comments outside a recovered raw island, or attached to valid sibling syntax, remain under normal comment placement.
- Comments crossing a recovery boundary cause the recovery planner to widen the raw region or fail if widening is unsafe.
- Formatter pragmas fully inside recovered raw islands are printed as raw text but do not affect formatter state.
- Parsed formatter pragmas outside raw islands keep their normal state-machine behavior across recovery gaps.
- Explicit formatter raw pragmas take precedence over recovery and prevent partial formatting inside the raw-preserved
  pragma region.
- Recovery debug labels include internal diagnostic names and human-readable line/column ranges.
- Debug label strings are not a public compatibility guarantee.
- The CLI uses the default `RECOVER` behavior and exposes an option to select `FAIL`.
- The CLI does not print recovery warnings in the first implementation because the core API does not yet return
  diagnostics.
- Recovery behavior is documented here, and implementation changes should update the architecture and formatter coverage
  docs to point to this document.

## Testing Decisions

- Recovery tests should assert external behavior: returned formatted text, idempotence, exception behavior, and visible
  debug labels. They should not test internal recovery data structures directly unless those structures expose a stable
  package-private contract that is intentionally isolated.
- Dedicated recovery fixtures should live separately from the normal parseable formatter fixture corpus.
- Good recovery fixtures include an input, expected recovered output, a second-format idempotence assertion, and a fail
  mode assertion for the same source.
- Recovery fixtures should cover block statement gaps, member declaration gaps, top-level declaration gaps, prefix gaps,
  suffix gaps, switch entry gaps, switch statement-group gaps, comments inside raw islands, comments next to valid
  siblings, formatter pragmas inside raw islands, and formatter pragmas outside raw islands.
- Fail-mode tests should keep parse-error source context coverage.
- Existing parse-error tests should be split by intent rather than mechanically rewritten. Diagnostics tests should
  select `FAIL`; recoverable examples should assert default recovery.
- Unsupported syntax that produces no usable JavaParser compilation unit should continue to assert formatter failure.
- Normal parseable formatter fixtures should continue to assert parseability where they already do. Recovery fixtures do
  not need to produce parseable Java.
- Guardrail-oriented tests should enable debug guardrails where useful to prove comments in raw islands are accounted once.
- Transform behavior should be tested by proving imports are not sorted when parse problems are present, while ordinary
  parseable files still run transforms.
- Debug doc tests should assert that recovered-region labels appear, but should avoid making exact label strings more
  stable than necessary.

## Out of Scope

- No manual tokenizer-level recovery when JavaParser cannot return a compilation unit.
- No synthetic Java repair such as adding missing braces, semicolons, labels, or expressions.
- No first-phase diagnostics result type from `Frmtr.format(...)`.
- No CLI warnings for recovered parse problems until a diagnostics API exists.
- No transform opt-in while parse problems are present.
- No surgical recovery inside expressions, type arguments, annotations, declaration headers, switch selectors, switch
  labels, or switch guards.
- No guarantee that recovered output is parseable Java.
- No public stability guarantee for recovered-region debug label text.

## Further Notes

The design intentionally follows the formatter's existing state-machine direction. Recovery is another outer gate before
normal structured formatting: decide whether a region is safely parsed, decide whether a raw island must be emitted, then
narrow valid siblings toward their existing declaration, statement, expression, or switch-specific printers.

Biome's bogus-node model is useful as a conceptual reference, but `frmtr` should align with JavaParser terminology and
capabilities. The implementation should prefer JavaParser's `UNPARSABLE` state and source ranges, plus explicit
formatter-owned sibling lists, rather than introducing a second parser vocabulary into the public API.

## Implementation Plan

1. Add the public option shape.
   - Add `ParseErrorBehavior` with `RECOVER` and `FAIL`.
   - Make formatter defaults use `RECOVER`.
   - Add static factory compatibility through the existing option factories.
   - Add CLI parsing for the fail behavior.
   - Update fail-mode parse-error tests to select `FAIL`.

2. Introduce a parse result boundary.
   - Replace the current parse method's direct compilation-unit return with an internal parse result object that carries
     the compilation unit, parse problems, whether recovery was used, and a failure reason when no safe result exists.
   - Add TODO comments at this boundary for the follow-up diagnostics API.
   - Keep successful recovery silent through the public string-returning API.

3. Add source-region infrastructure.
   - Add an offset-based source text helper that maps JavaParser node ranges to source offsets and slices raw source
     regions.
   - Support line/column formatting for debug labels.
   - Apply the raw trailing-horizontal-whitespace option to recovered raw slices.
   - Keep source line endings and indentation inside recovered raw slices.

4. Add recovered-region and comment accounting helpers.
   - Add a helper that creates labeled raw docs for recovered regions.
   - Account fully contained comments as raw-rendered through the existing comment tracking and guardrail mechanisms.
   - Detect comments crossing boundaries and either widen the region or fail recovery.

5. Add a reusable owned-list recovery planner.
   - Model prefix, between, and suffix gaps for an ordered sibling list.
   - Accept callbacks for valid sibling formatting and raw gap emission.
   - Inspect unparsed descendants, not only each sibling's own parsedness.
   - Fail when a recovered region cannot be tied to a safe owned-list boundary.

6. Wire ordinary statement, member, top-level, import, and module directive recovery.
   - Integrate the planner into block statement sequencing.
   - Integrate the planner into type member sequencing.
   - Integrate the planner into compilation-unit structural sequencing.
   - Integrate the planner into import and module directive lists only where JavaParser exposes safe sibling boundaries.
   - Preserve explicit raw pragma behavior as stronger than recovery.

7. Wire switch recovery.
   - Integrate recovery into switch entry sequencing.
   - Integrate recovery into switch statement-group statement sequencing.
   - Add wider raw fallbacks for broken switch selectors, labels, and guards.
   - Keep statement switch selection in the statement printer and reusable switch grammar in the switch printer.

8. Skip transforms on recovered parses.
   - Bypass the transform pipeline whenever JavaParser returned parse problems.
   - Keep normal transforms for parseable files.
   - Add tests proving import sorting still happens for parseable input and does not happen for recovered input.

9. Add recovery fixtures and debug coverage.
   - Add dedicated recovery fixtures for each supported boundary.
   - Assert default recovery output and idempotence.
   - Assert fail mode throws with the existing source-context message prefix.
   - Assert debug doc output labels recovered regions.

10. Update architecture and coverage docs.
    - Link this document from the formatter coverage map and architecture overview.
    - Document the parse-to-transform-to-print flow split for recovered versus fully parsed sources.
    - Document the supported recovery boundaries and transform-skip rule.

11. Follow up with diagnostics.
    - Add a richer formatting result API or debug path that exposes recovered parse problems.
    - Use that result in the CLI to optionally report warnings.
    - Revisit debug doc parse-problem metadata once the result shape is explicit.
