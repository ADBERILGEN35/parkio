#!/usr/bin/env bash
# T1 step wrapper - the same pre-flight around every remaining release step (owner requests 2026-10-12).
#
# Run as civo on parkio-civo-prod, in the shell that holds the smoke session:
#   bash ~/parkio-release-20261009/tools/t1-step.sh D3      (D3, D4, D5, D6a, D6b, D7 or final)
#
# 1. The installed T1 copy matches its pinned SHA-256 (fe469816...); its Python syntax parses.
# 2. sudo is validated once (T1's NR check and its nr steps call sudo; they then do not prompt).
# 3. For the smoke steps (D3, D5, D6a, D6b, D7, final): the five smoke-session variables are set in this shell (names
#    only; values are never read or printed here).
# 4. Backup freshness (not for final): the newest P5b receipt is PASS and its stamp is less than 2 hours old - the window
#    the owner applies to resuming the release; otherwise STOP and take a fresh P5a/P5b first.
# 5. NR: since when the guard timer has been paused (T1 state nrPausedAt) and that nr-resume is due after D6b.
# 6. Runs only `t1-release-step.py step <step>` (or `final`), output live, stdin closed.
# 7. Lists the step's newest records, T1 status and the NR pause duration again.
# Exit: T1's exit code; 1 when a pre-flight check fails (then T1 is not run and nothing changes); 2 on a usage error.
set -uo pipefail
STEP="${1:-}"
R="${T1S_OUT:-$HOME/parkio-release-20261009}"
T1="${T1S_T1:-$R/tools/t1-release-step.py}"
T1_SHA="${T1S_T1_SHA256:-fe46981663f687b3cdffab716862f5191fe6b1cfda4465636ed8b5ebab9f9fb6}"   # override: self-test only
WINDOW_MIN=120
stop() { echo "STOP: $* - T1 was not run, nothing was changed"; exit 1; }
nr_line() {
  python3 -I - "$R/state.json" <<'PY'
import json, sys
from datetime import datetime, timezone
try:
    s = json.load(open(sys.argv[1], encoding="utf-8"))
except (OSError, ValueError):
    s = {}
steps = s.get("steps") or {}
resumed = (steps.get("nr-resume") or {}).get("status") == "ok"
paused = s.get("nrPausedAt")
if resumed:
    print(f"  NR guard: resumed (nr-resume ok at {(steps.get('nr-resume') or {}).get('at', '?')}); paused from {paused or '?'}")
elif paused and (steps.get("nr-pause") or {}).get("status") == "ok":
    t = datetime.strptime(paused, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=timezone.utc)
    minutes = int((datetime.now(timezone.utc) - t).total_seconds() // 60)
    print(f"  NR guard: timer paused since {paused} ({minutes} min) - nr-resume is due after D6b, before D7")
else:
    print("  NR guard: not paused by T1")
PY
}

case "$STEP" in
  D3|D5|D6a|D6b|D7) SMOKE=1; ARGS=(step "$STEP") ;;
  D4) SMOKE=0; ARGS=(step "$STEP") ;;
  final) SMOKE=1; ARGS=(final) ;;
  *) echo "usage: t1-step.sh D3|D4|D5|D6a|D6b|D7|final"; exit 2 ;;
esac
echo "T1 step wrapper | $STEP | $(date -u +%Y-%m-%dT%H:%M:%SZ)"
echo "== 1. pre-flight"
echo "$T1_SHA  $T1" | sha256sum -c --quiet - >/dev/null 2>&1 || stop "the installed T1 copy does not match ${T1_SHA:0:12}"
python3 -I -c 'import ast, sys; ast.parse(open(sys.argv[1]).read())' "$T1" 2>/dev/null || stop "the T1 copy does not parse"
echo "  T1 copy matches ${T1_SHA:0:12}; syntax ok"
sudo -v || stop "sudo is not available"
echo "  sudo validated"
if [ "$SMOKE" = 1 ]; then
  for v in PARKIO_REAL_USER_PASSWORD PARKIO_GATEWAY_URL PARKIO_SMOKE_EXPECT_DIRECT_BLOCKED PARKIO_DEPLOYMENT_PROFILE PARKIO_ENV_FILE; do
    [ -n "${!v:-}" ] || stop "$v is not set in this shell (run the smoke session block in this shell first)"
  done
  echo "  smoke session variables present (5; values not shown)"
fi
if [ "$STEP" != final ]; then
  fresh="$(python3 -I - "$R/backup" "$WINDOW_MIN" <<'PY'
import glob, json, os, sys
from datetime import datetime, timedelta, timezone
files = sorted(glob.glob(os.path.join(sys.argv[1], "P5b-receipt-*.json")))
r = json.load(open(files[-1], encoding="utf-8")) if files else {}
try:
    t = datetime.strptime(r.get("stamp", ""), "%Y-%m-%dT%H-%M-%SZ").replace(tzinfo=timezone.utc)
    left = int((t + timedelta(minutes=int(sys.argv[2])) - datetime.now(timezone.utc)).total_seconds() // 60)
except ValueError:
    left = -1
print(f"{'ok' if r.get('result') == 'PASS' and left > 0 else 'stale'} {r.get('stamp', 'none')} {r.get('result', '-')} {left}")
PY
)"
  read -r fstate fstamp fresult fleft <<< "$fresh"
  [ "$fstate" = ok ] || stop "no verified backup within 2 hours (newest receipt ${fstamp:-none}, ${fresult:--}, ${fleft:-?} min left); take a fresh P5a/P5b first"
  echo "  verified backup $fstamp ($fresult): $fleft min left in the 2-hour window"
fi
nr_line

echo "== 2. t1-release-step.py ${ARGS[*]}"
python3 -I "$T1" "${ARGS[@]}" </dev/null
rc=$?
echo "  t1 ${ARGS[*]} exit=$rc"

echo "== 3. records and status"
ls -lt "$R"/steps/"$STEP"-* "$R"/smoke-"$STEP"-* 2>/dev/null | head -4
python3 -I "$T1" status </dev/null
nr_line
exit "$rc"
