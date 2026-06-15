---
name: adopt-fixture
description: Adopt Java code into frmtr formatter fixtures. Use when asked to turn real code, a snippet, a diff hunk, a pointed-out formatter/code issue, a formatter regression, a line-width limit crossing, or another fixture-worthy edge case into fixture files under frmtr-core/src/test/resources/format, especially when the code must be obfuscated, added to the right existing fixture group, or covered by default and option-variant outputs instead of inline tests.
---

# adopt-fixture

Adopt Java code as file-based formatter fixtures, not inline `Frmtr.format(...)` assertions.
When the adoption is prompted by a formatter issue, regression, line-width limit crossing, over-wide output, or raw `--render-line-width` failure, add a fixture in the same change as the formatter fix; do not leave only a helper/code-path change.

## Workflow

1. Locate the behavior owner before creating files.
   - Search existing fixtures with `rg -n "<construct or identifier>" frmtr-core/src/test/resources/format`.
   - Search nearby formatter code with `rg -n "<printer/helper/rule>" frmtr-core/src/main/java`.
   - For line-limit failures, include search terms from the over-wide construct and the helper/printer branch being changed.
   - Prefer extending an existing fixture folder when it already covers the same printer branch, option, or decision-tree area.
   - Create a new folder only when the case is genuinely new.

2. Name new fixture folders for the formatter decision they guard.
   - Use `frmtr-core/src/test/resources/format/<descriptive-name>/`.
   - Prefer names that point back to the relevant branch or decision-tree idea, for example `lambda-binary-body-opener`, `commented-continuation`, or `source-multiline-method-call-initializer`.
   - Avoid names based only on the original product/domain code.

3. Obfuscate the adopted code before committing it.
   - Make the original snippet unrecognizable: replace product names, private domains, service names, user data, ids, strings, comments, and distinctive type/member names.
   - Preserve formatter-relevant shape: AST structure, nesting depth, operator kinds, argument counts, lambda/block form, comment placement, source line breaks, parentheses, annotations, modifiers, and trailing punctuation.
   - Preserve element lengths when practical because line width is often the regression. Use realistic names with similar length instead of repeated letters.
   - Bad: `void m(A aaaaaaaaa, B bbbbbbbb)`.
   - Good: `void choose(Planner routePlanner, Catalog deliveryCatalog)`.
   - Keep the obfuscated snippet plausible Java, even if helper types are undefined; fixtures do not need to compile.

4. Add file fixtures.
   - Every fixture folder must contain `input.java`.
   - Every behavior must have at least `frmtr-default.output.java`.
   - Keep formatter-output tests in `frmtr-core/src/test/resources/format/**`; do not add inline test methods for normal formatter output regressions.

## Variant Outputs

Fixture output files are matched by name:

- `frmtr-default.output.java` uses `FormatterOptions.defaults()` and normally has no sidecar.
- `frmtr-<variant>.output.java` may have `frmtr-<variant>.options.properties` in the same folder.
- The variant name is the part between `frmtr-` and `.output.java`; its sidecar must use exactly the same variant.

Option sidecars use fixture property names such as:

```properties
line-width=80
indent-width=2
binary-operator-position=start
```

Add variant outputs only when the option is relevant to the behavior, for example START and END binary operator position.

## Regeneration

`FrmtrTest` discovers fixtures through `@ResourceFixtureSource(glob = "format/**/input.java")`.
For each discovered `input.java`, it reads every sibling `frmtr-*.output.java`, loads the matching
`frmtr-*.options.properties` sidecar when present, and asserts formatting plus idempotence.

To regenerate a default output:

```bash
cp frmtr-core/src/test/resources/format/<fixture>/input.java \
  frmtr-core/src/test/resources/format/<fixture>/frmtr-default.output.java
./gradlew :frmtr-cli:run --args='--write --color=never frmtr-core/src/test/resources/format/<fixture>/frmtr-default.output.java'
```

For variants expressible through CLI flags, copy `input.java` to the variant output and run the CLI
with those flags. For test-only options that the CLI does not expose, use a temporary test helper
that reads `input.java`, calls `Frmtr.format(source, FixtureOptionsProperties.forVariant(...))`,
writes `frmtr-<variant>.output.java`, runs the focused helper test, then deletes the helper before
finishing.

## Verification

Run focused fixture verification after adoption:

```bash
./gradlew :frmtr-core:test --tests dev.lanwen.frmtr.FrmtrTest
```
