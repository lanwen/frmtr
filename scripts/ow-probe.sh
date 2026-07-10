#!/usr/bin/env bash
#
# ow-probe.sh — reliable over-width reference probe.
#
# Format N corpus files with a CLI (into private /tmp-independent copies under $CORPUS_ROOT) and
# list the files that carry any line wider than the line width. This is the raw ">line-width"
# definition (character length on the once-formatted output) that corpus-check.sh's over-width
# figure is validated against — the two agree file-for-file for the same CLI + N. It is NOT the
# CLI's `--check --verify` "line is N columns" warnings, which flag only breakable over-width lines
# (a strict subset) and under-count the raw set.
#
# USAGE
#   scripts/ow-probe.sh <cli-bin> [N] [tag] [corpus]
#
#   <cli-bin>   Path to a built frmtr-cli launcher
#               (…/frmtr-cli/build/install/frmtr-cli/bin/frmtr-cli).
#   N           Number of corpus files (sorted) to probe. Default: 400.
#   tag         Work-dir label so parallel probes do not collide. Default: probe.
#   corpus      Registered corpus name (kafka|camel|cayenne|tomcat|zookeeper) resolved under
#               $CORPUS_ROOT/runs/<corpus>/src, OR an absolute path to a dir of .java files.
#               Default: kafka.
#
# ENV
#   CORPUS_ROOT       Scratch root for corpus clones + probe work. Default: /tmp/frmtr-corpus (the
#                     same default and layout corpus-check.sh uses; nothing is written to the repo).
#   FRMTR_LINE_WIDTH  Over-width threshold in columns. Default: 120 (keep in lockstep with the CLI's
#                     formatting width — the probe formats at the formatter default).
#
# Reports "<tag>: <count> over-width files of <N> (<cli>)" and writes the sorted basenames to
# $CORPUS_ROOT/ow-probe/<tag>/ow.list.
set -uo pipefail
CLI="${1:?usage: ow-probe.sh <cli-bin> [N] [tag] [corpus]}"
N="${2:-400}"
TAG="${3:-probe}"
CORPUS="${4:-kafka}"
CORPUS_ROOT="${CORPUS_ROOT:-/tmp/frmtr-corpus}"
LINE_WIDTH="${FRMTR_LINE_WIDTH:-120}"

if [ -d "$CORPUS" ]; then
  SRC="$CORPUS"
elif [ -d "$CORPUS_ROOT/runs/$CORPUS/src" ]; then
  SRC="$CORPUS_ROOT/runs/$CORPUS/src"
else
  echo "ow-probe: cannot resolve corpus '$CORPUS' (no dir, no \$CORPUS_ROOT/runs/$CORPUS/src)" >&2
  exit 2
fi

W="$CORPUS_ROOT/ow-probe/$TAG"; rm -rf "$W"; mkdir -p "$W/f"
find "$SRC" -name '*.java' | sort | head -"$N" | while read -r f; do
  cp "$f" "$W/f/$(echo "$f" | md5sum | cut -c1-10)_$(basename "$f")"
done
bash -l -c "export JAVA_TOOL_OPTIONS=''; '$CLI' --write --progress=never $W/f/*.java >/dev/null 2>&1"
# A "column" in frmtr terms is display width; awk character length > LINE_WIDTH is the proxy the
# probe relies on (tabs are already expanded on --write, and the corpus is ASCII-dominant).
: > "$W/ow.list"
for jf in "$W"/f/*.java; do
  if awk -v w="$LINE_WIDTH" '{ if (length($0) > w) { found = 1 } } END { exit !found }' "$jf"; then
    echo "$(basename "$jf" | sed -E 's/^[0-9a-f]{10}_//')" >> "$W/ow.list"
  fi
done
sort -u "$W/ow.list" -o "$W/ow.list"
echo "$TAG: $(wc -l < "$W/ow.list" | tr -d ' ') over-width files of $N ($CLI)"
