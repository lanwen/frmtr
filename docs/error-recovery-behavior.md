# Error Recovery Behavior

Status: current behavior and historical design notes.

This document captures the implemented formatter behavior for JavaParser parse-error recovery. It also preserves useful
design decisions from the implementation plan; the supported recovery envelope described here is current behavior, not a
permanent limit on what recovery may support later.

## Problem Statement

`frmtr` historically treated JavaParser parse problems as a complete formatting failure. JavaParser can sometimes recover
from syntax errors by returning a partial compilation unit with `UNPARSABLE` nodes, and formatters such as Biome use a
similar idea by representing invalid syntax as bogus nodes that are printed verbatim while valid surrounding syntax can
still be formatted.

From a formatter user's perspective, a small broken region in a file should not prevent formatting the valid code around
it when the parser has recovered enough structure to identify safe sibling boundaries. The formatter should preserve the
broken source text rather than trying to repair it, and it should keep the previous fail-on-parse-error behavior available
for users and automation that require it.

## Solution

Parser recovery is the default parse-error behavior. When JavaParser returns a partial compilation unit with parse
problems, the formatter formats valid parsed siblings and preserves recovered source islands verbatim. The old behavior
remains available through an explicit fail mode.

The implementation is conservative about where recovery can happen. It only recovers inside formatter-owned sibling
lists where the formatter already understands sequencing: whole-file structural lists, import declarations when safe,
module directives when safe, class/interface/record type members, block statements, switch entries, enum constants, and
annotation declaration members. Syntax fragments such as expressions, switch selectors, labels, guards, type parameters,
annotation pairs, annotation member defaults, and declaration headers are not recovered surgically. If a syntax fragment
contains unparsed descendants, recovery widens to the nearest safe formatter-owned boundary; if no safe boundary exists,
formatting fails.

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
11. As a developer editing Java, I want enum constants to recover at enum constant-list boundaries, so that valid
    constants can still format around broken constants.
12. As a developer editing Java, I want annotation declaration members to recover at annotation body member boundaries, so
    that valid members can still format around broken members.
13. As a developer editing Java, I want broken switch selectors, labels, or guards to use a wider raw boundary, so that
    the formatter does not reconstruct switch syntax unsafely.
14. As a developer editing Java, I want parser lexical failures with no usable AST to still fail clearly, so that the
    formatter does not guess syntax without JavaParser structure.
15. As a developer editing Java, I want a fail mode, so that CI or editor integrations can keep the old parse-error
    behavior.
16. As a CLI user, I want the CLI to use recovery by default but expose a fail option, so that command-line behavior
    matches the core API while preserving strict workflows.
17. As a formatter API user, I want `Frmtr.format(...)` to keep returning a string or throwing, so that recovery does not
    force a public result type migration.
18. As a formatter API user, I want recovered parse problems to be silent on successful formatting for now, so that the
    existing API remains simple.
19. As a formatter maintainer, I want clear integration points where parse problems become available, so that a follow-up
    diagnostic API has a narrow boundary.
20. As a formatter maintainer, I want debug doc output to label recovered regions, so that recovery choices are visible
    when inspecting formatter IR.
21. As a formatter maintainer, I want debug labels to show human-readable line and column ranges, so that recovered
    regions can be located in source.
22. As a formatter maintainer, I want debug label names to remain diagnostic and evolvable, so that implementation details
    are not frozen as public API.
23. As a formatter maintainer, I want transforms skipped when parse problems are present, so that normalization does not
    mutate a partial AST before recovery printing.
24. As a formatter maintainer, I want transform skipping to be reconsiderable later, so that proven recovery-safe
    transforms can opt in in a future design.
25. As a formatter maintainer, I want comments inside recovered raw islands to be raw-accounted, so that guardrails do not
    report them missing and the formatter does not duplicate them.
26. As a formatter maintainer, I want only fully contained comments to be owned by raw islands, so that comments attached
    to valid siblings stay with normal comment placement.
27. As a formatter maintainer, I want comments crossing recovery boundaries to widen or fail the recovery plan, so that no
    comment is split between raw and structured output.
28. As a formatter maintainer, I want formatter pragmas inside raw islands preserved but inactive, so that unparsed source
    does not mutate formatter state.
29. As a formatter maintainer, I want parsed formatter pragmas outside raw islands to keep their normal state-machine
    behavior, so that recovery stays orthogonal to explicit raw regions.
30. As a formatter maintainer, I want explicit raw pragmas to override recovery formatting, so that user-requested raw
    preservation remains stronger than parser recovery.
31. As a formatter maintainer, I want recovered output to be idempotent, so that repeated formatting stabilizes even when
    the source remains invalid.
32. As a formatter maintainer, I want recovery examples separate from normal parseable formatter fixtures where fixtures
    are useful, so that invalid output expectations do not weaken the parseable fixture contract.
33. As a formatter maintainer, I want existing parse-error diagnostics tests split by intent, so that fail-mode diagnostics
    remain covered while default recovery gains its own coverage.

## Implementation Decisions

- Formatter options expose a public `ParseErrorBehavior` enum.
- The enum values are `RECOVER` and `FAIL`.
- `RECOVER` is the default behavior for formatter options.
- `FAIL` preserves the old behavior: any JavaParser parse problem causes a formatter exception.
- `requirePragma` remains a pre-parse gate. When pragma is required and no recognized pragma is present, the formatter
  returns the original source unchanged and does not attempt recovery.
- `debugDoc` continues to bypass pragma gating, but it follows the configured parse-error behavior. With default options,
  debug output recovers and labels recovered regions.
- The core `Frmtr.format(...)` API remains string-or-exception. Successful recovery does not expose parse problems yet.
- The parse wrapper keeps a parse-result boundary for a near-term diagnostics API that can later expose recovered parse
  problems to debug output, API callers, and the CLI.
- Recovery uses JavaParser's recovered AST only. There is no manual tokenizer or source-scanner fallback when JavaParser
  does not return a compilation unit.
- When JavaParser returns no compilation unit, recovery fails with a formatter exception.
- When JavaParser throws before a partial result is available, recovery fails with a formatter exception.
- When recovery fails, the exception message keeps the existing parse-error prefix and adds recovery-specific context.
- Recovered formatting is not guaranteed to produce parseable Java.
- Recovery never prints synthetic repair tokens or JavaParser placeholder text.
- Source regions are represented by character offsets internally.
- Line and column positions are used for diagnostics and debug labels, not for raw slicing.
- Raw recovered islands are emitted as labeled text docs, not as a new document algebra node.
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
- Current recovery lists are whole-file structural sequences, import declarations when safe, module directives when safe,
  top-level declarations, class/interface/record type members, block statements, switch entries, enum constants, and
  annotation declaration members.
- Switch recovery starts at switch-entry boundaries.
- Switch selectors, labels, and guards are not recovered surgically.
- A broken switch selector raw-preserves the whole switch construct when possible.
- A broken switch label or guard raw-preserves the affected switch entry when safe, otherwise the whole switch block.
- Import sorting and all other transforms are skipped whenever JavaParser reports parse problems.
- Transform skipping is global, even if the recovered region appears unrelated to imports.
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
- The CLI does not print recovery warnings because the core API does not yet return diagnostics.
- Recovery behavior is documented here, and implementation changes should update the architecture and formatter coverage
  docs to point to this document.

## Testing Decisions

- Recovery tests should assert external behavior: returned formatted text, idempotence, exception behavior, and visible
  debug labels. They should not test internal recovery data structures directly unless those structures expose a stable
  package-private contract that is intentionally isolated.
- Recovery coverage may use focused printer tests or dedicated fixtures, depending on the slice. Fixture resources are
  useful for broad end-to-end examples, but they are not required for every completed recovery boundary.
- Dedicated recovery fixtures, when added, should live separately from the normal parseable formatter fixture corpus.
- Good recovery fixtures include an input, expected recovered output, a second-format idempotence assertion, and a fail
  mode assertion for the same source.
- Recovery coverage should include block statement gaps, member declaration gaps, top-level declaration gaps, prefix
  gaps, suffix gaps, switch entry gaps, enum constant gaps, annotation declaration member gaps, comments inside raw
  islands, comments next to valid siblings, formatter pragmas inside raw islands, and formatter pragmas outside raw
  islands.
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
- No diagnostics result type from `Frmtr.format(...)`.
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

## Implemented Slices

The recovery implementation is complete for the current formatter-owned list boundaries:

1. Public option and parse boundary.
   - `ParseErrorBehavior.RECOVER` is the default and `FAIL` preserves strict parse-error behavior.
   - The internal parse result boundary carries parser problems and skips transforms when JavaParser reports parse
     problems.

2. Shared recovery infrastructure.
   - `SourceText` and `SourceRegion` map JavaParser ranges to source slices.
   - `RecoveredSourceRegions` emits labeled raw source islands and accounts contained comments.
   - `RecoveredListPlanner` plans prefix, between, and suffix gaps for formatter-owned sibling lists.

3. Structural list recovery.
   - Block statement lists, class/interface/record member declaration lists, top-level declaration lists, import
     declaration lists, and module directive lists recover valid siblings around raw unsafe gaps.

4. Switch entry recovery.
   - `SwitchPrinter` owns malformed switch-entry recovery for switch statements and switch expressions.
   - Malformed switch selectors remain unsupported when JavaParser collapses the selector too far for safe recovery.

5. Enum constant-list recovery.
   - `EnumDeclarationPrinter` owns malformed enum constant-list recovery before the enum body semicolon or closing brace.
   - Malformed enum constant shapes that collapse the compilation unit remain unsupported.

6. Annotation declaration member-list recovery.
   - `AnnotationDeclarationPrinter` owns malformed annotation declaration member-list recovery inside annotation bodies.
   - Malformed annotation member shapes that collapse the compilation unit remain unsupported.

7. Documentation and coverage.
   - `ARCHITECTURE.md` and `docs/formatter-coverage.md` record the supported recovery boundaries and transform-skip
     rule.

Remaining diagnostics follow-up:

- Add a richer formatting result API or debug path that exposes recovered parse problems.
- Use that result in the CLI to optionally report warnings.
- Revisit debug doc parse-problem metadata once the result shape is explicit.
