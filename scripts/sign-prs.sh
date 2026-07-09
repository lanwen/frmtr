#!/usr/bin/env bash
#
# sign-prs.sh — sign unsigned commits of one or more open PRs and force-push.
#
# Workflow this enables: let automation create/stack PRs with PLAIN (unsigned)
# commits to keep things fast, then run this when you're ready to sign — it signs
# only commits that need new signed objects and updates the PR in place (diff
# unchanged, so the PR stays open).
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
#   * Per-commit signing timeout, "already signed -> preserve/skip" (prompt-free
#     re-runs), multiple PRs / --all, and a post-push GitHub verification report.
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
#   --timeout <seconds>   Per-commit signing timeout (default: 45).
#   --squash              Squash each PR to a single commit before signing
#                         (keeps the head commit's message). Default: preserve
#                         signed commits and sign only unsigned commits.
#   -h, --help            Show this help.
#
# Requires: gh (authenticated or sandbox-proxied), git, setsid or perl for
# process-group cleanup, an ssh-agent holding the signing key, and that the key
# is registered as a *signing* key on your GitHub account (so GitHub reports
# verified=true).
#
set -euo pipefail

# Progress goes to stderr so it shows immediately even when stdout is captured/buffered
# (e.g. run in the background); stdout carries only the machine-readable summary lines.
GREEN=$'\033[32m'; YELLOW=$'\033[33m'; RED=$'\033[31m'; RESET=$'\033[0m'
msg()  { echo "${GREEN}==> $*${RESET}" >&2; }
warn() { echo "${YELLOW}==> $*${RESET}" >&2; }
err()  { echo "${RED}error: $*${RESET}" >&2; }

KEY_PATTERN="GitHub-SSH-Sign"
SIGN_TIMEOUT=45
SIGN_TIMEOUT_EXPLICIT=0
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
        --timeout) SIGN_TIMEOUT="$2"; SIGN_TIMEOUT_EXPLICIT=1; shift 2 ;;
        -h|--help) usage; exit 0 ;;
        -*) err "unknown option: $1"; usage >&2; exit 2 ;;
        *) prs+=("$1"); shift ;;
    esac
done

CLEANUP_DIR=""
trap 'rm -rf "${CLEANUP_DIR:-}" 2>/dev/null || true' EXIT

command -v gh >/dev/null || { err "'gh' (GitHub CLI) is required"; exit 1; }
git rev-parse --is-inside-work-tree >/dev/null 2>&1 || { err "run inside a git repository"; exit 1; }
if [[ ! "$SIGN_TIMEOUT" =~ ^[0-9]+$ || "$SIGN_TIMEOUT" -le 0 ]]; then
    err "--timeout must be a positive integer number of seconds"
    exit 2
fi

start_process_group() {
    if command -v setsid >/dev/null 2>&1; then
        exec setsid "$@"
    fi
    if command -v perl >/dev/null 2>&1; then
        exec perl -MPOSIX=setsid -e 'setsid() or die "setsid: $!"; exec @ARGV or die "exec: $!\n";' "$@"
    fi
    err "setsid or perl is required to isolate signing process groups"
    exit 127
}

stop_heartbeat() {
    local heartbeat_pid="$1"
    [[ -n "$heartbeat_pid" ]] || return 0
    kill "$heartbeat_pid" 2>/dev/null || true
    wait "$heartbeat_pid" 2>/dev/null || true
}

run_with_timeout() {
    local seconds="$1"
    local label="$2"
    shift 2

    ( start_process_group "$@" ) &
    local pid=$!
    local heartbeat_pid=""
    local started_at=$SECONDS

    if [[ -n "$label" ]]; then
        (
            while true; do
                sleep 15 || exit 0
                kill -0 "$pid" 2>/dev/null || exit 0
                warn "${label}: waiting for Touch ID... $((SECONDS - started_at))s/${seconds}s"
            done
        ) &
        heartbeat_pid=$!
    fi

    while kill -0 "$pid" 2>/dev/null; do
        if (( SECONDS - started_at >= seconds )); then
            stop_heartbeat "$heartbeat_pid"
            warn "${label:-command}: timed out after ${seconds}s; terminating process group ${pid}"
            kill -TERM "-$pid" 2>/dev/null || kill -TERM "$pid" 2>/dev/null || true
            local kill_started_at=$SECONDS
            while kill -0 "$pid" 2>/dev/null && (( SECONDS - kill_started_at < 5 )); do
                sleep 1
            done
            if kill -0 "$pid" 2>/dev/null; then
                warn "${label:-command}: still running after TERM; killing process group ${pid}"
                kill -KILL "-$pid" 2>/dev/null || kill -KILL "$pid" 2>/dev/null || true
            fi
            wait "$pid" 2>/dev/null || true
            return 124
        fi
        sleep 1
    done

    stop_heartbeat "$heartbeat_pid"
    local status
    if wait "$pid"; then
        status=0
    else
        status=$?
    fi
    return "$status"
}

commit_has_signature() {
    git cat-file commit "$1" | grep -qE 'BEGIN (SSH|PGP) SIGNATURE'
}

reap_ssh_signers() {
    for p in $(pgrep -f 'ssh-keygen -Y sign' 2>/dev/null || true); do
        local ppid; ppid="$(ps -o ppid= -p "$p" 2>/dev/null | tr -d ' ')"
        local secs; secs="$(ps -o etimes= -p "$p" 2>/dev/null | tr -d ' ')"
        if [[ "$ppid" == "1" ]]; then
            warn "reaping orphaned ssh-keygen sign (pid $p) — it was blocking the agent"
            kill -9 "$p" 2>/dev/null || true
        elif [[ -n "$secs" && "$secs" -gt 60 ]]; then
            warn "reaping stuck ssh-keygen sign (pid $p, ${secs}s old) — it was blocking the agent"
            kill "$p" 2>/dev/null || true
        fi
    done
}

abort_rewrite() {
    reap_ssh_signers
    git cherry-pick --abort 2>/dev/null || true
    git rebase --abort 2>/dev/null || true
}

if [[ $VERIFY_ONLY -eq 0 && ! -t 2 ]]; then
    warn "stderr is not a TTY — Touch ID prompts are easy to miss in background runs."
    if [[ $SIGN_TIMEOUT_EXPLICIT -eq 0 && "$SIGN_TIMEOUT" -gt 45 ]]; then
        SIGN_TIMEOUT=45
        warn "using ${SIGN_TIMEOUT}s signing timeout for this non-interactive run"
    fi
fi

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
    reap_ssh_signers
    if ! run_with_timeout 8 "" ssh-add -l >/dev/null 2>&1; then
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
        set -e
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
        original_head="$(git rev-parse HEAD)"
        mapfile -t commits < <(git rev-list --reverse "${merge_base}..HEAD")
        if [[ ${#commits[@]} -eq 0 ]]; then
            warn "PR #${pr}: no commits above base — nothing to sign"; exit 30
        fi

        unsigned=0
        for c in "${commits[@]}"; do
            commit_has_signature "$c" || unsigned=$((unsigned + 1))
        done
        if [[ $unsigned -eq 0 ]]; then
            warn "PR #${pr}: all ${#commits[@]} commit(s) already signed — skipping"; exit 31
        fi

        warn "PR #${pr}: approve the Touch ID prompt on your Mac to sign ${unsigned} unsigned commit(s) (key: ${KEY_PATTERN})…"
        if ! run_with_timeout 30 "PR #${pr}: warm-up signature" bash -c 'printf test | ssh-keygen -Y sign -n git -f .sign.pub >/dev/null'; then
            abort_rewrite
            err "PR #${pr}: test signature failed — fix Secretive/Touch ID and retry"
            exit 40
        fi

        if [[ $SQUASH -eq 1 ]]; then
            msg "PR #${pr}: squashing ${#commits[@]} commit(s) into one signed commit"
            git reset --soft "$merge_base"
            if ! run_with_timeout "$SIGN_TIMEOUT" "PR #${pr}: squash commit" git commit -S -C "$original_head" --quiet; then
                abort_rewrite
                err "PR #${pr}: squash signing failed or timed out — remote left UNCHANGED"
                exit 40
            fi
        else
            msg "PR #${pr}: replaying history and signing only commits that need new signed objects"
            git checkout --quiet --detach "$merge_base"
            preserved=0
            resigned=0
            signed=0
            for c in "${commits[@]}"; do
                if commit_has_signature "$c" && [[ "$(git rev-parse HEAD)" == "$(git rev-parse "${c}^")" ]]; then
                    git merge --quiet --ff-only "$c"
                    preserved=$((preserved + 1))
                    continue
                fi

                if commit_has_signature "$c"; then
                    resigned=$((resigned + 1))
                fi
                if ! git cherry-pick --quiet --no-commit "$c"; then
                    abort_rewrite
                    err "PR #${pr}: failed to replay commit ${c:0:8} — remote left UNCHANGED"
                    exit 40
                fi
                if ! run_with_timeout "$SIGN_TIMEOUT" "PR #${pr}: commit ${c:0:8}" git commit -S -C "$c" --quiet; then
                    abort_rewrite
                    err "PR #${pr}: signing failed or timed out at commit ${c:0:8} — remote left UNCHANGED"
                    exit 40
                fi
                signed=$((signed + 1))
            done
            git branch --force "$head" HEAD >/dev/null
            if [[ $resigned -gt 0 ]]; then
                warn "PR #${pr}: re-signed ${resigned} already-signed descendant commit(s) because an earlier parent changed"
            fi
            msg "PR #${pr}: signed ${signed} commit(s), preserved ${preserved} already-signed commit(s)"
        fi

        # GATE: verify every commit is now signed before pushing. Never force-push otherwise.
        for c in $(git rev-list "${merge_base}..HEAD"); do
            if ! commit_has_signature "$c"; then
                err "PR #${pr}: commit ${c:0:8} is still unsigned — refusing to push (remote UNCHANGED)"
                exit 41
            fi
        done

        msg "PR #${pr}: pushing signed commits (force-with-lease)"
        git push --quiet --force-with-lease origin "HEAD:${head}"
        git rev-parse HEAD > "$TMPDIR/.newtip"
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
