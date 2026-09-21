#!/usr/bin/env bash
# Authenticated nearby + media functional acceptance against retained candidate
# images in disposable infrastructure.
#
# Auth path: real auth-service login after DB seed (NOT a test-signed JWT fixture).
# Peers: release-tree auth/user at RELEASE_SHA unless overridden — label as
# candidate-stack acceptance when production peer digests are unavailable.
#
# Requires: COMPOSE_ENV_FILE, COMPOSE_FILES, healthy disposable compose project.
set -euo pipefail

EVIDENCE_DIR="${EVIDENCE_DIR:-../out/http-auth-media}"
mkdir -p "$EVIDENCE_DIR"

EMAIL="${PARKIO_REAL_USER_EMAIL:-user@real-e2e.parkio.local}"
PASSWORD="${PARKIO_REAL_USER_PASSWORD:-SecprivCiUserPass123!}"
CLIENT_HEADER="${PARKIO_CLIENT_HEADER:-X-Parkio-Client: web}"

compose() {
  # shellcheck disable=SC2086
  docker compose --env-file "$COMPOSE_ENV_FILE" $COMPOSE_FILES "$@"
}

container_name() {
  local svc="$1" cid
  cid="$(compose ps -q "$svc")"
  docker inspect -f '{{.Name}}' "$cid" | sed 's#^/##'
}

assert_http() {
  local got="$1" want="$2" label="$3"
  if [ "$got" != "$want" ]; then
    echo "::error::${label}: expected HTTP ${want}, got ${got}"
    exit 1
  fi
  echo "OK ${label}: HTTP ${got}"
}

json_get() {
  python3 - "$1" "$2" <<'PY'
import json, sys
data = json.load(open(sys.argv[1], encoding="utf-8"))
key = sys.argv[2]
val = data.get(key)
print("" if val is None else val)
PY
}

echo "AUTH_PATH=real-auth-service-login-after-db-seed" | tee "${EVIDENCE_DIR}/auth-path.txt"
echo "PEER_ASSUMPTION=${ANCILLARY_ASSUMPTION:-release-tree-peers-unspecified}" \
  | tee "${EVIDENCE_DIR}/peer-assumption.txt"

echo "=== Resolve postgres container names for seed ==="
AUTH_PG="$(container_name postgres-auth)"
USER_PG="$(container_name postgres-user)"
echo "AUTH_PG=${AUTH_PG}" | tee "${EVIDENCE_DIR}/seed-containers.txt"
echo "USER_PG=${USER_PG}" | tee -a "${EVIDENCE_DIR}/seed-containers.txt"

echo "=== Seed synthetic identity via auth DB (seed-real-e2e) ==="
export PARKIO_REAL_USER_EMAIL="$EMAIL"
export PARKIO_REAL_USER_PASSWORD="$PASSWORD"
# Prefer explicit DB names over sourcing the full compose env (JAVA_TOOL_OPTIONS
# and PEM multiline values are unsafe under `set -a; . envfile`).
export PARKIO_ENV_FILE=""
export PARKIO_AUTH_PG_CONTAINER="$AUTH_PG"
export PARKIO_USER_PG_CONTAINER="$USER_PG"
# shellcheck disable=SC1091
if [ -f "$COMPOSE_ENV_FILE" ]; then
  POSTGRES_AUTH_USER="$(grep -E '^POSTGRES_AUTH_USER=' "$COMPOSE_ENV_FILE" | head -1 | cut -d= -f2- || true)"
  POSTGRES_AUTH_DB="$(grep -E '^POSTGRES_AUTH_DB=' "$COMPOSE_ENV_FILE" | head -1 | cut -d= -f2- || true)"
  POSTGRES_USER_USER="$(grep -E '^POSTGRES_USER_USER=' "$COMPOSE_ENV_FILE" | head -1 | cut -d= -f2- || true)"
  POSTGRES_USER_DB="$(grep -E '^POSTGRES_USER_DB=' "$COMPOSE_ENV_FILE" | head -1 | cut -d= -f2- || true)"
  export POSTGRES_AUTH_USER="${POSTGRES_AUTH_USER:-parkio_auth}"
  export POSTGRES_AUTH_DB="${POSTGRES_AUTH_DB:-parkio_auth}"
  export POSTGRES_USER_USER="${POSTGRES_USER_USER:-parkio_user}"
  export POSTGRES_USER_DB="${POSTGRES_USER_DB:-parkio_user}"
fi
scripts/seed-real-e2e.sh --target local --update-passwords \
  | tee "${EVIDENCE_DIR}/seed-real-e2e.log"

echo "=== Anonymous nearby still 401 ==="
status="$(compose exec -T gateway-service \
  curl --path-as-is -sS -o /tmp/nearby-401.json -w '%{http_code}' \
  -H "$CLIENT_HEADER" \
  'http://localhost:8080/api/v1/parking/spots/nearby?lat=41.0&lng=29.0&radius=1000&limit=5' || true)"
compose cp gateway-service:/tmp/nearby-401.json "${EVIDENCE_DIR}/nearby-401.json" >/dev/null 2>&1 || true
assert_http "$status" "401" "nearby anonymous"

echo "=== Auth-service login (real flow) ==="
status="$(compose exec -T gateway-service \
  curl --path-as-is -sS -o /tmp/login.json -w '%{http_code}' \
  -H "$CLIENT_HEADER" -H 'Content-Type: application/json' \
  -d "{\"email\":\"${EMAIL}\",\"password\":\"${PASSWORD}\"}" \
  'http://localhost:8080/api/v1/auth/login' || true)"
compose cp gateway-service:/tmp/login.json "${EVIDENCE_DIR}/login.json" >/dev/null 2>&1 || true
assert_http "$status" "200" "auth login"
ACCESS="$(json_get "${EVIDENCE_DIR}/login.json" accessToken)"
if [ -z "$ACCESS" ]; then
  echo "::error::login response missing accessToken"
  exit 1
fi
# Redact token from retained evidence copy
python3 - <<PY
import json
from pathlib import Path
p = Path("${EVIDENCE_DIR}/login.json")
data = json.loads(p.read_text())
for k in list(data.keys()):
    if "token" in k.lower() or "Token" in k:
        data[k] = "REDACTED"
p.write_text(json.dumps(data, indent=2) + "\n")
print("OK login tokens redacted in evidence")
PY
echo "AUTH_FLOW=real-auth-service-login" | tee "${EVIDENCE_DIR}/auth-flow.txt"

echo "=== Authenticated nearby (expect 200) ==="
# Pass bearer via env into container curl to avoid shell history of full token in logs.
status="$(compose exec -T -e ACCESS_TOKEN="$ACCESS" gateway-service \
  sh -c 'curl --path-as-is -sS -o /tmp/nearby-auth.json -w "%{http_code}" \
    -H "'"$CLIENT_HEADER"'" -H "Authorization: Bearer ${ACCESS_TOKEN}" \
    "http://localhost:8080/api/v1/parking/spots/nearby?lat=41.0&lng=29.0&radius=1000&limit=5"' || true)"
compose cp gateway-service:/tmp/nearby-auth.json "${EVIDENCE_DIR}/nearby-auth.json" >/dev/null 2>&1 || true
assert_http "$status" "200" "nearby authenticated"
python3 - <<PY
import json
from pathlib import Path
data = json.loads(Path("${EVIDENCE_DIR}/nearby-auth.json").read_text())
assert isinstance(data, list), f"expected list, got {type(data)}"
print(f"OK nearby returned list len={len(data)}")
PY

echo "=== Media upload / read / delete (built media image; task-owned MinIO) ==="
SYNTH_PNG="${EVIDENCE_DIR}/synthetic.png"
python3 - <<'PY' "$SYNTH_PNG"
import os, struct, sys, zlib, time
signature = b"\x89PNG\r\n\x1a\n"
def chunk(tag, data):
    return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data) & 0xffffffff)
ihdr = chunk(b"IHDR", struct.pack(">IIBBBBB", 1, 1, 8, 2, 0, 0, 0))
uniq = f"secpriv-media-{time.time_ns()}-{os.urandom(4).hex()}".encode()
text = chunk(b"tEXt", b"Comment\x00" + uniq)
raw = b"\x00" + os.urandom(3)
idat = chunk(b"IDAT", zlib.compress(raw))
iend = chunk(b"IEND", b"")
open(sys.argv[1], "wb").write(signature + ihdr + text + idat + iend)
PY
compose cp "$SYNTH_PNG" gateway-service:/tmp/synthetic.png

IDEMPOTENCY="secpriv-media-$(date -u +%Y%m%dT%H%M%SZ)-$RANDOM"
status="$(compose exec -T -e ACCESS_TOKEN="$ACCESS" gateway-service \
  sh -c 'curl --path-as-is -sS -o /tmp/media-upload.json -w "%{http_code}" \
    -H "'"$CLIENT_HEADER"'" -H "Authorization: Bearer ${ACCESS_TOKEN}" \
    -H "Idempotency-Key: '"$IDEMPOTENCY"'" \
    -F "file=@/tmp/synthetic.png;type=image/png" \
    "http://localhost:8080/api/v1/media/upload"' || true)"
compose cp gateway-service:/tmp/media-upload.json "${EVIDENCE_DIR}/media-upload.json" >/dev/null 2>&1 || true
if [ "$status" != "201" ] && [ "$status" != "200" ]; then
  echo "::error::media upload expected 200/201, got ${status}"
  cat "${EVIDENCE_DIR}/media-upload.json" || true
  exit 1
fi
echo "OK media upload: HTTP ${status}"
MEDIA_ID="$(json_get "${EVIDENCE_DIR}/media-upload.json" mediaId)"
if [ -z "$MEDIA_ID" ]; then
  echo "::error::media upload missing mediaId"
  exit 1
fi
echo "mediaId=${MEDIA_ID}" | tee "${EVIDENCE_DIR}/media-id.txt"

MEDIA_READY=no
for _i in $(seq 1 40); do
  status="$(compose exec -T -e ACCESS_TOKEN="$ACCESS" -e MEDIA_ID="$MEDIA_ID" gateway-service \
    sh -c 'curl --path-as-is -sS -o /tmp/media-get.json -w "%{http_code}" \
      -H "'"$CLIENT_HEADER"'" -H "Authorization: Bearer ${ACCESS_TOKEN}" \
      "http://localhost:8080/api/v1/media/${MEDIA_ID}"' || true)"
  compose cp gateway-service:/tmp/media-get.json "${EVIDENCE_DIR}/media-get.json" >/dev/null 2>&1 || true
  if [ "$status" = "200" ]; then
    mstatus="$(json_get "${EVIDENCE_DIR}/media-get.json" status)"
    echo "media poll status=${mstatus} http=${status}"
    if [ "$mstatus" = "READY" ]; then
      MEDIA_READY=yes
      break
    fi
  fi
  sleep 3
done
if [ "$MEDIA_READY" != "yes" ]; then
  echo "::error::media never reached READY"
  exit 1
fi
echo "OK media read READY"

# Optional access-url (presigned) — owner path
status="$(compose exec -T -e ACCESS_TOKEN="$ACCESS" -e MEDIA_ID="$MEDIA_ID" gateway-service \
  sh -c 'curl --path-as-is -sS -o /tmp/media-access.json -w "%{http_code}" \
    -H "'"$CLIENT_HEADER"'" -H "Authorization: Bearer ${ACCESS_TOKEN}" \
    "http://localhost:8080/api/v1/media/${MEDIA_ID}/access-url"' || true)"
compose cp gateway-service:/tmp/media-access.json "${EVIDENCE_DIR}/media-access.json" >/dev/null 2>&1 || true
# Redact URL query credentials if present
python3 - <<PY
import json, re
from pathlib import Path
p = Path("${EVIDENCE_DIR}/media-access.json")
if not p.exists():
    raise SystemExit(0)
try:
    data = json.loads(p.read_text())
except Exception:
    text = p.read_text()
    p.write_text(re.sub(r'(X-Amz-|Signature|Credential)=[^&\s"]+', r'\1=REDACTED', text))
    raise SystemExit(0)
for k, v in list(data.items()):
    if isinstance(v, str) and ("X-Amz-" in v or "Signature=" in v or v.startswith("http")):
        data[k] = "REDACTED_PRESIGNED_URL"
p.write_text(json.dumps(data, indent=2) + "\n")
print("OK access-url evidence redacted")
PY
if [ "$status" != "200" ]; then
  echo "WARN: access-url returned HTTP ${status} (upload/read already passed)"
else
  echo "OK media access-url: HTTP 200"
fi

status="$(compose exec -T -e ACCESS_TOKEN="$ACCESS" -e MEDIA_ID="$MEDIA_ID" gateway-service \
  sh -c 'curl --path-as-is -sS -o /tmp/media-delete.json -w "%{http_code}" \
    -X DELETE \
    -H "'"$CLIENT_HEADER"'" -H "Authorization: Bearer ${ACCESS_TOKEN}" \
    "http://localhost:8080/api/v1/media/${MEDIA_ID}"' || true)"
compose cp gateway-service:/tmp/media-delete.json "${EVIDENCE_DIR}/media-delete.json" >/dev/null 2>&1 || true
if [ "$status" != "204" ] && [ "$status" != "200" ]; then
  echo "::error::media delete expected 200/204, got ${status}"
  exit 1
fi
echo "OK media delete: HTTP ${status}"

{
  echo "anonymous_nearby=PASS_401"
  echo "auth_login=PASS_REAL_AUTH_SERVICE"
  echo "authenticated_nearby=PASS_200"
  echo "media_upload_read_delete=PASS"
  echo "jwt_fixture=NOT_USED"
  echo "acceptance_class=CANDIDATE_STACK"
} | tee "${EVIDENCE_DIR}/RESULT.txt"

echo "ALL_AUTH_MEDIA_ACCEPTANCE_CHECKS_PASSED"
