# Context

## Glossary

- **File selector**: A CLI argument entry that points to files to consider for formatting. A selector can be a file path, directory path, glob pattern, or comma-separated list of those forms.
- **Write mode**: CLI mode enabled by `--write` that overwrites matched Java files in place with formatted output.
- **Check mode**: CLI mode enabled by `--check` that reports files whose formatted output differs and exits non-zero when changes are needed.
- **Stdout aggregate mode**: CLI mode used when multiple Java files are matched without `--write` or `--check`; formatted contents are printed to stdout with filename headers.
- **Unknown extension**: A matched file whose extension is not supported by the formatter. Unknown extensions are skipped silently.
- **Ignored file**: A file excluded from selector discovery by `.gitignore` rules.
