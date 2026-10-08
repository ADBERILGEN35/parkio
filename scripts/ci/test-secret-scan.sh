#!/usr/bin/env bash
# Self-test of scripts/ci/secret-scan.sh against disposable repositories (owner decision 2026-10-08,
# item 6). It proves, with the repository's own .gitleaks.toml and the pinned image, that:
# - a disposable seeded secret (generated here, never committed to this repository, never printed)
#   fails the scan, at the start, in the middle and at the end of a range;
# - a range is honoured: a clean range next to seeded commits passes;
# - the "commits scanned" count is checked against the range's bounds;
# - scanner and git errors fail the scan, including the ownership failure that made the old step pass
#   with 0 commits scanned (reproduced by running the image as the old step did: root, with the HOME
#   GitHub sets for container actions, on a repository owned by a non-root user).
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCAN="$ROOT/scripts/ci/secret-scan.sh"
IMAGE="${PARKIO_GITLEAKS_IMAGE:-zricethezav/gitleaks@sha256:cdbb7c955abce02001a9f6c9f602fb195b7fadc1e812065883f695d1eeaba854}"
PASS=0 FAILS=0
ok() { PASS=$((PASS+1)); echo "PASS $*"; }
bad() { FAILS=$((FAILS+1)); echo "FAIL $*"; }
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

canary() {  # a GitHub-token-shaped synthetic value; random, never echoed
  printf 'ghp_%s' "$(LC_ALL=C tr -dc 'A-Za-z0-9' < /dev/urandom | head -c 36)"
}
R="$TMP/repo"
git init -q -b main "$R"
git -C "$R" config user.name "secret-scan self-test"
git -C "$R" config user.email "self-test@example.invalid"
cp "$ROOT/.gitleaks.toml" "$R/.gitleaks.toml"
commit() { git -C "$R" add -A && git -C "$R" commit -q -m "$1" && git -C "$R" rev-parse HEAD; }
echo "clean" > "$R/a.txt";                     C1=$(commit "c1 clean")
mkdir -p "$R/canary"; echo "token=$(canary)" > "$R/canary/one.txt"; C2=$(commit "c2 seeded")
echo "clean" > "$R/b.txt";                     C3=$(commit "c3 clean")
echo "token=$(canary)" > "$R/canary/two.txt";  C4=$(commit "c4 seeded")
echo "clean" > "$R/c.txt";                     C5=$(commit "c5 clean")
git -C "$R" checkout -q -b side "$C5"; echo "side" > "$R/d.txt"; C6=$(commit "c6 clean side")
git -C "$R" checkout -q main; git -C "$R" merge -q --no-ff -m "merge side" side; M=$(git -C "$R" rev-parse HEAD)

run() { bash "$SCAN" --repo "$R" --report scan.sarif --log "$TMP/log" "$@" > "$TMP/out" 2>&1; }
expect_pass() { local name=$1; shift; if run "$@"; then ok "$name"; else bad "$name"; cat "$TMP/out"; fi; }
expect_fail() { local name=$1 pattern=$2; shift 2; if run "$@"; then bad "$name (passed)"; cat "$TMP/out";
  elif grep -qE "$pattern" "$TMP/out"; then ok "$name"; else bad "$name (wrong reason)"; cat "$TMP/out"; fi; }

expect_pass "a clean single-commit range passes" --range "$C4..$C5"
grep -q 'commits scanned 1 (expected 1..1)' "$TMP/out" && ok "the count of a one-commit range is exactly 1" || { bad "one-commit count"; cat "$TMP/out"; }
expect_fail "a seed in the middle of the range fails" 'leaks found: 1' --range "$C1..$C3"
expect_fail "a seed at the start of the range fails" 'leaks found: 1' --range "$C1..$C2"
expect_fail "a seed at the end of the range fails" 'leaks found: 1' --range "$C3..$C4"
expect_fail "both seeds in a wide range are found" 'leaks found: 2' --range "$C1..$C5"
expect_pass "the root commit alone passes" --full "$C1"
expect_fail "the full history finds both seeds" 'leaks found: 2' --full "$M"
expect_pass "a merge commit is not counted and the merged clean branch passes" --range "$C5..$M"
grep -q 'expected 1..1' "$TMP/out" && ok "merge commits are excluded from the bounds" || { bad "merge bounds"; cat "$TMP/out"; }
expect_pass "pull_request event scans base..head" --event pull_request --base "$C4" --head "$C5"
expect_fail "push from a zero 'before' scans the full history" 'leaks found: 2' --event push --before 0000000000000000000000000000000000000000 --after "$C5"
expect_pass "push scans before..after" --event push --before "$C4" --after "$C5"
expect_fail "an unknown range start fails closed" 'cannot resolve' --range "deadbeef..$C5"
git clone -q --depth 1 "file://$R" "$TMP/shallow"
if bash "$SCAN" --repo "$TMP/shallow" --full HEAD > "$TMP/out" 2>&1; then bad "a shallow clone passed"; else grep -q 'shallow' "$TMP/out" && ok "a shallow clone fails closed" || { bad "shallow message"; cat "$TMP/out"; }; fi

# Log assessment: the exact output of the broken step, and other malformed outputs.
printf '%s\n' "11:12PM ERR [git] fatal: detected dubious ownership in repository at '/github/workspace'" "11:12PM INF 0 commits scanned." "11:12PM INF no leaks found" > "$TMP/broken.log"
if bash "$SCAN" --check-log "$TMP/broken.log" --min 5 --max 7 > "$TMP/out" 2>&1; then bad "the old step's output passed"; else grep -q 'scanner error' "$TMP/out" && grep -q '0 commits scanned, expected between 5' "$TMP/out" && ok "the old step's output (ownership error, 0 commits) fails" || { bad "old output reasons"; cat "$TMP/out"; }; fi
printf '%s\n' "INF no leaks found" > "$TMP/nocount.log"
if bash "$SCAN" --check-log "$TMP/nocount.log" --min 0 --max 0 > "$TMP/out" 2>&1; then bad "a log without a count passed"; else ok "a log without a 'commits scanned' line fails"; fi
printf '%s\n' "INF 3 commits scanned." "INF no leaks found" > "$TMP/low.log"
if bash "$SCAN" --check-log "$TMP/low.log" --min 4 --max 9 > "$TMP/out" 2>&1; then bad "a count below the bounds passed"; else ok "a count below the range's bounds fails"; fi
printf '%s\n' "INF 4 commits scanned." "INF no leaks found" > "$TMP/good.log"
if bash "$SCAN" --check-log "$TMP/good.log" --min 4 --max 9 > "$TMP/out" 2>&1; then ok "a count inside the bounds with no leaks passes"; else bad "good log failed"; cat "$TMP/out"; fi

# The ownership failure itself, configured exactly as the old step ran: root in the container, with the
# HOME GitHub gives container actions (so the image's /root/.gitconfig safe.directory=* is not read), on a
# repository owned by a non-root user.
seen_uid=$(docker run --rm --entrypoint stat -v "$R:/r" "$IMAGE" -c %u /r 2>/dev/null || echo "?")
if [ "$seen_uid" != "0" ] && [ "$seen_uid" != "?" ]; then
  if PARKIO_GITLEAKS_RUN_AS=old-action run --range "$C4..$C5"; then bad "the old step's configuration passed"; cat "$TMP/out";
  elif grep -q 'scanner error' "$TMP/out" && grep -q 'dubious ownership' "$TMP/log"; then
    ok "the old step's configuration (root, HOME=/github/home, uid-$seen_uid repository) fails closed on dubious ownership"
  else bad "ownership failure reason"; cat "$TMP/out"; fi
else
  bad "cannot reproduce the ownership failure: the container sees the repository as uid '$seen_uid'"
fi

echo; echo "=== secret-scan self-test: pass=$PASS fail=$FAILS ==="
[ "$FAILS" -eq 0 ]
