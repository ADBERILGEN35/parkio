#!/usr/bin/env bash
# Self-test of scripts/ci/secret-scan.sh against disposable repositories (owner decision 2026-10-08,
# item 6). It proves, with the repository's own .gitleaks.toml and the pinned image, that:
# - disposable seeded secrets (generated here, never committed to this repository, never printed) fail
#   the scan: a GitHub-token-shaped value and a freshly generated RSA private key, at the start, in the
#   middle and at the end of a range, and in the full history;
# - each event maps to its intended range: a seeded commit inside the event's range is found, the logged
#   range and count match, and an empty pull-request range fails;
# - the scanned tree cannot weaken its own scan: a `.gitattributes` marking the file binary, an
#   uncommitted `.gitleaksignore` with the finding's fingerprint, an inline `gitleaks:allow` comment, and
#   a pull request that allowlists its own secret in `.gitleaks.toml` or exempts its own finding by
#   fingerprint all still fail; a fingerprint exception applies only from the config revision (a pull
#   request's base; the scanned tip of a push or an audit) and only to that one commit, file and line;
# - the seeded values never appear in the scan's log or output;
# - the "commits scanned" count and gitleaks' executed `git log` range are checked;
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
trap 'rm -rf "$TMP" 2>/dev/null || true' EXIT

token() { python3 -c 'import secrets, string; print("ghp_" + "".join(secrets.choice(string.ascii_letters + string.digits) for _ in range(36)))'; }
pem() { openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 2>/dev/null; }

new_repo() {  # new_repo DIR: an empty repository with this repository's gitleaks config
  git init -q -b main "$1"
  git -C "$1" config user.name "secret-scan self-test"
  git -C "$1" config user.email "self-test@example.invalid"
  cp "$ROOT/.gitleaks.toml" "$1/.gitleaks.toml"
  echo "scan.sarif" >> "$1/.git/info/exclude"   # reports are written into the repository; never commit them
}
commit() { git -C "$R" add -A && git -C "$R" commit -q -m "$1" && git -C "$R" rev-parse HEAD; }

R="$TMP/repo"; new_repo "$R"
echo "clean" > "$R/a.txt";                              C1=$(commit "c1 clean")
mkdir -p "$R/canary"; T1=$(token); echo "token=$T1" > "$R/canary/one.txt"; C2=$(commit "c2 seeded token")
echo "clean" > "$R/b.txt";                              C3=$(commit "c3 clean")
pem > "$R/canary/two.pem";                              C4=$(commit "c4 seeded private key")
echo "clean" > "$R/c.txt";                              C5=$(commit "c5 clean")
git -C "$R" checkout -q -b side "$C5"; echo "side" > "$R/d.txt"; C6=$(commit "c6 clean side")
git -C "$R" checkout -q main; git -C "$R" merge -q --no-ff -m "merge side" side; M=$(git -C "$R" rev-parse HEAD)

run() { bash "$SCAN" --repo "$R" --report scan.sarif --log "$TMP/log" "$@" > "$TMP/out" 2>&1; }
expect_pass() { local name=$1; shift; if run "$@"; then ok "$name"; else bad "$name"; cat "$TMP/out"; fi; }
expect_fail() { local name=$1 pattern=$2; shift 2; if run "$@"; then bad "$name (passed)"; cat "$TMP/out";
  elif grep -qE "$pattern" "$TMP/out"; then ok "$name"; else bad "$name (wrong reason)"; cat "$TMP/out"; fi; }
logged() { local name=$1 range=$2 count=$3
  if grep -q "commits $range (range)" "$TMP/out" && grep -q "commits scanned $count (expected $count..$count)" "$TMP/out"; then ok "$name"; else bad "$name"; cat "$TMP/out"; fi; }

expect_pass "a clean single-commit range passes" --range "$C4..$C5"
logged "  its range and count are logged exactly" "$C4..$C5" 1
expect_fail "a seeded token in the middle of the range fails" 'leaks found: 1' --range "$C1..$C3"
expect_fail "a seeded token at the start of the range fails" 'leaks found: 1' --range "$C1..$C2"
expect_fail "a seeded private key at the end of the range fails" 'leaks found: 1' --range "$C3..$C4"
grep -q 'rule=private-key' "$TMP/out" && ok "  the second rule type (private-key) is detected" || { bad "private-key rule"; cat "$TMP/out"; }
expect_fail "both seeds in a wide range are found" 'leaks found: 2' --range "$C1..$C5"
if grep -qF "$T1" "$TMP/log" "$TMP/out"; then bad "  a seeded value appears in the log or output"; else ok "  no seeded value appears in the log or output"; fi
expect_pass "the root commit alone passes" --full "$C1"
expect_fail "the full history finds both seeds" 'leaks found: 2' --full "$M"
expect_pass "a merge commit is not counted and the merged clean branch passes" --range "$C5..$M"
grep -q 'expected 1..1' "$TMP/out" && ok "  merge commits are excluded from the bounds" || { bad "merge bounds"; cat "$TMP/out"; }
expect_fail "pull_request scans base..head (seeded)" 'leaks found: 1' --event pull_request --base "$C3" --head "$C4"
logged "  pull_request range and count" "$C3..$C4" 1
expect_fail "push scans before..after (seeded)" 'leaks found: 1' --event push --before "$C1" --after "$C2"
logged "  push range and count" "$C1..$C2" 1
expect_pass "push scans only its new commits (clean)" --event push --before "$C4" --after "$C5"
expect_fail "push from a zero 'before' scans the full history" 'leaks found: 2' --event push --before 0000000000000000000000000000000000000000 --after "$C5"
expect_fail "an empty pull_request range fails closed" 'pull request range .* is empty' --event pull_request --base "$C5" --head "$C5"
expect_fail "a reversed pull_request range fails closed" 'is empty' --event pull_request --base "$C5" --head "$C4"
expect_fail "an unknown range start fails closed" 'cannot resolve' --range "deadbeef..$C5"
git clone -q --depth 1 "file://$R" "$TMP/shallow"
if bash "$SCAN" --repo "$TMP/shallow" --full HEAD > "$TMP/out" 2>&1; then bad "a shallow clone passed"; else grep -q 'shallow' "$TMP/out" && ok "a shallow clone fails closed" || { bad "shallow message"; cat "$TMP/out"; }; fi

# The scanned tree cannot weaken its own scan.
B="$TMP/bypass"; new_repo "$B"; R0=$R; R=$B
echo "clean" > "$R/a.txt";                                       D1=$(commit "d1 clean")
printf '*.env binary\n' > "$R/.gitattributes";                   D2=$(commit "d2 mark env files binary")
echo "TOKEN=$(token)" > "$R/x.env";                              D3=$(commit "d3 seeded token in a 'binary' file")
expect_fail "a .gitattributes 'binary' mark does not hide a seeded file" 'leaks found: 1' --range "$D2..$D3"
printf '%s:x.env:github-pat:1\n' "$D3" > "$R/.gitleaksignore"
expect_fail "a .gitleaksignore with the finding's fingerprint is not honoured" 'leaks found: 1' --range "$D2..$D3"
rm -f "$R/.gitleaksignore"
echo "TOKEN=$(token) # gitleaks:allow" > "$R/allowed.txt";        D3A=$(commit "d3a seeded token with an inline allow comment")
expect_fail "an inline 'gitleaks:allow' comment does not suppress a finding" 'leaks found: 1' --range "$D3..$D3A"
D3=$D3A
python3 - "$R/.gitleaks.toml" <<'PY'
import sys
with open(sys.argv[1], "a", encoding="utf-8") as fh:
    fh.write('\n[[allowlists]]\ndescription = "self-test: a pull request allowlisting its own secret"\npaths = [\'\'\'^canary/\'\'\']\n')
PY
mkdir -p "$R/canary"; echo "token=$(token)" > "$R/canary/pr.txt"; D4=$(commit "d4 allowlist plus seeded token")
expect_fail "a pull request's own allowlist does not apply to its scan (base config)" 'leaks found: 1' --event pull_request --base "$D3" --head "$D4"
expect_pass "  the same allowlist applies once it is the scanned revision's config (merged state)" --range "$D3..$D4"
R=$R0

# Fingerprint exceptions (.gitleaksignore) come from the config revision only: a pull request cannot
# exempt its own commits; the exceptions merged into the scanned tip apply to pushes and audits.
F="$TMP/fingerprints"; new_repo "$F"; R=$F
echo "clean" > "$R/a.txt";                                       E1=$(commit "e1 clean")
echo "TOKEN=$(token)" > "$R/x.env";                              E2=$(commit "e2 seeded token")
expect_fail "a seeded token is found in a full-history audit" 'leaks found: 1' --full "$E2"
printf '# self-test exception\n%s:x.env:github-pat:1\n' "$E2" > "$R/.gitleaksignore"; E3=$(commit "e3 fingerprint exception for e2")
expect_pass "a fingerprint exception committed at the scanned tip suppresses that finding in an audit" --full "$E3"
grep -q '1 fingerprint exception(s) from' "$TMP/out" && ok "  the exception count is reported" || { bad "exception count not reported"; cat "$TMP/out"; }
expect_fail "a pull request's own fingerprint exception does not apply (base config)" 'leaks found: 1' --event pull_request --base "$E1" --head "$E3"
expect_pass "  the same exception applies to the push that follows the merge (tip config)" --event push --before "$E1" --after "$E3"
printf '%s:x.env:github-pat:2\n' "$E2" > "$R/.gitleaksignore";   E4=$(commit "e4 fingerprint for another line")
expect_fail "a fingerprint for another line does not suppress the finding" 'leaks found: 1' --full "$E4"
printf 'x.env:github-pat:1\n' > "$R/.gitleaksignore";               E5=$(commit "e5 commit-less entry")
expect_fail "a commit-less 'file:rule:line' entry fails the scan instead of suppressing every commit" 'invalid fingerprint exception' --full "$E5"
printf 'x.env\n' > "$R/.gitleaksignore";                              E6=$(commit "e6 bare path entry")
expect_fail "a bare path entry fails the scan" 'invalid fingerprint exception' --full "$E6"
printf '# comment\r\n\r\n%s:x.env:github-pat:1  \r\n' "$E2" > "$R/.gitleaksignore"; E7=$(commit "e7 CRLF, blank and trailing-space lines")
expect_pass "comment, blank and CRLF lines around a valid entry are tolerated" --full "$E7"
printf '%s:x.env:github-pat:1\n' "$E2" > "$R/.gitleaksignore";   E8=$(commit "e8 valid entry at the base")
printf 'x.env:github-pat:1\n' > "$R/.gitleaksignore";               E9=$(commit "e9 pull request head makes the entry commit-less")
expect_fail "a pull request whose head carries an invalid entry fails (format-checked, never applied)" 'invalid fingerprint exception at the scanned head' --event pull_request --base "$E8" --head "$E9"
printf '# tidy\n%s:x.env:github-pat:1\n' "$E2" > "$R/.gitleaksignore"; E10=$(commit "e10 head restores a valid file")
expect_pass "a pull request whose head carries a valid file passes (base exceptions applied)" --event pull_request --base "$E8" --head "$E10"
R=$R0

# Log assessment: the exact output of the broken step, and other malformed outputs.
check() { bash "$SCAN" --check-log "$1" --min "$2" --max "$3" ${4:+--expect-opts "$4"} > "$TMP/out" 2>&1; }
printf '%s\n' "11:12PM ERR [git] fatal: detected dubious ownership in repository at '/github/workspace'" "11:12PM INF 0 commits scanned." "11:12PM INF no leaks found" > "$TMP/broken.log"
if check "$TMP/broken.log" 5 7; then bad "the old step's output passed"; else grep -q 'scanner error' "$TMP/out" && grep -q '0 commits scanned, expected between 5' "$TMP/out" && ok "the old step's output (ownership error, 0 commits) fails" || { bad "old output reasons"; cat "$TMP/out"; }; fi
printf '%s\n' "INF no leaks found" > "$TMP/nocount.log"
if check "$TMP/nocount.log" 0 0; then bad "a log without a count passed"; else ok "a log without a 'commits scanned' line fails"; fi
printf '%s\n' "INF 3 commits scanned." "INF no leaks found" > "$TMP/low.log"
if check "$TMP/low.log" 4 9; then bad "a count below the bounds passed"; else ok "a count below the range's bounds fails"; fi
printf '%s\n' "DBG executing: /usr/bin/git -C /repo log -p -U0 aaaa..bbbb" "INF 4 commits scanned." "INF no leaks found" > "$TMP/other-range.log"
if check "$TMP/other-range.log" 4 4 "aaaa..cccc"; then bad "a different executed range passed"; else grep -q "expected 'aaaa..cccc'" "$TMP/out" && ok "gitleaks executing a different git log range fails" || { bad "range identity reason"; cat "$TMP/out"; }; fi
if check "$TMP/other-range.log" 4 9 "aaaa..bbbb"; then ok "the executed range and a count inside the bounds pass"; else bad "good log failed"; cat "$TMP/out"; fi

# The ownership failure itself, configured exactly as the old step ran (kept last: it leaves root-owned
# files in the repository it scans).
seen_uid=$(docker run --rm --entrypoint stat -v "$R:/r" "$IMAGE" -c %u /r 2>/dev/null || echo "?")
if [ "$seen_uid" != "0" ] && [ "$seen_uid" != "?" ]; then
  if PARKIO_GITLEAKS_RUN_AS=old-action run --range "$C4..$C5"; then bad "the old step's configuration passed"; cat "$TMP/out";
  elif grep -q 'scanner error' "$TMP/out" && grep -q 'dubious ownership' "$TMP/log"; then
    ok "the old step's configuration (root, HOME=/github/home, uid-$seen_uid repository) fails closed on dubious ownership"
  else bad "ownership failure reason"; cat "$TMP/out"; fi
  docker run --rm --entrypoint sh -v "$TMP:/t" "$IMAGE" -c 'rm -rf /t/repo /t/bypass' >/dev/null 2>&1 || true
else
  bad "cannot reproduce the ownership failure: the container sees the repository as uid '$seen_uid'"
fi

echo; echo "=== secret-scan self-test: pass=$PASS fail=$FAILS ==="
[ "$FAILS" -eq 0 ]
