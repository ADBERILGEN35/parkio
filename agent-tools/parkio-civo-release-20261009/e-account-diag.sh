#!/usr/bin/env bash
# E retry diagnostic (owner request 2026-10-12): why E's "CSRF checks: seeded account available (values not shown)" failed.
# Read-only. Run as civo in the smoke-session shell:
#   bash ~/parkio-release-20261009/tools/e-account-diag.sh
# Prints names, set / empty / absent, counts and code lines with any e-mail address masked - never a value or a password.
# A. The failed E run: T1's final status and failed gates; the E launch result; the E smoke log's login line and summary.
# B. How the installed T1 resolves the account for E2 (its csrf_checks line, and its env_value(PARKIO_REAL_USER_EMAIL) on the
#    production env file), the env file's *REAL_USER* / *SMOKE* key names, and the shell's two variables.
# C. How the smoke resolves it (scripts/smoke-hosted-beta.sh at the release checkout, the two lines).
# D. Verdict: CAUSE CONFIRMED when T1 finds no e-mail in the env file, the shell sets none (so the smoke used its default),
#    the password is set and E's smoke logged in; otherwise CAUSE NOT CONFIRMED with the fact that differs.
set -uo pipefail
R="${EAD_OUT:-$HOME/parkio-release-20261009}"                       # overrides: self-test only
ENVF="${EAD_ENV_FILE:-/opt/parkio/docker/.env.azure-hosted-beta}"
REL="${EAD_REL:-/opt/parkio-release}"
T1="$R/tools/t1-release-step.py"
mask() { sed -E 's/[A-Za-z0-9._%+][A-Za-z0-9._%+-]*@[A-Za-z0-9.-]+\.[A-Za-z]{2,}/<e-mail>/g'; }
facts=0
echo "E account diagnostic | $(date -u +%Y-%m-%dT%H:%M:%SZ) | read-only"

echo "== A. the failed E run"
python3 -I - "$R/state.json" <<'PY'
import json, sys
try:
    s = json.load(open(sys.argv[1], encoding="utf-8"))
except (OSError, ValueError):
    s = {}
f = (s.get("steps") or {}).get("final") or {}
print(f"  T1 final: {f.get('status', '-')} at {f.get('at', '-')} stamp {f.get('stamp', '-')}; failed gates: {f.get('failed') or []}")
PY
D="$(ls -1d "$R"/e-launch/20*Z 2>/dev/null | tail -1)"
echo "  E launch $(basename "${D:-none}"): $(tr '\n' ' ' < "${D:-/nonexistent}/result" 2>/dev/null | sed 's/finishedAt=[^ ]* //')"
SL="$(ls -1t "$R"/smoke-E-*.txt 2>/dev/null | head -1)"
if [ -n "$SL" ]; then
  login_lines="$(grep -c -x 'PASS: login' "$SL")"
  echo "  E smoke log $(basename "$SL"): login PASS lines $login_lines; $(grep -E '=== smoke summary' "$SL" | tail -1)"
else
  login_lines=0; echo "  E smoke log: none"
fi

echo "== B. the installed T1 (E2 CSRF checks)"
echo "  T1 sha256 $(sha256sum "$T1" 2>/dev/null | cut -c1-16)"
grep -n 'email, password = ' "$T1" 2>/dev/null | mask | sed 's/^/  T1 line /'
t1_value="$(T1_ENV_FILE="$ENVF" python3 -I -B - "$T1" <<'PY'
import importlib.util, sys
sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location("t1", sys.argv[1]); t1 = importlib.util.module_from_spec(spec); spec.loader.exec_module(t1)
v = t1.env_value("PARKIO_REAL_USER_EMAIL")
print("absent" if v is None else ("empty" if v == "" else "set"))
PY
)"
echo "  T1 env_value(PARKIO_REAL_USER_EMAIL) on $ENVF: ${t1_value:-error}"
echo "  env file lines starting PARKIO_REAL_USER_EMAIL=: $(grep -c '^PARKIO_REAL_USER_EMAIL=' "$ENVF" 2>/dev/null)"
echo "  env file key names with REAL_USER or SMOKE: $(grep -o -E '^[A-Za-z0-9_]*(REAL_USER|SMOKE)[A-Za-z0-9_]*=' "$ENVF" 2>/dev/null | tr -d '=' | sort -u | tr '\n' ' ')"
shell_email="$([ -n "${PARKIO_REAL_USER_EMAIL:-}" ] && echo set || echo unset)"
shell_pw="$([ -n "${PARKIO_REAL_USER_PASSWORD:-}" ] && echo set || echo unset)"
echo "  this shell: PARKIO_REAL_USER_EMAIL $shell_email; PARKIO_REAL_USER_PASSWORD $shell_pw (values not shown)"

echo "== C. the smoke (scripts/smoke-hosted-beta.sh at the release checkout)"
grep -n -E '^EXPLICIT_EMAIL=|^  EMAIL="\$\{EXPLICIT_EMAIL' "$REL/scripts/smoke-hosted-beta.sh" 2>/dev/null | mask | sed 's/^/  smoke line /'
echo "  -> the smoke logs in with $([ "$shell_email" = set ] && echo "this shell's PARKIO_REAL_USER_EMAIL" || echo "its built-in default account")"

echo "== D. verdict"
[ "$t1_value" = absent ] || [ "$t1_value" = empty ] || { echo "  differs: T1 finds an e-mail in the env file ($t1_value)"; facts=1; }
[ "$shell_email" = unset ] || { echo "  differs: this shell sets PARKIO_REAL_USER_EMAIL"; facts=1; }
[ "$shell_pw" = set ] || { echo "  differs: the smoke password is not set in this shell"; facts=1; }
[ "${login_lines:-0}" -ge 1 ] || { echo "  differs: E's smoke log has no login PASS line"; facts=1; }
if [ "$facts" = 0 ]; then
  echo "CAUSE CONFIRMED: E2 takes the account e-mail only from the production env file, which has none; the smoke takes"
  echo "PARKIO_REAL_USER_EMAIL from the environment or its built-in default and logged in. The password precondition holds."
  echo "Fix: T1 v7 resolves the account exactly like the smoke. No account, password or deployment change."
  exit 0
fi
echo "CAUSE NOT CONFIRMED - send this output"
exit 1
