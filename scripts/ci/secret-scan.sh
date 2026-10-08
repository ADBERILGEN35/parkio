#!/usr/bin/env bash
# Secret scan with gitleaks that fails closed (owner decision 2026-10-08, item 6).
#
# Why: the earlier `docker://zricethezav/gitleaks` step ran the image as root on a workspace owned by the
# runner user. The image marks every directory safe only in /root/.gitconfig, and GitHub runs container
# actions with HOME=/github/home, so that file is not read: git refused the repository ("detected dubious
# ownership"), gitleaks logged the error, reported "0 commits scanned" and exited 0, and the required
# "Secret scan" check passed without scanning, from its introduction (2026-06-18) on. Locally (HOME=/root)
# the same image works, which hid it.
#
# What this does:
# - runs the pinned image as the owner of the repository directory (and marks it safe for git);
# - scans an explicit commit range: a pull request's own commits (base..head), a push's new commits
#   (before..after; the full history of `after` for a new branch or an unknown `before`), or the full
#   history of the checked-out commit for a manual or scheduled run (audit);
# - keeps the scanned tree from weakening its own scan: gitleaks sees only the repository's git directory
#   (mounted read-only; gitleaks reads a `.gitleaksignore` from its scan path even when `-i` points
#   elsewhere, so the working tree is not given to it), git attributes come from the empty tree (a
#   `.gitattributes` cannot mark files binary), and a pull request is scanned with its base commit's
#   `.gitleaks.toml` (an allowlist takes effect only once merged);
# - fails on a shallow clone, an unresolvable or empty pull-request range, any scanner or git error line,
#   gitleaks not running the exact `git log` range, a missing "commits scanned" line, a count outside the
#   expected bounds, an unexpected exit code, and any finding;
# - proves the range was scanned: the debug log must show gitleaks executing `git log -p -U0 <range>` with
#   the resolved range, and gitleaks' count must lie between the commits that add a text line and the
#   non-merge commits of that range (gitleaks counts commits with at least one file entry that is not
#   deleted, binary or mode-only). Merge commits are not scanned (git log -p shows no diff for them).
# Findings are listed from the SARIF report (rule, file, line, commit; never the value). The log carries no
# finding values: gitleaks runs without --verbose, at debug level (trace level would print candidates).
#
# Usage:
#   secret-scan.sh --repo DIR (--range A..B | --full REV) [--config-rev REV] [--report FILE] [--log FILE]
#   secret-scan.sh --repo DIR --event NAME [--base SHA --head SHA] [--before SHA --after SHA] [...]
#   secret-scan.sh --check-log LOG --min N --max M [--expect-opts OPTS]  (log assessment only; self-test)
# DIR must be a full clone (a linked worktree's .git file is not visible inside the container).
# FILE paths are relative to DIR. Env: PARKIO_GITLEAKS_IMAGE (default: v8.28.0 pinned by digest);
# PARKIO_GITLEAKS_RUN_AS=owner (default) | old-action (self-test only: root with GitHub's container-action
# HOME, exactly as the old step ran, to reproduce the ownership failure).
# Exit: 0 clean; 1 findings or a failed check; 2 usage.
set -uo pipefail

IMAGE="${PARKIO_GITLEAKS_IMAGE:-zricethezav/gitleaks@sha256:cdbb7c955abce02001a9f6c9f602fb195b7fadc1e812065883f695d1eeaba854}"
RUN_AS="${PARKIO_GITLEAKS_RUN_AS:-owner}"
ZERO_SHA=0000000000000000000000000000000000000000
EMPTY_TREE=4b825dc642cb6eb9a060e54bf8d69288fbee4904

usage() { sed -n '2,38p' "$0" >&2; exit 2; }
fail() { echo "secret scan: FAIL: $*" >&2; exit 1; }

# assess LOG RC MIN MAX OPTS -> exit 0 (clean) or 1, printing one verdict line.
assess() {
  python3 - "$@" <<'PY'
import re, sys
log, rc, lo, hi, opts = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), int(sys.argv[4]), sys.argv[5]
text = re.sub(r"\x1b\[[0-9;]*m", "", open(log, encoding="utf-8", errors="replace").read())
problems = []
errors = [l.strip() for l in text.splitlines() if re.search(r"\bERR\b|\bFTL\b|fatal:", l)]
if errors:
    problems.append("scanner error: " + errors[0][:200])
if opts:
    executed = re.findall(r"executing: \S*git -C /repo log -p -U0 (.+)$", text, re.M)
    if not executed:
        problems.append("the log does not show gitleaks executing git log")
    elif any(e.strip() != opts for e in executed):
        problems.append(f"gitleaks ran 'git log {executed[0].strip()}', expected '{opts}'")
counts = re.findall(r"(\d+) commits scanned", text)
scanned = int(counts[-1]) if counts else None
if scanned is None:
    problems.append("no 'commits scanned' line in the gitleaks output")
elif not lo <= scanned <= hi:
    problems.append(f"{scanned} commits scanned, expected between {lo} (commits that add text) and {hi} (non-merge commits)")
leaks = re.findall(r"leaks found: (\d+)", text)
found = int(leaks[-1]) if leaks else 0
if found:
    problems.append(f"leaks found: {found} (see the findings list and the SARIF report)")
else:
    if rc != 0:
        problems.append(f"gitleaks exited {rc} without reporting leaks")
    if "no leaks found" not in text:
        problems.append("gitleaks did not report a result line")
summary = f"commits scanned {scanned if scanned is not None else '?'} (expected {lo}..{hi}), leaks {found}"
if problems:
    print("secret scan: FAIL: " + "; ".join(problems) + f" [{summary}]")
    sys.exit(1)
print("secret scan: PASS: " + summary)
PY
}

# bounds REVSPEC -> "MIN MAX": commits that add a text line, and non-merge commits. Fails on any git error.
bounds() {
  local max patch min
  max=$(git -C "$REPO" rev-list --no-merges --count "$1") || return 1
  patch=$(git -C "$REPO" log --no-merges -p -U0 --format='COMMIT %H' "$1") || return 1
  min=$(printf '%s\n' "$patch" | python3 -c '
import sys
added = 0; cur = False
for line in sys.stdin:
    if line.startswith("COMMIT "):
        added += cur; cur = False
    elif line.startswith("+") and not line.startswith("+++"):
        cur = True
print(added + cur)
') || return 1
  echo "$min $max"
}

REPO="" RANGE="" FULL="" EVENT="" BASE="" HEAD="" BEFORE="" AFTER="" REPORT="gitleaks.sarif" LOG="" CHECK_LOG="" MIN="" MAX="" EXPECT_OPTS="" CONFIG_REV=""
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
    --config-rev) CONFIG_REV=$2; shift 2 ;;
    --report) REPORT=$2; shift 2 ;;
    --log) LOG=$2; shift 2 ;;
    --check-log) CHECK_LOG=$2; shift 2 ;;
    --min) MIN=$2; shift 2 ;;
    --max) MAX=$2; shift 2 ;;
    --expect-opts) EXPECT_OPTS=$2; shift 2 ;;
    -h|--help) usage ;;
    *) echo "unknown argument: $1" >&2; usage ;;
  esac
done

if [ -n "$CHECK_LOG" ]; then
  [ -n "$MIN" ] && [ -n "$MAX" ] || usage
  assess "$CHECK_LOG" 0 "$MIN" "$MAX" "$EXPECT_OPTS"; exit $?
fi

[ -n "$REPO" ] && [ -d "$REPO/.git" ] || fail "--repo must be a full git clone with a .git directory (got '${REPO:-}'; a linked worktree cannot be scanned in the container)"
REPO=$(cd "$REPO" && pwd)
case "$REPORT" in /*|*..*) fail "--report must be a relative path inside the repository (got '$REPORT')" ;; esac
[ "$(git -C "$REPO" rev-parse --is-shallow-repository)" = false ] || fail "the clone is shallow; the scan needs the full history (fetch-depth: 0)"

resolve() { git -C "$REPO" rev-parse --verify --quiet "$1^{commit}"; }
MODE="" LOG_OPTS="" DESC=""
if [ -n "$EVENT" ]; then
  case "$EVENT" in
    pull_request|pull_request_target)
      [ -n "$BASE" ] && [ -n "$HEAD" ] || fail "event $EVENT needs --base and --head"
      RANGE="$BASE..$HEAD"; CONFIG_REV=${CONFIG_REV:-$BASE} ;;
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
  total=$(git -C "$REPO" rev-list --count "$A..$B") || fail "cannot count the commits of $A..$B"
  if [ "$total" -eq 0 ]; then
    case "$EVENT" in pull_request|pull_request_target) fail "the pull request range $A..$B is empty: the base/head mapping is wrong" ;; esac
    echo "secret scan: notice: the range $A..$B holds no commit"
  fi
elif [ -n "$FULL" ]; then
  B=$(resolve "$FULL") || fail "cannot resolve '$FULL'"
  MODE=full; LOG_OPTS="$B"; DESC="full history of $B"
else
  usage
fi
BOUNDS=$(bounds "$LOG_OPTS") || fail "cannot count the commits of $LOG_OPTS"
read -r MIN MAX <<< "$BOUNDS"
[ "$MAX" -gt 0 ] || [ "$MODE" = range ] || fail "the history of $B holds no non-merge commit"

CONFIG_DIR=$(mktemp -d)
cleanup() { rm -rf "$CONFIG_DIR"; [ -n "${OWN_LOG:-}" ] && rm -f "$OWN_LOG"; }
trap cleanup EXIT
CONFIG_REV=$(resolve "${CONFIG_REV:-$B}") || fail "cannot resolve the config revision"
git -C "$REPO" show "$CONFIG_REV:.gitleaks.toml" > "$CONFIG_DIR/.gitleaks.toml" 2>/dev/null \
  || fail "no .gitleaks.toml at the config revision $CONFIG_REV"
chmod 0755 "$CONFIG_DIR"; chmod 0644 "$CONFIG_DIR/.gitleaks.toml"
if [ -z "$LOG" ]; then LOG=$(mktemp); OWN_LOG=$LOG; fi

# The git directory only (read-only) at /repo: no working-tree file can reach gitleaks. The report is
# written through a separate mount of the repository directory.
docker_args=(run --rm -v "$REPO/.git:/repo:ro" -v "$REPO:/work" -v "$CONFIG_DIR:/scan-config:ro" -w /tmp
  -e "GIT_ATTR_SOURCE=$EMPTY_TREE")
case "$RUN_AS" in
  owner) docker_args+=(--user "$(stat -c '%u:%g' "$REPO")" -e HOME=/tmp
           -e GIT_CONFIG_COUNT=1 -e GIT_CONFIG_KEY_0=safe.directory -e GIT_CONFIG_VALUE_0=/repo) ;;
  old-action) docker_args+=(-e HOME=/github/home) ;;
  *) fail "unknown PARKIO_GITLEAKS_RUN_AS '$RUN_AS'" ;;
esac
echo "secret scan: $DESC ($MODE); config from $CONFIG_REV; expecting $MIN..$MAX scanned commits"
docker "${docker_args[@]}" "$IMAGE" git --config /scan-config/.gitleaks.toml --gitleaks-ignore-path /scan-config \
  --redact --no-banner --log-level debug --report-format sarif --report-path "/work/$REPORT" \
  --log-opts="$LOG_OPTS" /repo > "$LOG" 2>&1
rc=$?
sed -E 's/\x1b\[[0-9;]*m//g' "$LOG" | grep -E 'executing:|commits scanned|leaks|ERR|FTL|fatal' | head -20 || true
if [ -s "$REPO/$REPORT" ]; then
  python3 - "$REPO/$REPORT" <<'PY' || true
import json, sys
runs = json.load(open(sys.argv[1], encoding="utf-8")).get("runs", [])
for run in runs:
    for r in run.get("results", [])[:100]:
        loc = (r.get("locations") or [{}])[0].get("physicalLocation", {})
        path = loc.get("artifactLocation", {}).get("uri", "?")
        line = loc.get("region", {}).get("startLine", "?")
        commit = (r.get("partialFingerprints") or {}).get("commitSha", "")[:8]
        print(f"  finding: rule={r.get('ruleId')} file={path}:{line} commit={commit}")
PY
fi
verdict=$(assess "$LOG" "$rc" "$MIN" "$MAX" "$LOG_OPTS"); status=$?
echo "$verdict"
if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  {
    echo "### Secret scan"
    echo
    echo "| Scope | Config from | Expected scanned commits | Result |"
    echo "|---|---|---|---|"
    echo "| $DESC | $CONFIG_REV | $MIN..$MAX | ${verdict#secret scan: } |"
  } >> "$GITHUB_STEP_SUMMARY"
fi
exit "$status"
