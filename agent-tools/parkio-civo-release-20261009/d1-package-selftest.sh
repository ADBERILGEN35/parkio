#!/usr/bin/env bash
# Local self-test of d1-marketing-package.sh against a local bare repository that shares this repository's objects. A test
# merge of PR #330's reviewed head is made with git commit-tree (nothing is written to this repository or to GitHub).
# S1 merge of the reviewed head on api -> PACKAGE VERIFIED (19/19); S2 not merged -> STOP; S3 a "merge" whose web/marketing
# tree is not the reviewed one -> STOP.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"; TOOL="$HERE/d1-marketing-package.sh"
COMMON="$(git -C "$HERE" rev-parse --path-format=absolute --git-common-dir)"
H=272273c405c69beec94ce63913e7ddfd803595c3; API=22f9699038cdae15ffff5dd7433c4a64aed0a951
W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT
git clone -q --bare --shared "$COMMON" "$W/bare.git" 2>/dev/null
fail=0
run() { rm -rf "$W/home" && mkdir -p "$W/home"
        D1P_REPO_URL="file://$W/bare.git" HOME="$W/home" bash "$TOOL" > "$W/$1" 2>&1 && echo 0 > "$W/$1.rc" || echo $? > "$W/$1.rc"; }
check() { if grep -qF -- "$2" "$W/$1"; then echo "ok   $1: $2"; else echo "FAIL $1: $2"; fail=1; fi; }
rc_is() { [ "$(cat "$W/$1.rc")" = "$2" ] && echo "ok   $1 exit $2" || { echo "FAIL $1 exit $(cat "$W/$1.rc"), want $2"; fail=1; }; }
git -C "$W/bare.git" update-ref refs/heads/api "$API"
run s2; rc_is s2 1; check s2 "STOP: the reviewed head $H is not merged into origin/api yet"
BAD=$(git -C "$W/bare.git" commit-tree "$API^{tree}" -p "$API" -p "$H" -m "bad merge (test)")
git -C "$W/bare.git" update-ref refs/heads/api "$BAD"
run s3; rc_is s3 1; check s3 "STOP: the merge's web/marketing differs from the reviewed head"
M=$(git -C "$W/bare.git" commit-tree "$H^{tree}" -p "$API" -p "$H" -m "Merge pull request #330 (test)")
git -C "$W/bare.git" update-ref refs/heads/api "$M"
run s1; rc_is s1 0
grep -v 'filtering not recognized' "$W/s1" | sed "s#$W#<W>#g"
check s1 "merge commit $M on origin/api (second parent = reviewed head; web/marketing tree identical)"
check s1 "files: 19 in the zip, 19 expected; matching: 19"
check s1 "PACKAGE VERIFIED"
[ -f "$W/home/parkio-marketing-${M:0:12}/parkio-marketing-launch-${M:0:12}.zip" ] && echo "ok   zip written" || { echo "FAIL zip"; fail=1; }
[ -z "$(ls /tmp | grep '^parkio-mkt-' || true)" ] && echo "ok   temporary clones removed" || { echo "FAIL temporary clone left"; fail=1; }
[ "$fail" -eq 0 ] && echo "SELFTEST PASS" || echo "SELFTEST FAIL"
exit "$fail"
