#!/usr/bin/env bash
#
# corpus-check.sh — one-command hub-canonicalization corpus harness.
#
# Reports the four hub-canonicalization invariants (docs/proposals/hub-canonicalization-atomic-rewrite.md)
# for a BASE-ref CLI vs the CURRENT-worktree CLI over a real-world Java corpus, and prints the per-invariant
# base value, worktree value, and delta:
#
#   AST-equivalence  — count of files whose `--check --verify` reports a non-equivalent / non-parsing output.
#   Comment parity   — total comment-token count across all formatted outputs (fast grep proxy; optional
#                       thorough lexer-multiset net via CommentDropCheck.java with --comment-thorough).
#   Idempotence      — count of files that differ between a first `--write` pass and a second `--write` pass.
#   Over-width       — set of files carrying any line > line-width (120); count + whether worktree set ⊆ base set.
#
# On a no-formatter-change branch every delta must be 0. A non-zero idempotence delta on a hub change is the
# north-star signal that a source-shape read still fires — pair it with FRMTR_SOURCE_READ_TRIPWIRE=1 to see which.
#
# USAGE
#   scripts/corpus-check.sh <corpus> [--base <git-ref>] [--subset N | --full]
#                                    [--chunk N] [--comment-thorough] [--keep]
#
#   <corpus>            A registered corpus name (kafka|camel|cayenne|tomcat|zookeeper) resolved under
#                       $CORPUS_ROOT/runs/<name>/src, OR an absolute path to a directory of .java files.
#   --base <git-ref>    Base ref to build the reference CLI from. Default: github/main.
#   --subset N          Fast mode: first N files (sorted). Default subset size: 800.
#   --full              Whole corpus (kafka ~6033 files; minutes, more heap).
#   --chunk N           Files per CLI invocation (JVM arg-length cap). Default: 400.
#   --comment-thorough  Also run the CommentDropCheck.java lexer-multiset net (authoritative; slower).
#   --keep              Keep the pass working copies (passA/passB) even when idempotence is clean.
#
# ENV
#   CORPUS_ROOT   Scratch root for corpus clones + build cache. Default: /tmp/frmtr-corpus.
#   FRMTR_HEAP    CLI heap. Default: -Xmx2500m.
#
# Base CLI builds are cached under $CORPUS_ROOT/cli-cache/<base-sha>, keyed by the resolved base commit sha,
# so re-runs against the same base reuse the build. No corpus data is ever written into the repo.
#
set -uo pipefail

# ------------------------------------------------------------------ args
CORPUS=""
BASE_REF="github/main"
MODE="subset"
SUBSET_N=800
CHUNK=400
COMMENT_THOROUGH=0
KEEP=0

die() { echo "corpus-check: $*" >&2; exit 2; }

while [ $# -gt 0 ]; do
  case "$1" in
    --base)             BASE_REF="${2:?--base needs a ref}"; shift 2 ;;
    --subset)           MODE="subset"; SUBSET_N="${2:?--subset needs N}"; shift 2 ;;
    --full)             MODE="full"; shift ;;
    --chunk)            CHUNK="${2:?--chunk needs N}"; shift 2 ;;
    --comment-thorough) COMMENT_THOROUGH=1; shift ;;
    --keep)             KEEP=1; shift ;;
    -h|--help)          sed -n '2,40p' "$0"; exit 0 ;;
    -*)                 die "unknown flag: $1" ;;
    *)                  [ -z "$CORPUS" ] && CORPUS="$1" || die "unexpected arg: $1"; shift ;;
  esac
done
[ -n "$CORPUS" ] || die "missing <corpus> (name or path). See --help."

# ------------------------------------------------------------------ paths
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)"
CORPUS_ROOT="${CORPUS_ROOT:-/tmp/frmtr-corpus}"
HEAP="${FRMTR_HEAP:--Xmx2500m}"
export JAVA_OPTS="$HEAP -XX:MaxMetaspaceSize=512m"

# Resolve the corpus source directory.
if [ -d "$CORPUS" ]; then
  SRC="$CORPUS"
  CORPUS_NAME="$(basename "$CORPUS")"
elif [ -d "$CORPUS_ROOT/runs/$CORPUS/src" ]; then
  SRC="$CORPUS_ROOT/runs/$CORPUS/src"
  CORPUS_NAME="$CORPUS"
else
  die "cannot resolve corpus '$CORPUS' (no dir, no \$CORPUS_ROOT/runs/$CORPUS/src)"
fi

WORK="$CORPUS_ROOT/check/$CORPUS_NAME"
rm -rf "$WORK"; mkdir -p "$WORK"

echo "corpus-check: corpus=$CORPUS_NAME src=$SRC"
echo "corpus-check: base=$BASE_REF mode=$MODE${MODE:+ }$([ "$MODE" = subset ] && echo "N=$SUBSET_N") chunk=$CHUNK heap=$HEAP"

# ------------------------------------------------------------------ file list
find "$SRC" -name '*.java' | sort > "$WORK/all.list"
TOTAL=$(wc -l < "$WORK/all.list" | tr -d ' ')
if [ "$MODE" = subset ]; then
  head -n "$SUBSET_N" "$WORK/all.list" > "$WORK/files.list"
else
  cp "$WORK/all.list" "$WORK/files.list"
fi
NFILES=$(wc -l < "$WORK/files.list" | tr -d ' ')
[ "$NFILES" -gt 0 ] || die "no .java files under $SRC"
echo "corpus-check: files=$NFILES (of $TOTAL total)"

# ------------------------------------------------------------------ CLI builds
# Base CLI: cached by resolved sha. Worktree CLI: always (re)built from current tree.
build_cli() {
  # $1 = build dir (a git worktree of the target ref, or the repo itself)
  local dir="$1"
  ( cd "$dir" && ./gradlew --no-daemon -q :frmtr-cli:installDist ) >"$WORK/build_$(basename "$dir").log" 2>&1 \
    || { echo "corpus-check: build FAILED in $dir (see $WORK/build_$(basename "$dir").log)" >&2; tail -20 "$WORK/build_$(basename "$dir").log" >&2; exit 4; }
  echo "$dir/frmtr-cli/build/install/frmtr-cli/bin/frmtr-cli"
}

BASE_SHA="$(git -C "$REPO_ROOT" rev-parse "$BASE_REF")" || die "cannot resolve base ref '$BASE_REF'"
BASE_SHORT="$(git -C "$REPO_ROOT" rev-parse --short "$BASE_REF")"
CACHE_ROOT="$CORPUS_ROOT/cli-cache"
BASE_INSTALL="$CACHE_ROOT/$BASE_SHA"
BASE_CLI="$BASE_INSTALL/bin/frmtr-cli"

if [ -x "$BASE_CLI" ]; then
  echo "corpus-check: base CLI cache HIT  ($BASE_SHORT)"
else
  echo "corpus-check: base CLI cache MISS ($BASE_SHORT) — building via detached worktree…"
  BASE_WT="$CORPUS_ROOT/cli-build/$BASE_SHA"
  rm -rf "$BASE_WT"; mkdir -p "$(dirname "$BASE_WT")"
  git -C "$REPO_ROOT" worktree add --detach "$BASE_WT" "$BASE_SHA" >/dev/null 2>&1 \
    || die "git worktree add for base $BASE_SHORT failed"
  BUILT="$(build_cli "$BASE_WT")"
  mkdir -p "$CACHE_ROOT"
  rm -rf "$BASE_INSTALL"
  cp -r "$BASE_WT/frmtr-cli/build/install/frmtr-cli" "$BASE_INSTALL"
  git -C "$REPO_ROOT" worktree remove --force "$BASE_WT" >/dev/null 2>&1 || true
  echo "corpus-check: base CLI cached at $BASE_INSTALL"
fi

echo "corpus-check: building worktree CLI ($(git -C "$REPO_ROOT" rev-parse --short HEAD))…"
WT_CLI="$(build_cli "$REPO_ROOT")"
COMMENT_LIB="$REPO_ROOT/frmtr-cli/build/install/frmtr-cli/lib/*"
COMMENT_HARNESS="$CORPUS_ROOT/CommentDropCheck.java"

# ------------------------------------------------------------------ measurement
# run_verify <cli> <outprefix> : count files whose --check --verify reports a non-equivalent / non-parse output;
#                                also capture the over-width line set.
VIOL_PAT='not AST-equivalent|AST-equivalence verify failed|did not parse under|formatted output did not parse'
run_verify() {
  local cli="$1" pfx="$2"
  : > "$WORK/$pfx.verify.err"; : > "$WORK/$pfx.overwidth"
  split -l "$CHUNK" "$WORK/files.list" "$WORK/${pfx}_ck_"
  local vfail=0
  for ch in "$WORK/${pfx}_ck_"*; do
    "$cli" --check --verify --progress=never $(cat "$ch") >/dev/null 2>"$WORK/_ck.err"
    local ce=$?
    [ "$ce" = 3 ] && vfail=1
    grep -nE "$VIOL_PAT" "$WORK/_ck.err" >> "$WORK/$pfx.verify.err" 2>/dev/null || true
    grep -oE '[^ ]+\.java:[0-9]+: line is [0-9]+ columns' "$WORK/_ck.err" >> "$WORK/$pfx.overwidth" 2>/dev/null || true
  done
  rm -f "$WORK/${pfx}_ck_"* "$WORK/_ck.err"
  # File-granular verify count (a verify failure aborts a chunk's remaining files, so re-run per-file when any hit).
  : > "$WORK/$pfx.verify.files"
  if [ "$vfail" = 1 ]; then
    while read -r f; do
      "$cli" --check --verify --progress=never "$f" >/dev/null 2>/dev/null
      [ "$?" = 3 ] && echo "$f" >> "$WORK/$pfx.verify.files"
    done < "$WORK/files.list"
  fi
  # Over-width file set (unique files, sorted).
  sed -E 's/:[0-9]+: line is [0-9]+ columns//' "$WORK/$pfx.overwidth" | sort -u > "$WORK/$pfx.overwidth.files"
}

# run_idempotence <cli> <outprefix> : two --write passes over a private copy; count files that differ.
run_idempotence() {
  local cli="$1" pfx="$2"
  local A="$WORK/$pfx.passA" B="$WORK/$pfx.passB"
  rm -rf "$A" "$B"; mkdir -p "$A"
  # Materialize a flat copy so both passes and comment counting operate on identical inputs.
  local i=0
  while read -r f; do
    cp "$f" "$A/$(printf '%06d' "$i")_$(basename "$f")"
    i=$((i + 1))
  done < "$WORK/files.list"
  find "$A" -name '*.java' | sort > "$WORK/$pfx.copy.list"
  split -l "$CHUNK" "$WORK/$pfx.copy.list" "$WORK/${pfx}_wa_"
  for ch in "$WORK/${pfx}_wa_"*; do "$cli" --write --progress=never $(cat "$ch") >/dev/null 2>>"$WORK/$pfx.write.err"; done
  rm -f "$WORK/${pfx}_wa_"*
  cp -r "$A" "$B"
  find "$B" -name '*.java' | sort > "$WORK/$pfx.copyB.list"
  split -l "$CHUNK" "$WORK/$pfx.copyB.list" "$WORK/${pfx}_wb_"
  for ch in "$WORK/${pfx}_wb_"*; do "$cli" --write --progress=never $(cat "$ch") >/dev/null 2>>"$WORK/$pfx.write.err"; done
  rm -f "$WORK/${pfx}_wb_"*
  diff -rq "$A" "$B" 2>/dev/null | grep -c differ > "$WORK/$pfx.nidem" || echo 0 > "$WORK/$pfx.nidem"
  # Comment-token proxy: count // and block-comment openers across the ONCE-formatted (passA) outputs.
  grep -rhoE '//|/\*' "$A" 2>/dev/null | wc -l | tr -d ' ' > "$WORK/$pfx.comments"
  # Keep passA around for the optional thorough comment net; drop copies otherwise (or when --keep).
  if [ "$KEEP" = 0 ] && [ "$COMMENT_THOROUGH" = 0 ]; then rm -rf "$A" "$B"; else rm -rf "$B"; fi
}

# comment_thorough <passA-dir> : authoritative lexer-multiset comment-drop net over the formatted outputs.
comment_thorough() {
  local dir="$1"
  if [ ! -f "$COMMENT_HARNESS" ]; then echo "N/A (no $COMMENT_HARNESS)"; return; fi
  java -Xmx3000m -cp "$COMMENT_LIB" "$COMMENT_HARNESS" "$dir" 2>/dev/null \
    | grep -E 'files with comment DROPS:|total dropped comment contents:' | tr '\n' ' '
}

echo "corpus-check: === BASE ($BASE_SHORT) ==="
run_verify "$BASE_CLI" base
run_idempotence "$BASE_CLI" base
echo "corpus-check: === WORKTREE ==="
run_verify "$WT_CLI" wt
run_idempotence "$WT_CLI" wt

# ------------------------------------------------------------------ report
b_verify=$(wc -l < "$WORK/base.verify.files" | tr -d ' ')
w_verify=$(wc -l < "$WORK/wt.verify.files" | tr -d ' ')
b_comments=$(cat "$WORK/base.comments")
w_comments=$(cat "$WORK/wt.comments")
b_nidem=$(cat "$WORK/base.nidem")
w_nidem=$(cat "$WORK/wt.nidem")
b_ow=$(wc -l < "$WORK/base.overwidth.files" | tr -d ' ')
w_ow=$(wc -l < "$WORK/wt.overwidth.files" | tr -d ' ')
# Over-width containment: worktree files NOT in base = new over-width (must be empty at completion).
ow_new=$(comm -13 "$WORK/base.overwidth.files" "$WORK/wt.overwidth.files" | wc -l | tr -d ' ')
[ "$ow_new" = 0 ] && ow_subset="yes" || ow_subset="NO ($ow_new new)"

d_verify=$((w_verify - b_verify))
d_comments=$((w_comments - b_comments))
d_nidem=$((w_nidem - b_nidem))
d_ow=$((w_ow - b_ow))

echo ""
echo "================ CORPUS-CHECK  $CORPUS_NAME  ($NFILES files, mode=$MODE) ================"
printf "%-22s %12s %12s %10s\n" "invariant" "base($BASE_SHORT)" "worktree" "delta"
printf "%-22s %12s %12s %10s\n" "----------------------" "------------" "------------" "----------"
printf "%-22s %12s %12s %+10d\n" "verify failures"      "$b_verify"   "$w_verify"   "$d_verify"
printf "%-22s %12s %12s %+10d\n" "comment tokens"       "$b_comments" "$w_comments" "$d_comments"
printf "%-22s %12s %12s %+10d\n" "non-idempotent files" "$b_nidem"    "$w_nidem"    "$d_nidem"
printf "%-22s %12s %12s %+10d\n" "over-width files"     "$b_ow"       "$w_ow"       "$d_ow"
printf "%-22s %36s\n" "over-width worktree⊆base" "$ow_subset"
if [ "$COMMENT_THOROUGH" = 1 ]; then
  echo "----------------------------------------------------------------------------------------"
  echo "comment-drop net (lexer, authoritative):"
  echo "  base:     $(comment_thorough "$WORK/base.passA")"
  echo "  worktree: $(comment_thorough "$WORK/wt.passA")"
fi
echo "========================================================================================"

ALL_ZERO=$(( d_verify == 0 && d_comments == 0 && d_nidem == 0 && d_ow == 0 && ow_new == 0 ))
if [ "$ALL_ZERO" = 1 ]; then
  echo "corpus-check: RESULT all deltas 0 — worktree matches base on every invariant."
else
  echo "corpus-check: RESULT deltas present — worktree differs from base (see the table)."
  [ "$d_nidem" -gt 0 ] && echo "  hint: non-zero idempotence delta ⇒ re-run one file with FRMTR_SOURCE_READ_TRIPWIRE=1 to see the firing read."
fi

# Cleanup work copies unless kept for inspection.
if [ "$KEEP" = 0 ]; then rm -rf "$WORK"/base.passA "$WORK"/wt.passA; fi

# Exit non-zero when any delta is present so callers/CI can gate on parity.
[ "$ALL_ZERO" = 1 ]
