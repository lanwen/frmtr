---
name: explain-diff
description: Explain why frmtr formats/wraps a specific Java file the way it does — run the formatter's `--explain` mode on the file and translate its output (why each line wrapped, width arithmetic, structural vs width-driven breaks) into a plain answer. Use when the user asks to "explain the diff / formatting for <file>", "why did frmtr wrap/break this", "why is this line split", or wants to understand a formatting change on one file.
argument-hint: <path/to/File.java> [line-width]
allowed-tools: Bash, Read
---

# explain-diff

Explain *why* `frmtr` lays a specific Java file out the way it does, using the CLI's
`--explain` mode, then translate the output into plain language.

`--explain` answers the question a developer actually has — **"why did this line wrap?"** — by
printing the formatted result plus, for every construct that broke across lines, the rule that owns
it and the width math behind the decision.

## 1. Resolve the target

The file is `$ARGUMENTS` (first token = path, optional second token = line width). If no path is
given, use the file the user named in conversation. Use a **repo-relative path** — the runner's
working directory is the repo root.

## 2. Run explain

`--explain` is its own mode (it cannot be combined with `--check`/`--write`/`--diff`) and takes
**exactly one file** (or `--stdin`).

There is no `frmtr` binary on `PATH` in this repo; invoke through Gradle:

```bash
./gradlew -q :frmtr-cli:run \
  --args='--explain <repo-relative-file> --color never' \
  --console=plain 2>/dev/null
```

- Add `--line-width N` to reproduce a specific width (default is `FormatterOptions.DEFAULT_LINE_WIDTH`,
  currently 120; the "Formatted (line width N)" header and the "Nothing wrapped … within N columns"
  line both echo the width actually used, so trust those rather than assuming). A **narrower** width
  surfaces more wraps, useful when the file already fits and you want to show *what would* wrap.
- Add `-v`/`--verbose` to also see raw `java.*` rule labels and every group in the decision tree
  (including ones that stayed on one line).
- `2>/dev/null` drops the JVM/Gradle banner so only the explain output remains.

If you also want to show **what actually changes** on disk (the literal diff), run the separate
check mode first, then explain the why:

```bash
./gradlew -q :frmtr-cli:run --args='--check --diff <file> --color never' --console=plain 2>/dev/null
```

(`--explain` already prints the fully formatted result under "Formatted", so the diff step is only
needed when the user wants the line-by-line before/after.)

## 3. Read the output

The output has four sections.

### Formatted
The exact result frmtr produces, indented as a block. This is the "after".

### Why it wrapped — the important section
One entry per construct that broke across lines. Two kinds:

- **Width-driven** (actionable):
  ```
  method chain `out.append(...).repeat(...)` broke:
    flat width 81 > 80 available
    (3 segments, one per line)
  ```
  The construct's one-line form is **81 columns**; only **80** were available, so it wrapped into
  3 pieces. Two flavors of "available":
  - bare `> W` — the printer measured against the full configured line width.
  - `> W available (from column C)` — the renderer measured against the columns left on the line,
    i.e. line width minus the column `C` where the construct started (so a deeply-indented or
    trailing construct has less room).
  Construct names are humanized: **method chain, argument list, ternary, if condition**.

- **Rule-driven** (usually *not* a problem):
  ```
  TryStmt laid out across lines by rule (no width measurement)
  ```
  A formatter rule spans this construct over lines regardless of width — class/method **bodies**,
  `try`/`switch`/loops, and some pre-measured argument lists. This is normal structure, not "too
  long". Don't tell the user to shorten these.

If nothing genuinely overflowed you'll see **"Nothing wrapped — everything fit within N columns."**

### Decision tree
The structural map of the whole file. `BREAK forced N` = a rule emits N hard line breaks
(structural). `BREAK group N > W` = the renderer measured a group and broke it because N > W.
`FLAT` = stayed on one line. Use this to locate *where* in the file a wrap happened and what
encloses it.

### Legend
A built-in cheat sheet for the symbols above; you can quote it if helpful.

## 4. Answer the user

Lead with the **width-driven** wraps — those are the ones a developer can act on (shorten the
expression, extract a variable, or raise the line width). For each, state the construct, the
numbers (`N > W`), and what it split into, in plain words. Mention rule-driven/structural breaks
only briefly as "these span lines by design". If the user asked about a specific line, find the
matching entry (and its place in the decision tree) and explain just that one.

## Known coverage limits (mention only if relevant)

- **Object-creation argument lists** (`new Foo(...)`) currently report as the generic
  "laid out across lines by rule (no width measurement)" note rather than width arithmetic — so a
  wrapping constructor call won't show `N > W` yet.
- **Structural statements** (`TryStmt`, `switch`, loops) can appear in "Why it wrapped" as
  rule-driven notes even when nothing overflowed; treat them as structure, not problems.
- These are tracked follow-ups; don't present them as the file's fault.
