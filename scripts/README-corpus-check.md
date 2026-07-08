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
| **Over-width** | Set of files with any line > 120 columns; reports the count and whether the worktree set is a subset of the base set. |

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

## Known base numbers

Base ref `github/main @ 9b38eca8` (the D0 branch point), kafka **subset of 800 files**, default
options (line width 120), harness defaults (`--chunk 400`):

| Invariant | Base value |
| --- | --- |
| verify failures | 0 |
| comment tokens (grep proxy) | 9094 |
| non-idempotent files | 0 |
| over-width files | 49 |

(The 49 over-width files are pre-existing in the base output — files the current formatter already
leaves with a `>120` line; both CLIs reproduce them identically, so the worktree set equals the base
set and `worktree ⊆ base` holds. The hub rewrite's job at completion is to keep this set from
*growing*, not to zero it here.)

On the D0 branch (harness + tripwire only, **no formatter change**) the worktree reproduces every
one of these exactly, so all four deltas are `0` and over-width `worktree ⊆ base` holds — which is
the D0 gate ("harness reproduces the known base numbers; tooling-only, no formatter output change").

> Numbers above are for the 800-file kafka subset used during D0 development. The full-corpus and
> other-repo figures are larger; capture them the same way when you run `--full` at a milestone.
