#!/usr/bin/env bash
# Secret scan with gitleaks that fails closed (owner decision 2026-10-08, item 6).
#
# Why: the earlier `docker://zricethezav/gitleaks` step ran the image as root on a workspace owned by the
# runner user. The image marks every directory safe only in /root/.gitconfig, and GitHub runs container
# actions with HOME=/github/home, so that file is not read: git refused the repository ("detected dubious
# ownership"), gitleaks logged the error, reported "0 commits scanned" and exited 0, and the required
# "Secret scan" check passed without scanning. Locally (HOME=/root) the same image works, which hid it.
#
# What this does:
# - runs the pinned image as the owner of the repository directory (and marks it safe for git);
# - scans an explicit commit range: a pull request's own commits (base..head), a push's new commits
#   (before..after; the full history of `after` for a new branch or an unknown `before`), or the full
#   history of HEAD for a manual or scheduled run (audit);
# - fails on a shallow clone, an unresolvable range, any scanner or git error line, a missing
#   "commits scanned" line, a count outside the expected bounds, an unexpected exit code, or any finding;
# - proves the range was scanned: gitleaks counts the commits of the range that add text (it says so
#   itself: "this number might be smaller than expected due to commits with no additions"), so its count
#   must lie between the commits that add a text line and the non-merge commits of the same range.
#   Merge commits are not scanned (git log -p shows no diff for them), as before.
#
# Usage:
#   secret-scan.sh --repo DIR (--range A..B | --full REV) [--report FILE] [--log FILE]
#   secret-scan.sh --repo DIR --event NAME [--base SHA --head SHA] [--before SHA --after SHA] [...]
#   secret-scan.sh --check-log LOG --min N --max M     (log assessment only; used by the self-test)
# Env: PARKIO_GITLEAKS_IMAGE (default: v8.28.0 pinned by digest);
#      PARKIO_GITLEAKS_RUN_AS=owner (default) | old-action (self-test only: root with GitHub's container
#      action HOME, exactly as the old step ran, to reproduce the ownership failure).
# Exit: 0 clean; 1 findings or a failed check; 2 usage.
set -uo pipefail

IMAGE="${PARKIO_GITLEAKS_IMAGE:-zricethezav/gitleaks@sha256:cdbb7c955abce02001a9f6c9f602fb195b7fadc1e812065883f695d1eeaba854}"
RUN_AS="${PARKIO_GITLEAKS_RUN_AS:-owner}"
ZERO_SHA=0000000000000000000000000000000000000000

usage() { sed -n '2,27p' "$0" >&2; exit 2; }
fail() { echo "secret scan: FAIL: $*" >&2; exit 1; }

# assess LOG RC MIN MAX -> exit 0 (clean) or 1, printing one verdict line.
assess() {
  local log=$1 rc=$2 min=$3 max=$4
  python3 - "$log" "$rc" "$min" "$max" <<'PY'
import re, sys
log, rc, lo, hi = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), int(sys.argv[4])
text = re.sub(r"\x1b\[[0-9;]*m", "", open(log, encoding="utf-8", errors="replace").read())
problems = []
errors = [l.strip() for l in text.splitlines() if re.search(r"\bERR\b|\bFTL\b|fatal:", l)]
if errors:
    problems.append("scanner error: " + errors[0][:200])
counts = re.findall(r"(\d+) commits scanned", text)
if not counts:
    problems.append("no 'commits scanned' line in the gitleaks output")
    scanned = None
else:
    scanned = int(counts[-1])
    if not lo <= scanned <= hi:
        problems.append(f"{scanned} commits scanned, expected between {lo} (commits that add text) and {hi} (non-merge commits)")
leaks = re.findall(r"leaks found: (\d+)", text)
found = int(leaks[-1]) if leaks else 0
if found:
    problems.append(f"leaks found: {found} (redacted details in the log and the report)")
elif rc not in (0,):
    problems.append(f"gitleaks exited {rc} without reporting leaks")
if "no leaks found" not in text and not found:
    problems.append("gitleaks did not report a result line")
summary = f"commits scanned {scanned if scanned is not None else '?'} (expected {lo}..{hi}), leaks {found}"
if problems:
    print("secret scan: FAIL: " + "; ".join(problems) + f" [{summary}]")
    sys.exit(1)
print("secret scan: PASS: " + summary)
PY
}

# bounds REVSPEC... -> "MIN MAX": commits that add a text line, and non-merge commits.
bounds() {
  local max
  max=$(git -C "$REPO" rev-list --no-merges --count "$@") || return 1
  git -C "$REPO" log --no-merges -p -U0 --format='COMMIT %H' "$@" | python3 -c '
import sys
added = 0; cur = False
for line in sys.stdin:
    if line.startswith("COMMIT "):
        if cur: added += 1
        cur = False
    elif line.startswith("+") and not line.startswith("+++"):
        cur = True
if cur: added += 1
print(added)
' | { read -r min; echo "$min $max"; }
}

REPO="" RANGE="" FULL="" EVENT="" BASE="" HEAD="" BEFORE="" AFTER="" REPORT="gitleaks.sarif" LOG="" CHECK_LOG="" MIN="" MAX=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    --repo) REPO=$2; shift 2 ;;
    --range) RANGE=$2; shift 2 ;;
    --full) FULL=$2; shift 2 ;;
    --event) EVENT=$2; shift 2 ;;
    --base) BASE=$2; shift 2 ;;
    --head) HEAD=$2; shift 2 ;;
    --before) BEFORE=$2; shift 2 ;;
    --after) AFTER=$2; shift 2 ;;
    --report) REPORT=$2; shift 2 ;;
    --log) LOG=$2; shift 2 ;;
    --check-log) CHECK_LOG=$2; shift 2 ;;
    --min) MIN=$2; shift 2 ;;
    --max) MAX=$2; shift 2 ;;
    -h|--help) usage ;;
    *) echo "unknown argument: $1" >&2; usage ;;
  esac
done

if [ -n "$CHECK_LOG" ]; then
  [ -n "$MIN" ] && [ -n "$MAX" ] || usage
  assess "$CHECK_LOG" 0 "$MIN" "$MAX"; exit $?
fi

[ -n "$REPO" ] && [ -d "$REPO/.git" ] || [ -f "$REPO/.git" ] || fail "--repo must be a git checkout (got '${REPO:-}')"
REPO=$(cd "$REPO" && pwd)
[ "$(git -C "$REPO" rev-parse --is-shallow-repository)" = false ] || fail "the clone is shallow; the scan needs the full history (fetch-depth: 0)"

resolve() { git -C "$REPO" rev-parse --verify --quiet "$1^{commit}"; }
MODE="" LOG_OPTS="" DESC=""
if [ -n "$EVENT" ]; then
  case "$EVENT" in
    pull_request|pull_request_target)
      [ -n "$BASE" ] && [ -n "$HEAD" ] || fail "event $EVENT needs --base and --head"
      RANGE="$BASE..$HEAD" ;;
    push)
      [ -n "$AFTER" ] || fail "event push needs --after"
      if [ -z "$BEFORE" ] || [ "$BEFORE" = "$ZERO_SHA" ] || ! resolve "$BEFORE" >/dev/null; then
        echo "secret scan: push without a known 'before' commit: scanning the full history of $AFTER"
        FULL="$AFTER"
      else
        RANGE="$BEFORE..$AFTER"
      fi ;;
    *) FULL="${HEAD:-HEAD}" ;;
  esac
fi
if [ -n "$RANGE" ]; then
  a=${RANGE%%..*}; b=${RANGE##*..}
  A=$(resolve "$a") || fail "cannot resolve range start '$a'"
  B=$(resolve "$b") || fail "cannot resolve range end '$b'"
  MODE=range; LOG_OPTS="$A..$B"; DESC="commits $A..$B"
  read -r MIN MAX < <(bounds "$A..$B") || fail "cannot count the commits of $A..$B"
elif [ -n "$FULL" ]; then
  B=$(resolve "$FULL") || fail "cannot resolve '$FULL'"
  MODE=full; LOG_OPTS="$B"; DESC="full history of $B"
  read -r MIN MAX < <(bounds "$B") || fail "cannot count the history of $B"
else
  usage
fi

LOG=${LOG:-$(mktemp)}
docker_args=(run --rm -v "$REPO:/repo" -w /repo)
case "$RUN_AS" in
  owner) docker_args+=(--user "$(stat -c '%u:%g' "$REPO")" -e HOME=/tmp
           -e GIT_CONFIG_COUNT=1 -e GIT_CONFIG_KEY_0=safe.directory -e GIT_CONFIG_VALUE_0=/repo) ;;
  old-action) docker_args+=(-e HOME=/github/home) ;;
  *) fail "unknown PARKIO_GITLEAKS_RUN_AS '$RUN_AS'" ;;
esac
echo "secret scan: $DESC ($MODE); expecting $MIN..$MAX scanned commits"
docker "${docker_args[@]}" "$IMAGE" git --config /repo/.gitleaks.toml --redact --no-banner --verbose \
  --report-format sarif --report-path "/repo/$REPORT" --log-opts="$LOG_OPTS" /repo > "$LOG" 2>&1
rc=$?
sed -E 's/\x1b\[[0-9;]*m//g' "$LOG" | grep -E 'commits scanned|leaks|ERR|fatal|Finding:|RuleID:|File:|Commit:' | head -60 || true
verdict=$(assess "$LOG" "$rc" "$MIN" "$MAX"); status=$?
echo "$verdict"
if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  {
    echo "### Secret scan"
    echo
    echo "| Scope | Expected scanned commits | Result |"
    echo "|---|---|---|"
    echo "| $DESC | $MIN..$MAX | ${verdict#secret scan: } |"
  } >> "$GITHUB_STEP_SUMMARY"
fi
exit "$status"
