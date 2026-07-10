# Corpus check harness (`corpus-check.sh`)

One command that reports the four hub-canonicalization invariants for a **base-ref CLI vs the
current worktree CLI** over a real-world Java corpus, and prints the per-invariant base value,
worktree value, and delta. It is the D0 safety net for the hub rewrite described in
[`docs/proposals/hub-canonicalization-atomic-rewrite.md`](../docs/proposals/hub-canonicalization-atomic-rewrite.md).

The four invariants (from the plan):

| Invariant | What the harness measures |
| --- | --- |
| **AST-equivalence** | Count of files whose `--check --verify` reports a non-equivalent / non-parsing output (CLI exit 3). Must be 0 throughout. |
| **Comment parity** | Total comment-token count (`//` + block-comment openers, fast grep proxy) across the formatted outputs. Optional authoritative lexer-multiset net via `--comment-thorough`. Never drop a comment. |
| **Idempotence** | Count of files that differ between a first `--write` pass and a second `--write` pass. The north-star "pure-AST" signal — hits 0 when no source-shape read fires. |
| **Over-width** | Set of once-formatted files carrying any line > `LINE_WIDTH` (120) columns; reports the count and whether the worktree set is a subset of the base set. This is the raw ">line-width" definition (character length on the formatted output), *not* the CLI's breakable-only `--check --verify` warnings — those are a strict subset and under-report the raw set. |

On a branch with **no formatter change**, every delta must be `0` and `worktree ⊆ base` must hold.

## Usage

```bash
# Fast subset (first 800 files, seconds–minutes), base = github/main (default):
scripts/corpus-check.sh kafka --subset 800

# Whole corpus (kafka ~6033 files; minutes, more heap):
scripts/corpus-check.sh kafka --full

# Different base ref, custom subset size and chunking:
scripts/corpus-check.sh camel --base github/main --subset 500 --chunk 300

# Point at any directory of .java files instead of a registered corpus name:
scripts/corpus-check.sh /path/to/some/repo/src --subset 1000

# Authoritative comment-drop net (lexer multiset; slower) in addition to the grep proxy:
scripts/corpus-check.sh kafka --subset 800 --comment-thorough
```

Run it from a JDK-21 shell (`bash -l -c '...'` in the sandbox), with:

```bash
export JAVA_TOOL_OPTIONS=""; export FRMTR_HEAP="-Xmx2500m"
```

### Flags

| Flag | Meaning |
| --- | --- |
| `<corpus>` | Registered name (`kafka` / `camel` / `cayenne` / `tomcat` / `zookeeper`) resolved under `$CORPUS_ROOT/runs/<name>/src`, **or** an absolute path to a dir of `.java` files. |
| `--base <git-ref>` | Base ref for the reference CLI. Default `github/main`. |
| `--subset N` | Fast mode: first `N` files (sorted). Default subset size 800. |
| `--full` | Whole corpus. |
| `--chunk N` | Files per CLI invocation (JVM arg-length cap). Default 400. |
| `--comment-thorough` | Also run the `CommentDropCheck.java` lexer-multiset net. |
| `--keep` | Keep the pass working copies (`passA`/`passB`) for inspection. |

### Environment

| Var | Default | Meaning |
| --- | --- | --- |
| `CORPUS_ROOT` | `/tmp/frmtr-corpus` | Scratch root: corpus clones (`runs/<name>/src`), base-CLI build cache (`cli-cache/<sha>`), and per-run work (`check/<name>`). Never written into the repo. |
| `FRMTR_HEAP` | `-Xmx2500m` | CLI heap (also caps metaspace). |
| `FRMTR_LINE_WIDTH` | `120` | Over-width threshold in columns. Keep in lockstep with the CLI's formatting width (the harness formats at the formatter default). |

### Caching & robustness

- The **base CLI** is built once per resolved base commit and cached at
  `$CORPUS_ROOT/cli-cache/<base-sha>/`, so re-runs against the same base reuse the build. The base
  is built from a temporary detached `git worktree` and removed afterwards.
- The **worktree CLI** is always (re)built from the current tree.
- Large file lists are **chunked** (`--chunk`) to stay under the JVM argument-length limit; heap is
  capped via `FRMTR_HEAP`. `set -uo pipefail` throughout.
- Exit code: `0` when all deltas are `0`; non-zero when any delta is present (so CI can gate parity).

## Tie-in: the source-read tripwire

When the idempotence delta is non-zero on a hub change, re-run **one** offending file with the
tripwire on to see *which* `RETIREMENT_TARGET` source-shape read is still firing:

```bash
FRMTR_SOURCE_READ_TRIPWIRE=1 frmtr-cli --write --progress=never SomeFile.java
# → dumps per-method call-site hit counts (WAS_MULTILINE, SELECTOR_BROKE_AFTER, …) to stderr at JVM exit.
# Optionally: FRMTR_SOURCE_READ_TRIPWIRE_FILE=/tmp/tripwire.txt to write the dump to a file instead.
```

The tripwire is a diagnostic gated **off by default** (`SourceReadTripwire`); with the flag unset it
is zero-overhead and output is byte-identical.

## Reference probes (`idem-probe.sh`, `ow-probe.sh`)

Two single-metric probes back the harness's idempotence and over-width figures. They are the
independent reference implementations `corpus-check.sh` is validated against — for the same CLI and
`N` they report the same counts *and* the same file sets. Reach for them to double-check a harness
number in isolation, or to diff two CLIs' file sets by hand.

```bash
# <cli-bin> [N] [tag] [corpus]; both share corpus-check.sh's $CORPUS_ROOT convention.
CLI=frmtr-cli/build/install/frmtr-cli/bin/frmtr-cli
scripts/idem-probe.sh "$CLI" 400 base kafka   # → $CORPUS_ROOT/idem-probe/base/nidem.list
scripts/ow-probe.sh   "$CLI" 400 base kafka   # → $CORPUS_ROOT/ow-probe/base/ow.list
```

`idem-probe.sh` formats a subset twice and counts files that differ between the passes;
`ow-probe.sh` formats once and lists files with any line wider than `FRMTR_LINE_WIDTH` (raw
character-length `>` width, the same definition the harness uses). Both resolve the corpus under
`$CORPUS_ROOT/runs/<corpus>/src` (or an absolute path) and keep all scratch under `$CORPUS_ROOT`, so
neither writes into the repo.

## Known base numbers

Base ref `github/main @ b0257f75`, kafka subsets, default options (line width 120), harness default
chunking (`--chunk 400`):

| Invariant | N=400 | N=800 |
| --- | --- | --- |
| verify failures | 0 | 0 |
| comment tokens (grep proxy) | 5909 | — |
| non-idempotent files | 8 | 12 |
| over-width files | 138 | 205 |

These match the standalone reference probes file-for-file (`scripts/idem-probe.sh` for
idempotence, `scripts/ow-probe.sh` for over-width): the harness and the probes report the
same counts *and* the same file sets for the same CLI + N.

The non-idempotent and over-width files are pre-existing in the base output — files the current
formatter already re-shapes on a second pass, or already leaves with a `>120` line. On a **no-formatter-change**
branch both CLIs reproduce them identically, so every delta is `0` and over-width `worktree ⊆ base`
holds. The hub rewrite's job at completion is to keep these sets from *growing*, not to zero them here.

> The over-width count is the raw ">line-width" set (any formatted line whose character length exceeds
> `LINE_WIDTH`), measured over the once-formatted pass-A outputs — the same definition `ow-probe.sh`
> uses. It is deliberately **not** the CLI's `--check --verify` "line is N columns" warnings, which
> only flag *breakable* over-width lines (a strict subset) and so under-count the raw set. Override the
> threshold with `FRMTR_LINE_WIDTH` if you run the CLI at a non-default width.

> Numbers above are for kafka subsets used during development. The full-corpus and other-repo figures
> are larger; capture them the same way when you run `--full` at a milestone.
