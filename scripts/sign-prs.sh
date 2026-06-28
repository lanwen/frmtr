#!/usr/bin/env bash
#
# sign-prs.sh — re-sign all commits of one or more open PRs and force-push.
#
# Workflow this enables: let automation create/stack PRs with PLAIN (unsigned)
# commits to keep things fast, then run this when you're ready to sign — it
# rewrites each PR's commits with your SSH signing key and updates the PR in
# place (diff unchanged, so the PR stays open).
#
# Adapted from Joe Miller's `git sign-pr` gist
# (https://gist.github.com/joemiller/abc8f233e3a71e1b60669c250ff19d87), with
# changes for this repo's environment and safety model:
#   * Clone + push over HTTPS (the SSH `origin` is refused in the sandbox).
#   * Configure SSH signing locally in the throwaway clone — never globally, so
#     concurrent executor subagents are not hit with Touch ID prompts.
#   * GATE the push on the signature actually existing on every commit; a
#     timed-out / failed sign never results in a force-push (which would empty
#     the PR by pushing the base).
#   * Per-commit signing timeout, "already signed -> skip" (prompt-free re-runs),
#     multiple PRs / --all, and a post-push GitHub verification report.
#
# Usage:
#   scripts/sign-prs.sh <pr-number> [<pr-number> ...]
#   scripts/sign-prs.sh --all          # every open PR in the repo
#   scripts/sign-prs.sh --verify-only <pr> [...]   # report only, no signing
#
# Options:
#   --all                 Sign every open PR (head branches) in the repo.
#   --verify-only         Only report each PR head's GitHub signature status.
#   --key <pattern>       ssh-agent key comment to sign with (default: GitHub-SSH-Sign).
#   --timeout <seconds>   Per-commit signing timeout (default: 180).
#   --squash              Squash each PR to a single commit before signing
#                         (keeps the head commit's message). Default: sign every commit.
#   -h, --help            Show this help.
#
# Requires: gh (authenticated or sandbox-proxied), git, an ssh-agent holding the
# signing key, and that the key is registered as a *signing* key on your GitHub
# account (so GitHub reports verified=true).
#
set -euo pipefail

# Progress goes to stderr so it shows immediately even when stdout is captured/buffered
# (e.g. run in the background); stdout carries only the machine-readable summary lines.
GREEN=$'\033[32m'; YELLOW=$'\033[33m'; RED=$'\033[31m'; RESET=$'\033[0m'
msg()  { echo "${GREEN}==> $*${RESET}" >&2; }
warn() { echo "${YELLOW}==> $*${RESET}" >&2; }
err()  { echo "${RED}error: $*${RESET}" >&2; }

KEY_PATTERN="GitHub-SSH-Sign"
SIGN_TIMEOUT=180
SQUASH=0
VERIFY_ONLY=0
ALL=0
: "${SSH_AUTH_SOCK:=/run/ssh-agent.sock}"
export SSH_AUTH_SOCK

usage() { sed -n '2,40p' "$0" | sed 's/^# \{0,1\}//'; }

prs=()
while [[ $# -gt 0 ]]; do
    case "$1" in
        --all) ALL=1; shift ;;
        --verify-only) VERIFY_ONLY=1; shift ;;
        --squash) SQUASH=1; shift ;;
        --key) KEY_PATTERN="$2"; shift 2 ;;
        --timeout) SIGN_TIMEOUT="$2"; shift 2 ;;
        -h|--help) usage; exit 0 ;;
        -*) err "unknown option: $1"; usage >&2; exit 2 ;;
        *) prs+=("$1"); shift ;;
    esac
done

CLEANUP_DIR=""
trap 'rm -rf "${CLEANUP_DIR:-}" 2>/dev/null || true' EXIT

command -v gh >/dev/null || { err "'gh' (GitHub CLI) is required"; exit 1; }
git rev-parse --is-inside-work-tree >/dev/null 2>&1 || { err "run inside a git repository"; exit 1; }

LOCAL_REPO="$(git rev-parse --show-toplevel)"
GH_REPO="$(gh repo view --json nameWithOwner --jq .nameWithOwner)"
HTTPS_URL="https://github.com/${GH_REPO}.git"

# Committer identity for the rewritten commits (the throwaway clone inherits none).
GIT_NAME="$(git config user.name || true)"
GIT_EMAIL="$(git config user.email || true)"
if [[ $VERIFY_ONLY -eq 0 && ( -z "$GIT_NAME" || -z "$GIT_EMAIL" ) ]]; then
    err "git user.name/user.email not set — needed to commit the re-signed commits"
    exit 1
fi

# Resolve the public key once (for the "expected signer" identity / sanity check).
PUBKEY_LINE="$(ssh-add -L 2>/dev/null | grep -m1 "$KEY_PATTERN" || true)"
if [[ $VERIFY_ONLY -eq 0 && -z "$PUBKEY_LINE" ]]; then
    err "no ssh-agent key matching '${KEY_PATTERN}' (SSH_AUTH_SOCK=${SSH_AUTH_SOCK}). Available:"
    ssh-add -L 2>&1 | sed 's/^/    /' >&2 || true
    exit 1
fi

# Preflight: an abandoned `ssh-keygen -Y sign` (a Touch ID request that was never
# approved) keeps holding the agent, so every later signature blocks behind it forever —
# this is the classic "the script hangs" cause. Reap stuck signers and confirm the agent
# answers, so we fail in seconds with a clear message instead of hanging per-PR.
preflight_signing() {
    for p in $(pgrep -f 'ssh-keygen -Y sign' 2>/dev/null || true); do
        local secs; secs="$(ps -o etimes= -p "$p" 2>/dev/null | tr -d ' ')"
        if [[ -n "$secs" && "$secs" -gt 60 ]]; then
            warn "reaping stuck ssh-keygen sign (pid $p, ${secs}s old) — it was blocking the agent"
            kill "$p" 2>/dev/null || true
        fi
    done
    if ! timeout 8 ssh-add -l >/dev/null 2>&1; then
        err "ssh-agent not responding (SSH_AUTH_SOCK=${SSH_AUTH_SOCK}). Start/forward it, then retry."
        exit 1
    fi
}
[[ $VERIFY_ONLY -eq 0 ]] && preflight_signing

# Build the PR list. Descending order signs a number-ordered stack top-down, so each PR
# signs only its own commit(s) before its base branch is rewritten (no rebase cascade).
if [[ $ALL -eq 1 ]]; then
    mapfile -t prs < <(gh pr list --repo "$GH_REPO" --state open --limit 200 --json number --jq '.[].number' | sort -rn)
fi
[[ ${#prs[@]} -gt 0 ]] || { err "no PRs given (pass PR numbers or --all)"; usage >&2; exit 2; }

verified_of() { # <sha> -> "true"/"false reason"
    gh api "repos/${GH_REPO}/commits/$1" \
        --jq '(.commit.verification.verified|tostring) + " " + .commit.verification.reason' 2>/dev/null || echo "unknown query-failed"
}

declare -a SUMMARY=()
rc=0

for pr in "${prs[@]}"; do
    read -r state head base < <(
        gh pr view "$pr" --repo "$GH_REPO" --json state,headRefName,baseRefName \
            --jq '[.state,.headRefName,.baseRefName]|@tsv' 2>/dev/null || echo $'ERR\t\t'
    )
    if [[ "$state" != "OPEN" ]]; then
        warn "PR #${pr}: not open (state: ${state:-unknown}) — skipping"
        SUMMARY+=("#${pr}: skipped (not open)"); continue
    fi

    if [[ $VERIFY_ONLY -eq 1 ]]; then
        sha="$(gh pr view "$pr" --repo "$GH_REPO" --json headRefOid --jq .headRefOid)"
        v="$(verified_of "$sha")"
        echo "#${pr} (${head}) ${sha:0:8}: verified=${v}" >&2
        SUMMARY+=("#${pr}: verified=${v}"); continue
    fi

    msg "PR #${pr} (${head} <- base ${base}): preparing isolated clone"
    TMPDIR="$(mktemp -d)"
    CLEANUP_DIR="$TMPDIR"
    # Disable errexit around the work subshell so its status-signaling exit codes
    # (30/31/40/41) are captured instead of aborting the whole run.
    set +e
    (
        # Clone from HTTPS (proxy-authed), borrowing objects from the local repo for speed.
        git clone --quiet --reference-if-able "$LOCAL_REPO" "$HTTPS_URL" "$TMPDIR"
        cd "$TMPDIR"

        # SSH signing config + committer identity — LOCAL to this throwaway clone only.
        printf '%s\n' "$PUBKEY_LINE" > .sign.pub
        git config gpg.format ssh
        git config user.signingkey "$PWD/.sign.pub"
        git config commit.gpgsign true
        git config user.name "$GIT_NAME"
        git config user.email "$GIT_EMAIL"

        git checkout --quiet -B "$head" "origin/$head"
        merge_base="$(git merge-base HEAD "origin/${base}")"
        mapfile -t commits < <(git rev-list "${merge_base}..HEAD")
        if [[ ${#commits[@]} -eq 0 ]]; then
            warn "PR #${pr}: no commits above base — nothing to sign"; exit 30
        fi

        # Skip if every commit is already signed — SSH or PGP (prompt-free re-runs;
        # also leaves release-automation / web-flow PGP-signed commits untouched).
        already=1
        for c in "${commits[@]}"; do
            git cat-file commit "$c" | grep -qE 'BEGIN (SSH|PGP) SIGNATURE' || { already=0; break; }
        done
        if [[ $already -eq 1 ]]; then
            warn "PR #${pr}: all ${#commits[@]} commit(s) already signed — skipping"; exit 31
        fi

        warn "PR #${pr}: approve the Touch ID prompt on your Mac to sign ${#commits[@]} commit(s) (key: ${KEY_PATTERN})…"
        if [[ $SQUASH -eq 1 ]]; then
            msg "PR #${pr}: squashing ${#commits[@]} commit(s) into one signed commit"
            git reset --soft "$merge_base"
            timeout "$SIGN_TIMEOUT" git commit -S -C "${commits[0]}" --quiet
        else
            msg "PR #${pr}: signing ${#commits[@]} commit(s)"
            # rebase --exec replays each commit and re-commits it signed; a timed-out
            # sign aborts the rebase so we never push a partially-signed branch.
            if ! git rebase --quiet --exec "timeout ${SIGN_TIMEOUT} git commit --amend --no-edit -S --quiet" "$merge_base"; then
                git rebase --abort 2>/dev/null || true
                err "PR #${pr}: signing/rebase failed or timed out — remote left UNCHANGED"
                exit 40
            fi
        fi

        # GATE: verify every commit is now signed before pushing. Never force-push otherwise.
        for c in $(git rev-list "${merge_base}..HEAD"); do
            if ! git cat-file commit "$c" | grep -q 'BEGIN SSH SIGNATURE'; then
                err "PR #${pr}: commit ${c:0:8} is still unsigned — refusing to push (remote UNCHANGED)"
                exit 41
            fi
        done

        msg "PR #${pr}: pushing signed commits (force-with-lease)"
        git push --quiet --force-with-lease origin "HEAD:${head}"
        echo "$(git rev-parse HEAD)" > "$TMPDIR/.newtip"
    )
    status=$?
    set -e
    case $status in
        0)  newtip="$(cat "$TMPDIR/.newtip" 2>/dev/null || echo '')"
            v="$(verified_of "$newtip")"
            msg "PR #${pr}: signed + pushed ${newtip:0:8} — GitHub verified=${v}"
            SUMMARY+=("#${pr}: SIGNED ${newtip:0:8} verified=${v}") ;;
        31) SUMMARY+=("#${pr}: already signed") ;;
        30) SUMMARY+=("#${pr}: nothing to sign") ;;
        *)  rc=1; SUMMARY+=("#${pr}: FAILED (left unchanged)")
            rm -rf "$TMPDIR"; CLEANUP_DIR=""
            err "signing failed for #${pr} — aborting remaining PRs (fix Touch ID/Secretive and re-run; already-signed PRs are skipped)."
            break ;;
    esac
    rm -rf "$TMPDIR"; CLEANUP_DIR=""
done

echo >&2
msg "Summary:"
printf '  %s\n' "${SUMMARY[@]}" >&2
exit $rc
