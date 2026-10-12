#!/usr/bin/env bash
# T1 step wrapper - the same pre-flight around every remaining release step (owner requests 2026-10-12).
#
# Run as civo on parkio-civo-prod, in the shell that holds the smoke session:
#   bash ~/parkio-release-20261009/tools/t1-step.sh D3                (D3, D4, D5, D6a, D6b, D7 or final)
#   bash ~/parkio-release-20261009/tools/t1-step.sh D3 --recheck      re-run a step whose change is already in place
#
# 1. The installed T1 copy matches its pinned SHA-256 (T1 v5, 2026-10-12: E records the remaining exposure from the D7 record,
#    requires a passing coverage check of the six built images and the release tags unchanged. v4: D7's scanner cache at /trivy-cache instead of under
#    /root, which the scanner cannot reach as civo; a scan without a report fails as a scanner error; the scan databases
#    are recorded and must be current. v3: the NR check counts the helper's stderr lines; the guard run is verified from its
#    own journal lines, read with sudo); its Python syntax parses.
# 2. sudo is validated once (T1's NR check and its nr steps call sudo; they then do not prompt).
# 3. For the smoke steps (D3, D5, D6a, D6b, D7, final): the five smoke-session variables are set in this shell (names
#    only; values are never read or printed here).
# 4. Backup freshness (not for final): the newest P5b receipt is PASS and its stamp is less than 2 hours old - the window
#    the owner applies to resuming the release; otherwise STOP and take a fresh P5a/P5b first.
# 5. NR: since when the guard timer has been paused (T1 state nrPausedAt) and that nr-resume is due after D6b.
# 6. --recheck (D3, D4, D5, D6a, D6b): before T1 runs, the step's container must be the only running one of its service and
#    its config hash must equal the release model's (the wrapper's own `config --hash`, read-only), so the wrapper's
#    `up -d --no-deps` cannot recreate it; otherwise STOP. The service's Flyway history (rows and last rank) is recorded.
#    After T1: the same container id (not recreated) and the same Flyway history (no migration applied, nothing re-applied).
# 7. Runs only `t1-release-step.py step <step>` (or `final`), output live, stdin closed.
# 8. Lists the step's newest records, T1 status and the NR pause duration again.
# Exit: T1's exit code, or 1 when a --recheck comparison fails; 1 when a pre-flight check fails (then T1 is not run and
# nothing changes); 2 on a usage error.
set -uo pipefail
STEP="${1:-}"
RECHECK="${2:-}"
R="${T1S_OUT:-$HOME/parkio-release-20261009}"
REL="${T1S_REL:-/opt/parkio-release}"                                    # overrides: self-test only
ENVF="${T1S_ENV_FILE:-/opt/parkio/docker/.env.azure-hosted-beta}"
T1="${T1S_T1:-$R/tools/t1-release-step.py}"
T1_SHA="${T1S_T1_SHA256:-39caef1f65b564e37a921b5633dedf1ff86f0e745b9253008c996b11c8f53602}"   # override: self-test only
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
  *) echo "usage: t1-step.sh D3|D4|D5|D6a|D6b|D7|final [--recheck]"; exit 2 ;;
esac
SVC=""; DB=""; EXTRA=()
case "$RECHECK" in
  "") ;;
  --recheck)
    case "$STEP" in
      D3) SVC=gateway-service; DB=gateway; EXTRA=(-f docker/docker-compose.waitlist-ops-inbox.yml) ;;
      D4) SVC=web ;;
      D5) SVC=auth-service; DB=auth ;;
      D6a) SVC=media-service; DB=media ;;
      D6b) SVC=parking-service; DB=parking ;;
      *) echo "usage: --recheck applies to D3, D4, D5, D6a and D6b"; exit 2 ;;
    esac ;;
  *) echo "usage: t1-step.sh D3|D4|D5|D6a|D6b|D7|final [--recheck]"; exit 2 ;;
esac
running_id() {
  local ids
  ids="$(docker ps -q --no-trunc --filter label=com.docker.compose.project=parkio --filter "label=com.docker.compose.service=$SVC" --filter status=running)"
  [ "$(printf '%s\n' "$ids" | sed '/^$/d' | wc -l)" -eq 1 ] && printf '%s\n' "$ids"
}
flyway_state() {
  [ -n "$DB" ] || { echo "-"; return 0; }
  docker exec -i "parkio-postgres-$DB" sh -c \
    'psql -X -q -At -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "select count(*), coalesce(max(installed_rank), 0) from flyway_schema_history"' 2>/dev/null
}
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
if [ -n "$SVC" ]; then
  echo "  re-check of $STEP ($SVC): the change must already be in place"
  CID="$(running_id)" || stop "$SVC must have exactly one running container"
  [ -n "$CID" ] || stop "$SVC must have exactly one running container"
  RUN_HASH="$(docker inspect -f '{{index .Config.Labels "com.docker.compose.config-hash"}}' "$CID" 2>/dev/null)"
  MODEL_HASH="$(cd "$REL" && PARKIO_ENV_FILE="$ENVF" ./scripts/parkio-prod-compose.sh "${EXTRA[@]}" config --hash "$SVC" 2>/dev/null \
    | awk -v s="$SVC" '$1 == s { print $2 }')"
  [ -n "$RUN_HASH" ] && [ "$RUN_HASH" = "$MODEL_HASH" ] \
    || stop "the running $SVC (config hash ${RUN_HASH:0:12}) differs from the release model (${MODEL_HASH:0:12}): a re-run would recreate it, so this is not a re-check"
  FW_BEFORE="$(flyway_state)" || stop "cannot read the $DB Flyway history"
  [ -n "$FW_BEFORE" ] || stop "cannot read the $DB Flyway history"
  echo "  $SVC container ${CID:0:12}, config hash ${RUN_HASH:0:12} = release model: the wrapper will not recreate it"
  [ -n "$DB" ] && echo "  $DB Flyway history before: ${FW_BEFORE%%|*} rows, last rank ${FW_BEFORE##*|}"
fi

echo "== 2. t1-release-step.py ${ARGS[*]}"
python3 -I "$T1" "${ARGS[@]}" </dev/null
rc=$?
echo "  t1 ${ARGS[*]} exit=$rc"
if [ -n "$SVC" ]; then
  CID_AFTER="$(running_id)"; FW_AFTER="$(flyway_state)"
  if [ "$CID_AFTER" = "$CID" ]; then
    echo "  PASS $SVC not recreated (container ${CID:0:12} unchanged)"
  else
    echo "  FAIL $SVC container changed during the re-check (${CID:0:12} -> ${CID_AFTER:0:12})"; rc=1
  fi
  if [ -z "$DB" ]; then
    :
  elif [ "$FW_AFTER" = "$FW_BEFORE" ]; then
    echo "  PASS no migration applied during the re-check ($DB Flyway history unchanged: ${FW_AFTER%%|*} rows, last rank ${FW_AFTER##*|})"
  else
    echo "  FAIL the $DB Flyway history changed during the re-check (${FW_BEFORE} -> ${FW_AFTER})"; rc=1
  fi
fi

echo "== 3. records and status"
ls -lt "$R"/steps/"$STEP"-* "$R"/smoke-"$STEP"-* 2>/dev/null | head -4
python3 -I "$T1" status </dev/null
nr_line
exit "$rc"
