#!/usr/bin/env bash
# Self-test of e-account-diag.sh with T1 v6 (commit 8880ec3e, as installed on the host when E failed), the release smoke
# script at the PIN, a production-like env file without PARKIO_REAL_USER_EMAIL and E's records as written on 2026-10-12.
# G1 the host's facts -> CAUSE CONFIRMED (exit 0), no value, address or password printed. N1 the shell sets the e-mail,
# N2 the env file carries it, N3 E's smoke log has no login line, N4 no password -> CAUSE NOT CONFIRMED (exit 1).
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"; TOOL="$HERE/e-account-diag.sh"
REPO="${EADT_REPO:-$(git -C "$HERE" rev-parse --show-toplevel)}"
W="$(mktemp -d)"; trap 'rm -rf "${W:?}"' EXIT
mkdir -p "$W/out/tools" "$W/out/e-launch/20261012T133335Z" "$W/rel/scripts"
git -C "$REPO" show 8880ec3e4038ce460e503ff8263ce96794188aa7:agent-tools/parkio-civo-release-20261009/t1-release-step.py > "$W/out/tools/t1-release-step.py"
git -C "$REPO" show f888a81b113c0c486ea0949067779443857e644d:scripts/smoke-hosted-beta.sh > "$W/rel/scripts/smoke-hosted-beta.sh"
printf 'PARKIO_DOMAIN=parkio.example\nPARKIO_LOGIN_THROTTLE_HMAC_KEY=k\nOTHER=1\n' > "$W/env"
echo '{"status": "partial", "steps": {"D7": {"status": "ok"}, "final": {"status": "fail", "at": "2026-10-12T13:35:10Z", "stamp": "20261012T133335Z", "failed": ["CSRF checks: seeded account available (values not shown)"]}}}' > "$W/out/state.json"
printf 'exit=1\npasswordOccurrences=0\nt1Final=fail\noverall=partial\nfinishedAt=2026-10-12T13:35:11Z\n' > "$W/out/e-launch/20261012T133335Z/result"
printf 'PASS: gateway health (200)\nPASS: login\nPASS: refresh\n=== smoke summary: pass=12 fail=0 ===\n' > "$W/out/smoke-E-20261012T133335Z.txt"
PW="Diag-Secret-$(openssl rand -hex 6)"
export EAD_OUT="$W/out" EAD_ENV_FILE="$W/env" EAD_REL="$W/rel" PARKIO_REAL_USER_PASSWORD="$PW"
unset PARKIO_REAL_USER_EMAIL
fail=0
ok() { echo "ok   $*"; }
no() { echo "FAIL $*"; fail=1; }
has() { if grep -qF -- "$2" "$W/$1"; then ok "$1: $2"; else no "$1: $2"; fi; }
runit() { local name="$1"; shift; env "$@" bash "$TOOL" > "$W/$name" 2>&1; echo $? > "$W/$name.rc"; }
rc_is() { [ "$(cat "$W/$1.rc")" = "$2" ] && ok "$1 exit $2" || no "$1 exit $(cat "$W/$1.rc"), want $2"; }

runit g1.out; sed "s#$W#<W>#g" "$W/g1.out"; rc_is g1.out 0
has g1.out "T1 final: fail at 2026-10-12T13:35:10Z stamp 20261012T133335Z; failed gates: ['CSRF checks: seeded account available (values not shown)']"
has g1.out "login PASS lines 1; === smoke summary: pass=12 fail=0 ==="
has g1.out 'email, password = env_value("PARKIO_REAL_USER_EMAIL"), os.environ.get("PARKIO_REAL_USER_PASSWORD", "")'
has g1.out "T1 env_value(PARKIO_REAL_USER_EMAIL) on $W/env: absent"
has g1.out "env file lines starting PARKIO_REAL_USER_EMAIL=: 0"
has g1.out "this shell: PARKIO_REAL_USER_EMAIL unset; PARKIO_REAL_USER_PASSWORD set (values not shown)"
has g1.out 'EXPLICIT_EMAIL="${PARKIO_REAL_USER_EMAIL:-}"'; has g1.out 'EMAIL="${EXPLICIT_EMAIL:-<e-mail>}"'
has g1.out "the smoke logs in with its built-in default account"; has g1.out "CAUSE CONFIRMED"
grep -qF -- "$PW" "$W/g1.out" && no "g1 printed the password" || ok "g1: the password is not printed"
grep -q '@' "$W/g1.out" && no "g1 printed an e-mail address" || ok "g1: no e-mail address printed"
[ ! -e "$W/out/tools/__pycache__" ] && ok "g1: no __pycache__ beside T1" || no "g1 wrote a __pycache__"

runit n1.out PARKIO_REAL_USER_EMAIL=someone@example.test; rc_is n1.out 1; has n1.out "differs: this shell sets PARKIO_REAL_USER_EMAIL"
grep -q 'someone@' "$W/n1.out" && no "n1 printed the address" || ok "n1: the address is not printed"
cp "$W/env" "$W/env.bak"; echo 'PARKIO_REAL_USER_EMAIL=someone@example.test' >> "$W/env"
runit n2.out; rc_is n2.out 1; has n2.out "differs: T1 finds an e-mail in the env file (set)"; mv -f "$W/env.bak" "$W/env"
cp "$W/out/smoke-E-20261012T133335Z.txt" "$W/smoke.bak"; sed -i '/^PASS: login$/d' "$W/out/smoke-E-20261012T133335Z.txt"
runit n3.out; rc_is n3.out 1; has n3.out "differs: E's smoke log has no login PASS line"; mv -f "$W/smoke.bak" "$W/out/smoke-E-20261012T133335Z.txt"
runit n4.out -u PARKIO_REAL_USER_PASSWORD; rc_is n4.out 1; has n4.out "differs: the smoke password is not set in this shell"
has n4.out "CAUSE NOT CONFIRMED - send this output"
[ "$fail" -eq 0 ] && echo "SELFTEST PASS" || echo "SELFTEST FAIL"
exit "$fail"
