#!/usr/bin/env bash
# PRIV-001A — canonical erasure of ONE synthetic principal and read-only observation of its
# erasure request. civo-production only.
#
# SOURCE / OPERATOR TOOLING ONLY. Never runs automatically. It changes no flag, no registration
# setting and no service: the separately authorized live window enables PARKIO_ACCOUNT_ERASURE_ENABLED
# (and stops or starts a participant for a failure case) before this tool is used.
#
# Usage:
#   scripts/acceptance/erase-priv001-synthetic-principal.sh \
#     --environment civo-production --confirm-synthetic-only \
#     --credentials-file PATH        (written by create-priv001-synthetic-principal.sh; mode 600)
#     [--delete]                     send DELETE /api/v1/account once, with password confirmation
#     [--expect COMPLETE|IN_PROGRESS] what the observation must show (default COMPLETE)
#     [--hold-seconds N]             IN_PROGRESS: it must hold this long (default 300)
#     [--timeout-seconds N]          COMPLETE: how long to wait for it (default 900)
#     [--poll-seconds N]             default 15
#     [--window-start ISO-8601]      also count other accounts' erasure requests since then
#     [--evidence-file PATH]         JSON without secrets
#
# The request's status, every participant's ACK and the account state are read with read-only
# SELECTs through the postgres-auth container: after the DELETE the account's session epoch is bumped,
# so the old access token is not relied on for GET /api/v1/account/deletion-status.
#   COMPLETE    PASS when the latest request is COMPLETE, every participant in
#               PARKIO_ACCOUNT_ERASURE_PARTICIPANTS (default: the eight) acked SUCCESS, and the
#               account is ERASED.
#   IN_PROGRESS PASS when the request never reaches COMPLETE during --hold-seconds and at least one
#               participant has no SUCCESS ACK at the end; the missing participants are listed.
# Output and evidence hold ids, statuses, counts and service names only.
#
# shellcheck shell=bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
# shellcheck source=scripts/lib/priv001-synthetic.sh
source "${ROOT}/scripts/lib/priv001-synthetic.sh"

ENVIRONMENT=""
CONFIRM=""
CREDENTIALS_FILE=""
DO_DELETE=0
EXPECT="COMPLETE"
HOLD=300
TIMEOUT=900
POLL=15
WINDOW_START=""
EVIDENCE_FILE=""
PARTICIPANTS="${PARKIO_ACCOUNT_ERASURE_PARTICIPANTS:-user,parking,media,moderation,gamification,notification,analytics,ai-validation}"

usage() {
  sed -n '9,18p' "$0" | sed 's/^# \{0,1\}//'
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --environment) ENVIRONMENT="${2-}"; shift 2 ;;
    --confirm-synthetic-only) CONFIRM="yes"; shift ;;
    --credentials-file) CREDENTIALS_FILE="${2-}"; shift 2 ;;
    --delete) DO_DELETE=1; shift ;;
    --expect) EXPECT="${2-}"; shift 2 ;;
    --hold-seconds) HOLD="${2-}"; shift 2 ;;
    --timeout-seconds) TIMEOUT="${2-}"; shift 2 ;;
    --poll-seconds) POLL="${2-}"; shift 2 ;;
    --window-start) WINDOW_START="${2-}"; shift 2 ;;
    --evidence-file) EVIDENCE_FILE="${2-}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "ERROR: unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
done

export PARKIO_PRIV001_ENVIRONMENT="$ENVIRONMENT"
export PARKIO_PRIV001_CONFIRM_SYNTHETIC_ONLY="$CONFIRM"
if ! parkio_priv001_is_civo; then
  echo "ERROR: erase-priv001-synthetic-principal.sh supports --environment civo-production only." >&2
  exit 2
fi
parkio_priv001_require_opt_in

case "$EXPECT" in COMPLETE|IN_PROGRESS) ;; *) echo "ERROR: --expect must be COMPLETE or IN_PROGRESS." >&2; exit 2 ;; esac
for n in "$HOLD" "$TIMEOUT" "$POLL"; do
  if ! [[ "$n" =~ ^[0-9]+$ ]] || [ "$n" -lt 1 ] || [ "$n" -gt 3600 ]; then
    echo "ERROR: --hold-seconds, --timeout-seconds and --poll-seconds must be integers in [1, 3600]." >&2
    exit 2
  fi
done
if [ -n "$WINDOW_START" ] && ! [[ "$WINDOW_START" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$ ]]; then
  echo "ERROR: --window-start must be UTC like 2026-10-13T08:00:00Z." >&2
  exit 2
fi
if ! [[ "$PARTICIPANTS" =~ ^[a-z-]+(,[a-z-]+)*$ ]]; then
  echo "ERROR: PARKIO_ACCOUNT_ERASURE_PARTICIPANTS must be a comma-separated list of service names." >&2
  exit 2
fi
if [ -z "$CREDENTIALS_FILE" ] || [ ! -f "$CREDENTIALS_FILE" ]; then
  echo "ERROR: --credentials-file is required and must exist." >&2
  exit 2
fi

AUTH_USER_ID=""
EMAIL=""
PASSWORD=""
ACCESS=""
ACCEPTANCE_ID=""
while IFS= read -r line || [ -n "$line" ]; do
  case "$line" in
    acceptance_id=*) ACCEPTANCE_ID="${line#acceptance_id=}" ;;
    auth_user_id=*) AUTH_USER_ID="${line#auth_user_id=}" ;;
    email=*) EMAIL="${line#email=}" ;;
    password=*) PASSWORD="${line#password=}" ;;
    access_token=*) ACCESS="${line#access_token=}" ;;
  esac
done < "$CREDENTIALS_FILE"
parkio_priv001_require_email "$EMAIL"
if ! [[ "$AUTH_USER_ID" =~ ^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$ ]]; then
  echo "ERROR: the credentials file holds no lowercase UUID auth_user_id." >&2
  exit 2
fi

work="$(mktemp -d "${TMPDIR:-/tmp}/priv001a-erase.XXXXXX")"
chmod 700 "$work"
trap 'rm -rf -- "$work"' EXIT

DELETE_CODE=""
DELETE_STATUS=""
if [ "$DO_DELETE" = "1" ]; then
  if [ -z "$PASSWORD" ] || [ -z "$ACCESS" ]; then
    echo "ERROR: --delete needs password and access_token in the credentials file." >&2
    exit 2
  fi
  echo "PRIV-001A: DELETE /api/v1/account for auth_user_id=${AUTH_USER_ID}"
  DELETE_CODE="$(parkio_priv001_civo_http DELETE /api/v1/account \
    "$(printf '{"password":"%s"}' "$PASSWORD")" "$ACCESS" "$work/delete.json")"
  DELETE_STATUS="$(parkio_priv001_json_get "$work/delete.json" "status" 2>/dev/null || true)"
  if [ "$DELETE_CODE" != "200" ] && [ "$DELETE_CODE" != "202" ]; then
    echo "ERROR: DELETE failed http=${DELETE_CODE} (is PARKIO_ACCOUNT_ERASURE_ENABLED on for the window?)" >&2
    grep -E '"code"|"status"|"message"' "$work/delete.json" 2>/dev/null | head -n 5 >&2 || true
    exit 1
  fi
  echo "PRIV-001A: delete=ACCEPTED http=${DELETE_CODE} status=${DELETE_STATUS:-?}"
fi
PASSWORD=""
ACCESS=""

others_sql="NULL"
if [ -n "$WINDOW_START" ]; then
  others_sql="(SELECT count(*)::int FROM erasure_requests WHERE auth_user_id <> '${AUTH_USER_ID}'::uuid AND requested_at >= '${WINDOW_START}'::timestamptz)"
fi
OBSERVE_SQL="SELECT json_build_object(
  'request_status', COALESCE((SELECT status FROM erasure_requests WHERE auth_user_id = '${AUTH_USER_ID}'::uuid ORDER BY requested_at DESC LIMIT 1), 'ABSENT'),
  'request_count', (SELECT count(*)::int FROM erasure_requests WHERE auth_user_id = '${AUTH_USER_ID}'::uuid),
  'auth_state', COALESCE((SELECT status FROM auth_users WHERE id = '${AUTH_USER_ID}'::uuid), 'ABSENT'),
  'acks', COALESCE((SELECT json_object_agg(a.service_name, a.status ORDER BY a.service_name)
                    FROM erasure_service_acks a
                    WHERE a.erasure_request_id = (SELECT id FROM erasure_requests WHERE auth_user_id = '${AUTH_USER_ID}'::uuid
                                                  ORDER BY requested_at DESC LIMIT 1)), '{}'::json),
  'other_requests_since_window_start', ${others_sql}
);"

observe() {  # appends one observation line (JSON) to $work/obs.jsonl and prints a summary line
  local out
  out="$(parkio_priv001_auth_psql -t -A -c "$OBSERVE_SQL")" || { echo "ERROR: read-only status query failed." >&2; return 1; }
  out="$(printf '%s\n' "$out" | grep -E '^\{' | tail -1)"
  [ -n "$out" ] || { echo "ERROR: status query returned no JSON." >&2; return 1; }
  python3 -I - "$out" "$PARTICIPANTS" "$work/obs.jsonl" <<'PY'
import json, sys, time
o = json.loads(sys.argv[1])
parts = sys.argv[2].split(",")
acks = o.get("acks") or {}
o["at"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
o["successCount"] = sum(1 for p in parts if acks.get(p) == "SUCCESS")
o["missing"] = [p for p in parts if acks.get(p) != "SUCCESS"]
o["participants"] = len(parts)
open(sys.argv[3], "a").write(json.dumps(o, sort_keys=True) + "\n")
print(f"  {o['at']} request={o['request_status']} auth={o['auth_state']} success={o['successCount']}/{len(parts)}"
      + (f" missing={','.join(o['missing'])}" if o["missing"] else "")
      + ("" if o.get("other_requests_since_window_start") is None
         else f" other_requests_since_window_start={o['other_requests_since_window_start']}"))
PY
}

echo "PRIV-001A: observing (expect ${EXPECT}; read-only)"
start="$(date +%s)"
verdict="FAIL"
while :; do
  observe
  last="$(tail -1 "$work/obs.jsonl")"
  status="$(printf '%s' "$last" | python3 -I -c 'import json,sys; print(json.load(sys.stdin)["request_status"])')"
  elapsed=$(( $(date +%s) - start ))
  if [ "$EXPECT" = "COMPLETE" ]; then
    done_ok="$(printf '%s' "$last" | python3 -I -c 'import json,sys; o=json.load(sys.stdin); print("yes" if o["request_status"]=="COMPLETE" and not o["missing"] and o["auth_state"]=="ERASED" else "no")')"
    if [ "$done_ok" = "yes" ]; then verdict="PASS"; break; fi
    [ "$elapsed" -ge "$TIMEOUT" ] && break
  else
    if [ "$status" = "COMPLETE" ]; then break; fi
    if [ "$elapsed" -ge "$HOLD" ]; then
      missing="$(printf '%s' "$last" | python3 -I -c 'import json,sys; print(len(json.load(sys.stdin)["missing"]))')"
      if [ "$status" != "ABSENT" ] && [ "$missing" -gt 0 ]; then verdict="PASS"; fi
      break
    fi
  fi
  sleep "$POLL"
done

if [ -n "$EVIDENCE_FILE" ]; then
  python3 -I - "$EVIDENCE_FILE" "$work/obs.jsonl" "$ACCEPTANCE_ID" "$AUTH_USER_ID" "$EXPECT" "$verdict" \
    "$DELETE_CODE" "${DELETE_STATUS:-}" "$HOLD" "$TIMEOUT" <<'PY'
import json, sys
path, obs, acc, uid, expect, verdict, dcode, dstatus, hold, timeout = sys.argv[1:11]
rows = [json.loads(l) for l in open(obs) if l.strip()]
json.dump({"package": "PRIV-001A", "purpose": "synthetic-principal-erasure", "environment": "civo-production",
           "acceptanceId": acc, "authUserId": uid, "expect": expect, "verdict": verdict,
           "delete": {"sent": bool(dcode), "httpStatus": dcode or None, "status": dstatus or None},
           "holdSeconds": int(hold), "timeoutSeconds": int(timeout), "observations": rows,
           "source": "read-only SELECTs on the auth database (erasure_requests, erasure_service_acks, auth_users)",
           "secretsInEvidence": False}, open(path, "w", encoding="utf-8"), indent=2)
PY
  parkio_priv001_secret_scan_text "$EVIDENCE_FILE"
  chmod 600 "$EVIDENCE_FILE" 2>/dev/null || true
fi

echo "PRIV-001A erasure observation: expect=${EXPECT} verdict=${verdict}"
[ "$verdict" = "PASS" ] || exit 1
exit 0
