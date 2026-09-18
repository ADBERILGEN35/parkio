#!/usr/bin/env bash
# Regression gate for PROD-DEPLOY-01A-R9D.
#
# ClamD keeps the signature database resident while FreshClam's default
# TestDatabases validation loads an updated database. A 1536 MiB cgroup killed
# ClamD during that overlap while the image's init process remained running.
# This test pins the production resource, health, privacy, and media dependency
# contract in the fully merged invite-production Compose model.

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

pass=0
fail=0
ok() { echo "PASS: $1"; pass=$((pass + 1)); }
bad() { echo "FAIL: $1" >&2; fail=$((fail + 1)); }

hosted="docker/docker-compose.hosted-beta.yml"
apps="docker/docker-compose.apps.yml"
managed="docker/docker-compose.managed-db.yml"

if awk '
  /^  clamav:/ { in_clamav=1; next }
  in_clamav && /^  [a-z0-9-]+:/ { exit }
  in_clamav && /^[[:space:]]+mem_limit:[[:space:]]+3g([[:space:]]*#.*)?$/ { found=1 }
  END { exit !found }
' "$hosted"; then
  ok "hosted runtime gives ClamAV the 3 GiB minimum"
else
  bad "hosted runtime must set ClamAV mem_limit: 3g"
fi

if awk '
  /^  clamav:/ { in_clamav=1; next }
  in_clamav && /^  [a-z0-9-]+:/ { exit }
  in_clamav && /^[[:space:]]+ports:[[:space:]]+!reset[[:space:]]+\[\]/ { found=1 }
  END { exit !found }
' "$hosted"; then
  ok "ClamAV remains private in the hosted overlay"
else
  bad "hosted runtime must keep ClamAV ports reset"
fi

if python3 - "$apps" "$managed" <<'PY'
from pathlib import Path
import re
import sys

errors = []
for path_arg in sys.argv[1:]:
    path = Path(path_arg)
    text = path.read_text()
    match = re.search(r"^  media-service:\n(?P<body>.*?)(?=^  [a-z0-9-]+:|\Z)", text, re.M | re.S)
    body = match.group("body") if match else ""
    if not re.search(r"^      clamav:\n        condition: service_healthy$", body, re.M):
        errors.append(f"{path}: media-service must depend on healthy ClamAV")
if errors:
    print("\n".join(errors), file=sys.stderr)
    raise SystemExit(1)
PY
then
  ok "media-service stays gated on healthy ClamAV in both application models"
else
  bad "media-service ClamAV dependency contract is incomplete"
fi

compose_model_checked=0
COMPOSE_BIN=""
for candidate in docker docker.exe; do
  if command -v "$candidate" >/dev/null 2>&1 && "$candidate" compose version >/dev/null 2>&1; then
    COMPOSE_BIN="$candidate"
    break
  fi
done

if [ -n "$COMPOSE_BIN" ]; then
  # shellcheck source=lib/deploy-common.sh
  source "$ROOT/scripts/lib/deploy-common.sh"
  PARKIO_DEPLOYMENT_PROFILE=invite-production
  parkio_configure_deployment_profile /dev/null >/dev/null
  model="$(mktemp)"
  trap 'rm -f -- "$model"' EXIT
  export PARKIO_IMAGE_TAG="${PARKIO_IMAGE_TAG:-sha-test}"
  export PARKIO_GIT_SHA="${PARKIO_GIT_SHA:-0000000000000000000000000000000000000000}"
  export PARKIO_IMAGE_CREATED="${PARKIO_IMAGE_CREATED:-1970-01-01T00:00:00Z}"
  export PARKIO_ENVIRONMENT="${PARKIO_ENVIRONMENT:-invite-production}"
  export WSLENV="${WSLENV:+$WSLENV:}PARKIO_IMAGE_TAG:PARKIO_GIT_SHA:PARKIO_IMAGE_CREATED:PARKIO_ENVIRONMENT"
  # shellcheck disable=SC2086
  if "$COMPOSE_BIN" compose --env-file docker/.env.invite-production.example \
      $PARKIO_COMPOSE_FILES config --format json > "$model" 2>/dev/null; then
    compose_model_checked=1
    if python3 - "$model" <<'PY'
import json
import sys

model = json.load(open(sys.argv[1]))
services = model.get("services", {})
clamav = services.get("clamav", {})
media = services.get("media-service", {})
errors = []

limit = int(clamav.get("mem_limit") or 0)
if limit < 3 * 1024 * 1024 * 1024:
    errors.append(f"merged ClamAV memory limit is {limit}, expected at least 3221225472")
if clamav.get("ports"):
    errors.append(f"merged ClamAV unexpectedly publishes ports: {clamav['ports']}")
health_test = (clamav.get("healthcheck") or {}).get("test") or []
if health_test != ["CMD", "clamdcheck.sh"]:
    errors.append(f"merged ClamAV healthcheck is {health_test}, expected ['CMD', 'clamdcheck.sh']")
dependency = (media.get("depends_on") or {}).get("clamav") or {}
if dependency.get("condition") != "service_healthy":
    errors.append(f"merged media ClamAV dependency is {dependency}, expected service_healthy")

if errors:
    print("\n".join(errors), file=sys.stderr)
    raise SystemExit(1)
PY
    then
      ok "merged invite-production ClamAV contract is safe"
    else
      bad "merged invite-production ClamAV contract is unsafe"
    fi
  else
    bad "docker compose could not render the invite-production model"
  fi
else
  echo "SKIP: docker compose unavailable; static assertions completed"
fi

if [ "$compose_model_checked" -eq 0 ] && [ "${PARKIO_REQUIRE_COMPOSE_MODEL:-0}" = "1" ]; then
  bad "merged Compose assertions are required but did not run"
fi

echo
echo "ClamAV runtime regression gates: $pass passed, $fail failed"
[ "$fail" -eq 0 ]
