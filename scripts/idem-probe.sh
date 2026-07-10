#!/usr/bin/env bash
#
# idem-probe.sh — reliable idempotence reference probe.
#
# The corpus-check.sh in-harness idempotence metric under-reported during development (it showed 0
# while the true base non-idempotence was ~8/400 kafka files). This standalone probe is the
# reference implementation: format a subset of corpus files twice with a given CLI and count the
# files that differ between the two --write passes. corpus-check.sh's idempotence figure is
# validated against this probe — the two agree file-for-file for the same CLI + N.
#
# USAGE
#   scripts/idem-probe.sh <cli-bin> [N] [tag] [corpus]
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
#   CORPUS_ROOT   Scratch root for corpus clones + probe work. Default: /tmp/frmtr-corpus (the same
#                 default and layout corpus-check.sh uses; nothing is ever written into the repo).
#
# Reports "<tag>: <count> non-idempotent of <N> (<cli>)" and writes the differing basenames to
# $CORPUS_ROOT/idem-probe/<tag>/nidem.list.
set -uo pipefail
CLI="${1:?usage: idem-probe.sh <cli-bin> [N] [tag] [corpus]}"
N="${2:-400}"
TAG="${3:-probe}"
CORPUS="${4:-kafka}"
CORPUS_ROOT="${CORPUS_ROOT:-/tmp/frmtr-corpus}"

if [ -d "$CORPUS" ]; then
  SRC="$CORPUS"
elif [ -d "$CORPUS_ROOT/runs/$CORPUS/src" ]; then
  SRC="$CORPUS_ROOT/runs/$CORPUS/src"
else
  echo "idem-probe: cannot resolve corpus '$CORPUS' (no dir, no \$CORPUS_ROOT/runs/$CORPUS/src)" >&2
  exit 2
fi

W="$CORPUS_ROOT/idem-probe/$TAG"; rm -rf "$W"; mkdir -p "$W/a"
find "$SRC" -name '*.java' | sort | head -"$N" | while read -r f; do
  cp "$f" "$W/a/$(echo "$f" | md5sum | cut -c1-10)_$(basename "$f")"
done
bash -l -c "export JAVA_TOOL_OPTIONS=''; '$CLI' --write --progress=never $W/a/*.java >/dev/null 2>&1"
cp -r "$W/a" "$W/b"
bash -l -c "export JAVA_TOOL_OPTIONS=''; '$CLI' --write --progress=never $W/b/*.java >/dev/null 2>&1"
diff -rq "$W/a" "$W/b" 2>/dev/null | grep differ | grep -oE '[^/ ]+\.java' | sort -u > "$W/nidem.list" || true
echo "$TAG: $(wc -l < "$W/nidem.list" | tr -d ' ') non-idempotent of $N ($CLI)"
