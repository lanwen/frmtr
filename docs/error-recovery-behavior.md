# Error Recovery Behavior

Status: current behavior specification.

This document describes the implemented formatter behavior for JavaParser parse-error recovery. The supported recovery
envelope described here is current behavior, not a permanent limit on what recovery may support later.

## Problem Statement

`frmtr` historically treated any JavaParser parse problem as a complete formatting failure. JavaParser can often recover
from syntax errors by returning a partial compilation unit with `UNPARSABLE` nodes, and formatters such as Biome
represent invalid syntax as bogus nodes that are printed verbatim while valid surrounding syntax is still formatted.

From a formatter user's perspective, a small broken region should not block formatting the valid code around it once the
parser has recovered enough structure to identify safe sibling boundaries. The formatter should preserve the broken
source text rather than repair it, while keeping the old fail-on-parse-error behavior available for users and automation
that require it.

## Solution

Parser recovery is the default parse-error behavior, selected by `FormatterOptions.ParseErrorBehavior.RECOVER`. When
JavaParser returns a partial compilation unit with parse problems, the formatter formats valid parsed siblings and
preserves recovered source islands verbatim. The old strict behavior remains available through `ParseErrorBehavior.FAIL`,
under which any parse problem raises a formatter exception. The CLI uses `RECOVER` by default and exposes an option to
select `FAIL`; the core `Frmtr.format(...)` API stays string-or-exception and does not surface recovered parse problems.

Recovery is conservative about where it can happen. It only recovers inside formatter-owned sibling lists where the
formatter already understands sequencing: whole-file structural lists, import declarations when safe, module directives
when safe, class/interface/record type members, block statements, switch entries, enum constants, and annotation
declaration members. Recovery supports prefix, between, and suffix raw gaps in these lists when safe boundaries exist.

Syntax fragments such as expressions, switch selectors, labels, guards, type parameters, annotation pairs, annotation
member defaults, and declaration headers are not recovered surgically. A node marked parsed may still contain unparsed
descendants, so recovery inspects descendants, not just the node's own parsedness. If an unparsed descendant appears
inside a syntax fragment rather than an owned sibling list, recovery widens to the nearest safe formatter-owned boundary;
if no safe boundary exists, formatting fails.

Recovery is not a fixer. It never prints synthetic braces, semicolons, labels, expressions, or JavaParser placeholder
text. Recovered output may still be invalid Java because the broken source is intentionally preserved. Because the AST is
partial, all transforms (import sorting and the rest) are skipped whenever JavaParser reports parse problems, so the
partial tree is printed rather than reordered.

## Guarantees & Non-guarantees

Supported recovery envelope:

- Valid siblings before and after a broken region format normally at the owning list's indentation.
- Broken regions are preserved as raw islands with source text and indentation intact (subject to the existing raw
  trailing-whitespace option); the raw island owns the surrounding original whitespace.
- Comments fully inside a raw island are raw-accounted so guardrails do not report them missing and they are not
  duplicated. Comments crossing a recovery boundary widen the raw region or fail the plan; comments attached to valid
  siblings keep normal comment placement.
- Formatter pragmas fully inside a raw island are preserved as raw text but do not affect formatter state; parsed pragmas
  outside raw islands keep their normal behavior, and an explicit raw pragma still overrides recovery.
- Recovered output is idempotent: repeated formatting stabilizes even when the source remains invalid.

Non-guarantees:

- No manual tokenizer or source-scanner fallback. When JavaParser returns no compilation unit (or throws before a
  partial result is available), recovery fails with a formatter exception.
- No synthetic Java repair (missing braces, semicolons, labels, or expressions) and no JavaParser placeholder text.
- No surgical recovery inside expressions, type arguments, annotations, declaration headers, or switch
  selectors/labels/guards.
- No guarantee that recovered output is parseable Java.
- No diagnostics result type from `Frmtr.format(...)` and no CLI warnings for recovered parse problems yet.
- Debug labels for recovered regions carry internal diagnostic names plus human-readable line/column ranges, but the
  label strings are not a public compatibility guarantee.

## Implemented Slices

The recovery implementation is complete for the current formatter-owned list boundaries:

1. Public option and parse boundary.
   - `ParseErrorBehavior.RECOVER` is the default and `FAIL` preserves strict parse-error behavior.
   - The internal parse-result boundary carries parser problems and skips transforms when JavaParser reports parse
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
     rule. Recovery test coverage is described in [testing-strategy.md](testing-strategy.md).

Remaining diagnostics follow-up:

- Add a richer formatting result API or debug path that exposes recovered parse problems.
- Use that result in the CLI to optionally report warnings.
- Revisit debug doc parse-problem metadata once the result shape is explicit.
