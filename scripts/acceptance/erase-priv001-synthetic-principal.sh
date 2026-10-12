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
#     [--delete]                     log in again, then send DELETE /api/v1/account once, with password confirmation
#     [--expect COMPLETE|IN_PROGRESS] what the observation must show (default COMPLETE)
#     [--hold-seconds N]             IN_PROGRESS: it must hold this long (default 300)
#     [--timeout-seconds N]          COMPLETE: how long to wait for it (default 900)
#     [--poll-seconds N]             default 15
#     [--window-start ISO-8601]      also count other accounts' erasure requests since then
#     [--evidence-file PATH]         JSON without secrets
#
# Binding: the credentials file must describe the synthetic principal it names. The account row
# (read-only) must hold the file's allowlisted email, or after erasure its tombstone
# erased-<auth_user_id>@invalid.localhost. --delete also needs the row to be ACTIVE and the fresh
# login's token subject to equal auth_user_id, so the DELETE can only reach that account.
# A create that stopped after registration still leaves a usable file (it is written before the
# register call): without auth_user_id the account is found by its allowlisted email, and with
# --delete a PENDING_VERIFICATION account first gets the same allowlisted verification as create.
# Exit codes: 0 PASS, 1 FAIL, 2 usage or guard, 4 no account holds the file's email (nothing to erase).
#
# The request's status, every participant's ACK and the account state are read with read-only
# SELECTs in a read-only session through the postgres-auth container: after the DELETE the account's
# session epoch is bumped, so no access token is relied on for GET /api/v1/account/deletion-status.
# The expected participants are auth-service's eight (PARKIO_ACCOUNT_ERASURE_PARTICIPANTS is not
# mapped by any compose file); a value in the operator's shell is ignored.
#   COMPLETE    PASS when the latest request is COMPLETE, all eight acked SUCCESS and the account is ERASED.
#   IN_PROGRESS PASS when the latest request is still IN_PROGRESS after --hold-seconds (never COMPLETE
#               or FAILED_RETRYING meanwhile) and at least one participant has no SUCCESS ACK; the
#               missing participants are listed. Participants ACK only SUCCESS, after their erase
#               commits, so a failing or stopped participant sends no ACK and the request stays
#               IN_PROGRESS. A FAILED ACK (no participant sends one today) makes it FAILED_RETRYING,
#               which fails this expectation.
# A transient query failure is retried; three in a row end the observation as FAIL.
# Output and evidence hold ids, statuses, counts and service names only.
#
# shellcheck shell=bash
set -euo pipefail
umask 077

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
PARTICIPANTS="user,parking,media,moderation,gamification,notification,analytics,ai-validation"

usage() {
  sed -n '/^# Usage:/,/--evidence-file/p' "$0" | sed 's/^# \{0,1\}//'
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
if [ -n "${PARKIO_ACCOUNT_ERASURE_PARTICIPANTS:-}" ] && [ "$PARKIO_ACCOUNT_ERASURE_PARTICIPANTS" != "$PARTICIPANTS" ]; then
  echo "NOTE: PARKIO_ACCOUNT_ERASURE_PARTICIPANTS in this shell is ignored; the expected participants are auth-service's eight."
fi
if [ -z "$CREDENTIALS_FILE" ] || [ ! -f "$CREDENTIALS_FILE" ]; then
  echo "ERROR: --credentials-file is required and must exist." >&2
  exit 2
fi

AUTH_USER_ID=""
EMAIL=""
PASSWORD=""
ACCEPTANCE_ID=""
while IFS= read -r line || [ -n "$line" ]; do
  case "$line" in
    acceptance_id=*) ACCEPTANCE_ID="${line#acceptance_id=}" ;;
    auth_user_id=*) AUTH_USER_ID="${line#auth_user_id=}" ;;
    email=*) EMAIL="${line#email=}" ;;
    password=*) PASSWORD="${line#password=}" ;;
  esac
done < "$CREDENTIALS_FILE"
parkio_priv001_require_email "$EMAIL"
if [ -n "$AUTH_USER_ID" ] && ! [[ "$AUTH_USER_ID" =~ ^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$ ]]; then
  echo "ERROR: the credentials file's auth_user_id is not a lowercase UUID." >&2
  exit 2
fi
if [ "$DO_DELETE" = "1" ] && [ -z "$PASSWORD" ]; then
  echo "ERROR: --delete needs the password in the credentials file." >&2
  exit 2
fi

work="$(mktemp -d "${TMPDIR:-/tmp}/priv001a-erase.XXXXXX")"
chmod 700 "$work"
trap 'rm -rf -- "$work"' EXIT

account_row() {  # account_row <SQL condition>: prints "id|email|status" of the one matching row, or nothing
  local out
  out="$(PARKIO_PRIV001_PSQL_READONLY=1 parkio_priv001_auth_psql -t -A -F '|' \
    -c "SELECT id, email, status FROM auth_users WHERE $1;")" \
    || { echo "ERROR: read-only account lookup failed." >&2; return 1; }
  printf '%s\n' "$out" | grep -F '|' | tail -1 || true
}

# Binding (read-only): the row named by auth_user_id (or, when the file has none, by the allowlisted
# email) must be the file's synthetic principal.
if [ -z "$AUTH_USER_ID" ]; then
  row="$(account_row "email = '${EMAIL}'")" || exit 1  # allowlisted: priv001a-[a-z0-9]+@priv001a.parkio.invalid
  if [ -z "$row" ]; then
    echo "PRIV-001A: no auth account holds the credentials file's email (nothing to erase)."
    exit 4
  fi
  AUTH_USER_ID="${row%%|*}"
  echo "PRIV-001A: auth_user_id=${AUTH_USER_ID} found by the allowlisted email (the file had none)"
else
  row="$(account_row "id = '${AUTH_USER_ID}'::uuid")" || exit 1
  if [ -z "$row" ]; then
    echo "ERROR: no auth account has id ${AUTH_USER_ID}." >&2
    exit 1
  fi
fi
row="${row#*|}"
DB_EMAIL="${row%%|*}"
DB_STATUS="${row#*|}"
TOMBSTONE="erased-${AUTH_USER_ID}@invalid.localhost"
if [ "$DB_EMAIL" != "$EMAIL" ] && [ "$DB_EMAIL" != "$TOMBSTONE" ]; then
  echo "ERROR: account ${AUTH_USER_ID} does not hold the credentials file's synthetic email; refusing." >&2
  exit 1
fi
BINDING_TOKEN_SUBJECT=""

DELETE_CODE=""
DELETE_STATUS=""
if [ "$DO_DELETE" = "1" ] && [ "$DB_EMAIL" = "$EMAIL" ] && [ "$DB_STATUS" = "PENDING_VERIFICATION" ]; then
  # A create that stopped after registration: the same allowlisted verification as create, so the
  # account can log in and be erased canonically.
  verified_id="$(parkio_priv001_mark_verified "$EMAIL" 3600)" || exit 1
  if [ "$verified_id" != "$AUTH_USER_ID" ]; then
    echo "ERROR: verification returned another row; refusing." >&2
    exit 1
  fi
  DB_STATUS="ACTIVE"
  echo "PRIV-001A: the account was PENDING_VERIFICATION; applied the allowlisted verification first"
fi
if [ "$DO_DELETE" = "1" ]; then
  if [ "$DB_EMAIL" != "$EMAIL" ] || [ "$DB_STATUS" != "ACTIVE" ]; then
    echo "ERROR: --delete needs account ${AUTH_USER_ID} ACTIVE under the file's email (status=${DB_STATUS:-?}); refusing." >&2
    exit 1
  fi
  # A fresh login: the access token from create may have expired (15-minute TTL).
  code="$(parkio_priv001_civo_http POST /api/v1/auth/login \
    "$(printf '{"email":"%s","password":"%s"}' "$EMAIL" "$PASSWORD")" "" "$work/login.json")"
  if [ "$code" != "200" ]; then
    echo "ERROR: login before DELETE failed http=${code}" >&2
    grep -E '"code"|"status"|"message"' "$work/login.json" 2>/dev/null | head -n 5 >&2 || true
    exit 1
  fi
  ACCESS="$(parkio_priv001_json_get "$work/login.json" "accessToken")"
  rm -f "$work/login.json"
  if [ "$(printf '%s' "$ACCESS" | parkio_priv001_jwt_sub)" != "$AUTH_USER_ID" ]; then
    echo "ERROR: the fresh access token's subject is not ${AUTH_USER_ID}; refusing." >&2
    exit 1
  fi
  BINDING_TOKEN_SUBJECT="match"
  echo "PRIV-001A: DELETE /api/v1/account for auth_user_id=${AUTH_USER_ID}"
  DELETE_CODE="$(parkio_priv001_civo_http DELETE /api/v1/account \
    "$(printf '{"password":"%s"}' "$PASSWORD")" "$ACCESS" "$work/delete.json")"
  ACCESS=""
  DELETE_STATUS="$(parkio_priv001_json_get "$work/delete.json" "status" 2>/dev/null || true)"
  if [ "$DELETE_CODE" != "200" ] && [ "$DELETE_CODE" != "202" ]; then
    case "$DELETE_CODE" in
      401) echo "ERROR: DELETE failed http=401: the fresh access token was rejected." >&2 ;;
      403) echo "ERROR: DELETE failed http=403: ACCOUNT_ERASURE_DISABLED means PARKIO_ACCOUNT_ERASURE_ENABLED is off; ACCOUNT_NOT_ACTIVE comes from the gateway." >&2 ;;
      *) echo "ERROR: DELETE failed http=${DELETE_CODE}" >&2 ;;
    esac
    grep -E '"code"|"status"|"message"' "$work/delete.json" 2>/dev/null | head -n 5 >&2 || true
    exit 1
  fi
  echo "PRIV-001A: delete=ACCEPTED http=${DELETE_CODE} status=${DELETE_STATUS:-?}"
fi
PASSWORD=""

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
  out="$(PARKIO_PRIV001_PSQL_READONLY=1 parkio_priv001_auth_psql -t -A -c "$OBSERVE_SQL")" \
    || { echo "WARNING: read-only status query failed." >&2; return 1; }
  out="$(printf '%s\n' "$out" | grep -E '^\{' | tail -1 || true)"
  [ -n "$out" ] || { echo "WARNING: status query returned no JSON." >&2; return 1; }
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

field() {  # field <name>: one field of the last observation
  tail -1 "$work/obs.jsonl" | python3 -I -c 'import json,sys; o=json.load(sys.stdin); v=o[sys.argv[1]]; print(len(v) if isinstance(v, list) else v)' "$1"
}

echo "PRIV-001A: observing (expect ${EXPECT}; read-only)"
start="$(date +%s)"
verdict="FAIL"
status="UNKNOWN"
reason=""
failures=0
while :; do
  if ! observe; then
    failures=$((failures + 1))
    if [ "$failures" -ge 3 ]; then reason="three consecutive status queries failed"; break; fi
    sleep "$POLL"
    continue
  fi
  failures=0
  status="$(field request_status)"
  elapsed=$(( $(date +%s) - start ))
  if [ "$status" = "FAILED_RETRYING" ]; then
    reason="the request is FAILED_RETRYING (a participant sent a FAILED ACK)"
    break
  fi
  if [ "$EXPECT" = "COMPLETE" ]; then
    if [ "$status" = "COMPLETE" ] && [ "$(field missing)" = "0" ] && [ "$(field auth_state)" = "ERASED" ]; then
      verdict="PASS"; break
    fi
    if [ "$elapsed" -ge "$TIMEOUT" ]; then reason="not COMPLETE with 8/8 SUCCESS and ERASED within ${TIMEOUT}s"; break; fi
  else
    if [ "$status" = "COMPLETE" ]; then reason="the request reached COMPLETE during the hold"; break; fi
    if [ "$elapsed" -ge "$HOLD" ]; then
      if [ "$status" = "IN_PROGRESS" ] && [ "$(field missing)" -gt 0 ]; then
        verdict="PASS"
      else
        reason="after ${HOLD}s the request is ${status} with $(field missing) participant(s) missing"
      fi
      break
    fi
  fi
  sleep "$POLL"
done

if [ -n "$EVIDENCE_FILE" ]; then
  [ -f "$work/obs.jsonl" ] || : > "$work/obs.jsonl"
  python3 -I - "$EVIDENCE_FILE" "$work/obs.jsonl" "$ACCEPTANCE_ID" "$AUTH_USER_ID" "$EXPECT" "$verdict" \
    "$DELETE_CODE" "${DELETE_STATUS:-}" "$HOLD" "$TIMEOUT" "$status" "$reason" "$PARTICIPANTS" \
    "$BINDING_TOKEN_SUBJECT" <<'PY'
import json, sys
(path, obs, acc, uid, expect, verdict, dcode, dstatus, hold, timeout, status, reason, parts,
 subject) = sys.argv[1:15]
rows = [json.loads(l) for l in open(obs) if l.strip()]
json.dump({"package": "PRIV-001A", "purpose": "synthetic-principal-erasure", "environment": "civo-production",
           "acceptanceId": acc, "authUserId": uid, "expect": expect, "verdict": verdict,
           "finalRequestStatus": status, "failReason": reason or None,
           "expectedParticipants": parts.split(","),
           "binding": {"accountEmailIsSyntheticOrTombstone": True,
                       "tokenSubjectMatches": True if subject == "match" else None},
           "delete": {"sent": bool(dcode), "httpStatus": dcode or None, "status": dstatus or None},
           "holdSeconds": int(hold), "timeoutSeconds": int(timeout), "observations": rows,
           "source": "read-only SELECTs in a read-only session on the auth database "
                     "(erasure_requests, erasure_service_acks, auth_users)",
           "secretsInEvidence": False}, open(path, "w", encoding="utf-8"), indent=2)
PY
  parkio_priv001_secret_scan_text "$EVIDENCE_FILE"
  chmod 600 "$EVIDENCE_FILE" 2>/dev/null || true
fi

[ -z "$reason" ] || echo "PRIV-001A: ${reason}" >&2
echo "PRIV-001A erasure observation: expect=${EXPECT} status=${status} verdict=${verdict}"
[ "$verdict" = "PASS" ] || exit 1
exit 0
