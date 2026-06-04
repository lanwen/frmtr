# Context

## Glossary

- **File selector**: A CLI argument entry that points to files to consider for formatting. A selector can be a file path, directory path, glob pattern, or comma-separated list of those forms.
- **Default source selection**: Build integration source selection used when no explicit formatter selectors are configured. In Java projects, it means the project's Java source sets.
- **Source filter**: Build integration include or exclude pattern that narrows the default source selection using Gradle-style source filtering semantics.
- **Generated source**: Source file located under a Gradle project's build directory. Build integration excludes generated sources from formatting by default.
- **Line width**: Formatter option that defines the target maximum rendered column width before grouped output should prefer line breaks.
- **Java language level**: Formatter option that selects the Java grammar level used when parsing Java source.
- **Write mode**: CLI mode enabled by `--write` that overwrites matched Java files in place with formatted output.
- **Check mode**: CLI mode enabled by `--check` that reports files whose formatted output differs and exits non-zero when changes are needed.
- **Format task**: Build integration task that applies formatter output to selected source files in place.
- **Check task**: Build integration task that verifies selected source files already match formatter output and fails when changes are needed.
- **Check result**: File-oriented formatting outcome describing whether a selected source file is already formatted, would change, was written, or failed to format.
- **Unified diff**: Patch-style comparison between original source and formatter output for a source file that would change.
- **No-op formatting run**: Build integration run with no selected source files. It succeeds without formatting or reporting changes.
- **Stdout aggregate mode**: CLI mode used when multiple Java files are matched without `--write` or `--check`; formatted contents are printed to stdout with filename headers.
- **Unknown extension**: A matched file whose extension is not supported by the formatter. Unknown extensions are skipped silently.
- **Ignored file**: A file excluded from selector discovery by `.gitignore` rules.
- **Native-image companion module**: Build-time support module used only while producing a GraalVM native executable. It belongs on the native-image build path only and must not be part of the normal formatter runtime or build integration dependency graph.
- **Native compatibility fixture**: Java source sample used to prove formatter behavior survives GraalVM native-image compilation. It should exercise parser features whose implementation depends on native-image metadata.
