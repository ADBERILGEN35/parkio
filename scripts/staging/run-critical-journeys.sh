#!/usr/bin/env bash
# WP-06.2A.1 — critical application journeys with bounded stages and sanitized evidence.
# Prerequisites: python3, curl, running gateway (+ auth/parking; media optional).
# Does not print access tokens, refresh tokens, or passwords.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
# shellcheck source=lib/safety-guards.sh
source "${SCRIPT_DIR}/lib/safety-guards.sh"
# shellcheck source=lib/json-helper.sh
source "${SCRIPT_DIR}/lib/json-helper.sh"
# shellcheck source=lib/evidence-common.sh
source "${SCRIPT_DIR}/lib/evidence-common.sh"

assert_staging_safety
json_require_python

GATEWAY_URL="${PARKIO_GATEWAY_URL:-http://127.0.0.1:8080}"
API="${GATEWAY_URL}/api/v1"
CLIENT_HEADER="X-Parkio-Client: mobile"
CONNECT_TIMEOUT="${PARKIO_JOURNEY_CONNECT_TIMEOUT:-5}"
REQUEST_TIMEOUT="${PARKIO_JOURNEY_REQUEST_TIMEOUT:-30}"
EMAIL="${PARKIO_REAL_USER_EMAIL:-user@real-e2e.parkio.local}"
PASSWORD="${PARKIO_REAL_USER_PASSWORD:-StrongParkio123}"
LAT="${PARKIO_SYNTHETIC_LAT:-41.008200}"
LNG="${PARKIO_SYNTHETIC_LNG:-28.978400}"

if [ -z "${PARKIO_EVIDENCE_DIR:-}" ]; then
  init_evidence_run "${PARKIO_EVIDENCE_RUN_ID:-}" >/dev/null
fi
JOURNEY_DIR="${PARKIO_EVIDENCE_DIR}/critical-journeys"
mkdir -p "${JOURNEY_DIR}/bodies" "${JOURNEY_DIR}/logs"
BODY="${JOURNEY_DIR}/bodies/last.json"
ACCESS=""
REFRESH=""
SPOT_ID=""
MEDIA_ID=""
MEDIA_READY=no
MANDATORY_FAIL=0
OVERALL_STATUS="PASSED"

write_stage() {
  local stage="$1" status="$2" http_code="${3:-}" reason="${4:-}" mandatory="${5:-true}"
  local started="${6:-$(date -u +%Y-%m-%dT%H:%M:%SZ)}"
  local completed="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  cat > "${JOURNEY_DIR}/${stage}.json" <<EOF
{"evidenceSchemaVersion":"${EVIDENCE_SCHEMA_VERSION}","runId":"${PARKIO_EVIDENCE_RUN_ID}","environmentType":"${PARKIO_ENVIRONMENT_TYPE:-unknown}","repositoryCommit":"$(git -C "${ROOT_DIR}" rev-parse HEAD 2>/dev/null || echo unknown)","stage":"${stage}","status":"${status}","startedAt":"${started}","completedAt":"${completed}","httpStatus":${http_code:-null},"failureReason":$(python3 -c 'import json,sys; print(json.dumps(sys.argv[1]))' "${reason}"),"mandatory":${mandatory},"sourceRestoreDistinction":"${PARKIO_JOURNEY_STORE_MODE:-live_synthetic}","syntheticDataMarker":true}
EOF
  echo "STAGE ${stage}: ${status}${http_code:+ http=${http_code}}${reason:+ (${reason})}"
  if [ "${mandatory}" = "true" ] && { [ "${status}" = "FAILED" ] || [ "${status}" = "BLOCKED" ]; }; then
    MANDATORY_FAIL=1
    OVERALL_STATUS="FAILED"
  fi
}

http_call() {
  local method="$1" url="$2"
  shift 2
  local code
  code="$(curl -sS -o "${BODY}" -w '%{http_code}' --connect-timeout "${CONNECT_TIMEOUT}" --max-time "${REQUEST_TIMEOUT}" \
    -X "${method}" -H "${CLIENT_HEADER}" "$@" "${url}" 2>/dev/null || echo "000")"
  printf '%s' "${code}"
}

wait_ready() {
  local name="$1" url="$2" attempts="${3:-20}"
  local i code
  for i in $(seq 1 "${attempts}"); do
    code="$(curl -sS -o /dev/null -w '%{http_code}' --connect-timeout 3 --max-time 5 "${url}" 2>/dev/null || echo "000")"
    if [ "${code}" = "200" ]; then return 0; fi
    sleep 2
  done
  return 1
}

# --- gateway_readiness ---
started="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
if wait_ready gateway "${GATEWAY_URL}/actuator/health" 15; then
  write_stage gateway_readiness PASSED 200 "" true "${started}"
else
  write_stage gateway_readiness BLOCKED 000 "gateway_unreachable" true "${started}"
  cat > "${JOURNEY_DIR}/summary.json" <<EOF
{"evidenceSchemaVersion":"${EVIDENCE_SCHEMA_VERSION}","runId":"${PARKIO_EVIDENCE_RUN_ID}","status":"FAILED","mandatoryFailures":true}
EOF
  exit 1
fi

# --- auth_readiness (direct optional; gateway path is authoritative for JWKS) ---
started="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
code="$(http_call GET "${API}/auth/.well-known/jwks.json")"
if [ "${code}" = "200" ]; then
  write_stage auth_readiness PASSED "${code}" "" true "${started}"
else
  write_stage auth_readiness FAILED "${code}" "jwks_http_not_200" true "${started}"
fi

# --- auth_contract (JWKS non-empty valid JSON) ---
started="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
if [ "${code}" = "200" ] && json_assert_jwks "${BODY}" > "${JOURNEY_DIR}/logs/jwks-assert.log" 2>&1; then
  write_stage auth_contract PASSED 200 "" true "${started}"
else
  write_stage auth_contract FAILED "${code:-000}" "jwks_invalid_or_empty" true "${started}"
fi

# Seed verified synthetic user + user-service profile (gateway account-status requires profile).
# PARKIO_JOURNEY_AUTH_ONLY_SEED=yes skips user-service profile (not valid for isolated stacks).
started="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
SEED_ARGS=(--target local)
if [ "${PARKIO_JOURNEY_AUTH_ONLY_SEED:-no}" = "yes" ]; then
  SEED_ARGS+=(--auth-only)
fi
if PARKIO_ENV_FILE="${PARKIO_ENV_FILE:-${ROOT_DIR}/docker/.env}" \
  PARKIO_REAL_USER_EMAIL="${EMAIL}" PARKIO_REAL_USER_PASSWORD="${PASSWORD}" \
  "${ROOT_DIR}/scripts/seed-real-e2e.sh" "${SEED_ARGS[@]}" \
  > "${JOURNEY_DIR}/logs/seed.log" 2>&1; then
  write_stage synthetic_seed PASSED "" "" true "${started}"
else
  # seed may fail if postgres container naming differs — try login anyway
  write_stage synthetic_seed FAILED "" "seed_script_failed" false "${started}"
fi

# --- restored_login / login ---
started="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
code="$(http_call POST "${API}/auth/login" -H "Content-Type: application/json" \
  -d "{\"email\":\"${EMAIL}\",\"password\":\"${PASSWORD}\"}")"
if [ "${code}" = "200" ]; then
  ACCESS="$(json_get "${BODY}" accessToken)"
  REFRESH="$(json_get "${BODY}" refreshToken)"
  if [ -n "${ACCESS}" ] && [ "${ACCESS}" != "null" ]; then
    write_stage restored_login PASSED "${code}" "" true "${started}"
  else
    write_stage restored_login FAILED "${code}" "missing_access_token" true "${started}"
    ACCESS=""
  fi
else
  write_stage restored_login FAILED "${code}" "login_rejected" true "${started}"
  ACCESS=""
fi

# Invalid credentials
started="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
code="$(http_call POST "${API}/auth/login" -H "Content-Type: application/json" \
  -d "{\"email\":\"${EMAIL}\",\"password\":\"DefinitelyWrongPassword999!\"}")"
if [ "${code}" = "401" ] || [ "${code}" = "403" ]; then
  write_stage invalid_credentials PASSED "${code}" "" true "${started}"
else
  write_stage invalid_credentials FAILED "${code}" "expected_401_or_403" true "${started}"
fi

# Unauthorized protected access
started="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
code="$(http_call GET "${API}/parking/spots/nearby?lat=${LAT}&lng=${LNG}&radius=1000&limit=5")"
if [ "${code}" = "401" ]; then
  write_stage unauthorized_access PASSED "${code}" "" true "${started}"
else
  write_stage unauthorized_access FAILED "${code}" "expected_401" true "${started}"
fi

if [ -n "${ACCESS}" ]; then
  # token_validation — authenticated profile or nearby
  started="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  code="$(http_call GET "${API}/users/me" -H "Authorization: Bearer ${ACCESS}")"
  if [ "${code}" = "200" ]; then
    write_stage token_validation PASSED "${code}" "" true "${started}"
  else
    # users/me may be unavailable; try nearby as protected surface
    code="$(http_call GET "${API}/parking/spots/nearby?lat=${LAT}&lng=${LNG}&radius=1000&limit=5" \
      -H "Authorization: Bearer ${ACCESS}")"
    if [ "${code}" = "200" ]; then
      write_stage token_validation PASSED "${code}" "via_nearby" true "${started}"
    else
      write_stage token_validation FAILED "${code}" "protected_endpoint_failed" true "${started}"
    fi
  fi

  # refresh_rotation
  started="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  OLD_REFRESH="${REFRESH}"
  if [ -n "${REFRESH}" ] && [ "${REFRESH}" != "null" ]; then
    code="$(http_call POST "${API}/auth/refresh-token" -H "Content-Type: application/json" \
      -d "{\"refreshToken\":\"${REFRESH}\"}")"
    if [ "${code}" = "200" ]; then
      ACCESS="$(json_get "${BODY}" accessToken)"
      REFRESH="$(json_get "${BODY}" refreshToken)"
      write_stage refresh_rotation PASSED "${code}" "" true "${started}"
      # old refresh rejection
      started2="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
      code="$(http_call POST "${API}/auth/refresh-token" -H "Content-Type: application/json" \
        -d "{\"refreshToken\":\"${OLD_REFRESH}\"}")"
      if [ "${code}" = "401" ] || [ "${code}" = "403" ]; then
        write_stage old_refresh_rejection PASSED "${code}" "" true "${started2}"
      else
        write_stage old_refresh_rejection FAILED "${code}" "old_refresh_still_accepted" true "${started2}"
      fi
    else
      write_stage refresh_rotation FAILED "${code}" "refresh_failed" true "${started}"
    fi
  else
    write_stage refresh_rotation FAILED "" "no_refresh_token_in_login" true "${started}"
  fi

  # media_metadata / upload (optional if media down)
  started="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  SYNTH_PNG="${JOURNEY_DIR}/bodies/synthetic.png"
  # Unique 1x1 PNG per run (media-service dedupes identical content as DUPLICATE_MEDIA).
  python3 - <<'PY' "${SYNTH_PNG}"
import base64, os, struct, sys, zlib, time
# Build a minimal unique PNG: vary a tEXt chunk with timestamp/random
signature = b"\x89PNG\r\n\x1a\n"
def chunk(tag, data):
    return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data) & 0xffffffff)
ihdr = chunk(b"IHDR", struct.pack(">IIBBBBB", 1, 1, 8, 2, 0, 0, 0))
uniq = f"wp062a1-{time.time_ns()}-{os.urandom(4).hex()}".encode()
text = chunk(b"tEXt", b"Comment\x00" + uniq)
# raw filter+RGB pixel
raw = b"\x00" + os.urandom(3)
idat = chunk(b"IDAT", zlib.compress(raw))
iend = chunk(b"IEND", b"")
open(sys.argv[1], "wb").write(signature + ihdr + text + idat + iend)
PY
  MEDIA_START_MS="$(python3 -c 'import time; print(int(time.time()*1000))')"
  code="$(curl -sS -o "${BODY}" -w '%{http_code}' --connect-timeout "${CONNECT_TIMEOUT}" --max-time "${REQUEST_TIMEOUT}" \
    -H "${CLIENT_HEADER}" -H "Authorization: Bearer ${ACCESS}" \
    -H "Idempotency-Key: wp062a1-media-$(date +%s)" \
    -F "file=@${SYNTH_PNG};type=image/png" \
    "${API}/media/upload" 2>/dev/null || echo "000")"
  MEDIA_END_MS="$(python3 -c 'import time; print(int(time.time()*1000))')"
  MEDIA_DURATION_MS=$((MEDIA_END_MS - MEDIA_START_MS))
  echo "{\"routeId\":\"media-service\",\"path\":\"/api/v1/media/upload\",\"httpStatus\":${code},\"durationMs\":${MEDIA_DURATION_MS},\"payloadBytes\":$(wc -c < "${SYNTH_PNG}" | tr -d ' '),\"sampleCount\":1,\"baseliningStatus\":\"BASELINING_REQUIRED\"}" \
    > "${JOURNEY_DIR}/gateway-media-timeout-sample.json"
  if [ "${code}" = "201" ] || [ "${code}" = "200" ]; then
    MEDIA_ID="$(json_get "${BODY}" mediaId)"
    write_stage media_object PASSED "${code}" "durationMs=${MEDIA_DURATION_MS}" false "${started}"
    # Wait until media is READY (scan-before-store) before parking create.
    MEDIA_READY=no
    for _i in $(seq 1 30); do
      code="$(http_call GET "${API}/media/${MEDIA_ID}" -H "Authorization: Bearer ${ACCESS}")"
      if [ "${code}" = "200" ]; then
        mstatus="$(json_get "${BODY}" status)"
        if [ "${mstatus}" = "READY" ]; then MEDIA_READY=yes; break; fi
      fi
      sleep 2
    done
    if [ "${MEDIA_READY}" = "yes" ]; then
      write_stage media_metadata PASSED 200 "status=READY" false "${started}"
    else
      write_stage media_metadata FAILED "${code}" "media_not_ready" false "${started}"
    fi
  else
    write_stage media_object EXTERNAL_STAGING_REQUIRED "${code}" "media_upload_unavailable" false "${started}"
    write_stage media_metadata EXTERNAL_STAGING_REQUIRED "${code}" "media_unavailable" false "${started}"
  fi

  # parking create (needs READY mediaId) or skip create and use my-spots
  started="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  if [ -n "${MEDIA_ID}" ] && [ "${MEDIA_ID}" != "null" ] && [ "${MEDIA_READY:-no}" = "yes" ]; then
    code="$(http_call POST "${API}/parking/spots" \
      -H "Authorization: Bearer ${ACCESS}" -H "Content-Type: application/json" \
      -H "Idempotency-Key: wp062a1-spot-$(date +%s)" \
      -d "{\"mediaId\":\"${MEDIA_ID}\",\"latitude\":${LAT},\"longitude\":${LNG},\"addressText\":\"WP062A1 Synthetic Spot\",\"description\":\"synthetic restore fixture\",\"manualLocationEdited\":false,\"suitableVehicleTypes\":[\"SEDAN\"],\"parkingContext\":\"STREET_PARKING\",\"legalStatus\":\"LEGAL\"}")"
    if [ "${code}" = "201" ] || [ "${code}" = "200" ]; then
      SPOT_ID="$(json_get "${BODY}" id)"
      write_stage restored_safe_write PASSED "${code}" "" true "${started}"
    else
      write_stage restored_safe_write FAILED "${code}" "create_spot_failed" true "${started}"
    fi
  else
    write_stage restored_safe_write EXTERNAL_STAGING_REQUIRED "" "media_required_for_create" false "${started}"
  fi

  # restored_parking_read
  started="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  if [ -n "${SPOT_ID}" ] && [ "${SPOT_ID}" != "null" ]; then
    code="$(http_call GET "${API}/parking/spots/${SPOT_ID}" -H "Authorization: Bearer ${ACCESS}")"
    if [ "${code}" = "200" ]; then
      write_stage restored_parking_read PASSED "${code}" "" true "${started}"
    else
      write_stage restored_parking_read FAILED "${code}" "get_spot_failed" true "${started}"
    fi
  else
    code="$(http_call GET "${API}/parking/my-spots" -H "Authorization: Bearer ${ACCESS}")"
    if [ "${code}" = "200" ]; then
      write_stage restored_parking_read PASSED "${code}" "via_my_spots_empty_ok" true "${started}"
    else
      write_stage restored_parking_read FAILED "${code}" "no_spot_and_my_spots_failed" true "${started}"
    fi
  fi

  # restored_nearby_search — endpoint must succeed; candidate visibility follows product contract
  # (PENDING_VALIDATION spots are not searchable until ACTIVE — ParkingApplicationService.searchNearby).
  started="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  code="$(http_call GET "${API}/parking/spots/nearby?lat=${LAT}&lng=${LNG}&radius=2000&limit=20" \
    -H "Authorization: Bearer ${ACCESS}")"
  if [ "${code}" = "200" ]; then
    if [ -n "${SPOT_ID}" ] && [ "${SPOT_ID}" != "null" ]; then
      # Capture spot status from prior get (re-read)
      http_call GET "${API}/parking/spots/${SPOT_ID}" -H "Authorization: Bearer ${ACCESS}" >/dev/null
      spot_status="$(json_get "${BODY}" status)"
      http_call GET "${API}/parking/spots/nearby?lat=${LAT}&lng=${LNG}&radius=2000&limit=20" \
        -H "Authorization: Bearer ${ACCESS}" >/dev/null
      if json_array_contains_id "${BODY}" "${SPOT_ID}" > "${JOURNEY_DIR}/logs/nearby.log" 2>&1; then
        write_stage restored_nearby_search PASSED "${code}" "spot_visible status=${spot_status}" true "${started}"
      elif [ "${spot_status}" = "PENDING_VALIDATION" ] || [ "${spot_status}" = "PENDING_REVIEW" ]; then
        write_stage restored_nearby_search PASSED "${code}" "nearby_ok_spot_not_visible_until_active status=${spot_status}" true "${started}"
      else
        write_stage restored_nearby_search FAILED "${code}" "spot_not_in_nearby status=${spot_status}" true "${started}"
      fi
    else
      write_stage restored_nearby_search PASSED "${code}" "nearby_ok_no_fixture_id" true "${started}"
    fi
  else
    write_stage restored_nearby_search FAILED "${code}" "nearby_failed" true "${started}"
  fi

  # logout
  started="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  if [ -n "${REFRESH}" ] && [ "${REFRESH}" != "null" ]; then
    code="$(http_call POST "${API}/auth/logout" -H "Content-Type: application/json" \
      -d "{\"refreshToken\":\"${REFRESH}\"}")"
  else
    code="$(http_call POST "${API}/auth/logout" -H "Content-Type: application/json" -d '{}')"
  fi
  if [ "${code}" = "204" ] || [ "${code}" = "200" ]; then
    write_stage logout PASSED "${code}" "" true "${started}"
  else
    write_stage logout FAILED "${code}" "logout_failed" true "${started}"
  fi
  # repeated logout (idempotent contract: typically 204/401/200)
  started="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  code="$(http_call POST "${API}/auth/logout" -H "Content-Type: application/json" \
    -d "{\"refreshToken\":\"${REFRESH:-}\"}")"
  if [ "${code}" = "204" ] || [ "${code}" = "200" ] || [ "${code}" = "401" ] || [ "${code}" = "400" ]; then
    write_stage logout_repeat PASSED "${code}" "" true "${started}"
  else
    write_stage logout_repeat FAILED "${code}" "unexpected_repeat_logout" true "${started}"
  fi

  # async_completion — outbox not always queryable via public API
  write_stage async_completion NOT_APPLICABLE "" "no_public_async_probe" false
else
  write_stage token_validation NOT_RUN "" "no_access_token" true
  write_stage refresh_rotation NOT_RUN "" "no_access_token" true
  write_stage restored_parking_read NOT_RUN "" "no_access_token" true
  write_stage restored_nearby_search NOT_RUN "" "no_access_token" true
  write_stage logout NOT_RUN "" "no_access_token" true
fi

# Clear secrets from memory (best-effort)
ACCESS=""
REFRESH=""
OLD_REFRESH=""

if [ "${MANDATORY_FAIL}" -eq 0 ] && [ "${OVERALL_STATUS}" = "PASSED" ]; then
  FINAL="APPLICATION_VERIFICATION_SUCCEEDED"
else
  FINAL="FAILED"
fi

commit="$(git -C "${ROOT_DIR}" rev-parse HEAD 2>/dev/null || echo unknown)"
python3 - <<PY "${JOURNEY_DIR}" "${FINAL}" "${PARKIO_EVIDENCE_RUN_ID}" "${EVIDENCE_SCHEMA_VERSION}" "${commit}" "${PARKIO_ENVIRONMENT_TYPE:-STAGING_LOCAL}"
import json, os, sys, glob
d, final, run_id, ver, commit, env_type = sys.argv[1:7]
stages = {}
started_min = None
completed_max = None
for path in glob.glob(os.path.join(d, "*.json")):
    name = os.path.basename(path)
    if name in ("summary.json", "gateway-media-timeout-sample.json") or name.startswith("summary."):
        continue
    doc = json.load(open(path, encoding="utf-8"))
    if "stage" in doc:
        stages[doc["stage"]] = {
            "status": doc.get("status"),
            "httpStatus": doc.get("httpStatus"),
            "mandatory": doc.get("mandatory"),
            "failureReason": doc.get("failureReason"),
        }
        if doc.get("startedAt"):
            started_min = doc["startedAt"] if started_min is None else min(started_min, doc["startedAt"])
        if doc.get("completedAt"):
            completed_max = doc["completedAt"] if completed_max is None else max(completed_max, doc["completedAt"])
summary = {
  "evidenceSchemaVersion": ver,
  "runId": run_id,
  "repositoryCommit": commit,
  "environmentType": env_type if env_type in (
      "CI_EPHEMERAL", "STAGING_LOCAL", "STAGING_SHARED", "DEVELOPMENT"
  ) else "STAGING_LOCAL",
  "startedAt": started_min or "",
  "completedAt": completed_max or "",
  "status": final,
  "stages": stages,
  "syntheticDataMarker": True,
  "sourceRestoreDistinction": os.environ.get("PARKIO_JOURNEY_STORE_MODE", "live_synthetic"),
}
json.dump(summary, open(os.path.join(d, "summary.json"), "w", encoding="utf-8"), indent=2)
print(final)
PY

# Evidence must not contain tokens
if grep -R -E 'eyJ[A-Za-z0-9_-]+\.|accessToken|refreshToken|Bearer ey' "${JOURNEY_DIR}" --include='*.json' >/dev/null 2>&1; then
  echo "ERROR: secrets detected in journey evidence" >&2
  exit 1
fi

if [ "${FINAL}" = "APPLICATION_VERIFICATION_SUCCEEDED" ]; then
  exit 0
fi
exit 1