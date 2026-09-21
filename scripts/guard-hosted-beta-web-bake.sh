#!/usr/bin/env bash
#
# P01F — Hosted-beta web bake + release-pin coherence guard.
#
# Validates:
#   - canonical bake profile key/values
#   - effective services.web.image in the pin (comments do not count)
#   - compose.production.files overlay precedence (pin last)
#
# Usage (repo root):
#   ./scripts/guard-hosted-beta-web-bake.sh
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

fail=0
ok() { echo "PASS: $1"; }
bad() { echo "FAIL: $1" >&2; fail=$((fail + 1)); }

BAKE="docker/web-hosted-beta.release-bake.env"
MUNI_BAKE="docker/web-hosted-beta.municipal-on.bake.env"
PIN="docker/docker-compose.web-release-pin.yml"
FILES="docker/compose.production.files"
AZURE_EX="docker/.env.azure-hosted-beta.example"
NODE="${NODE:-}"
if [ -z "$NODE" ]; then
  if command -v node.exe >/dev/null 2>&1; then
    NODE="$(command -v node.exe)"
  elif command -v node >/dev/null 2>&1; then
    NODE="$(command -v node)"
  else
    echo "ERROR: node is required" >&2
    exit 2
  fi
fi

require_file() {
  if [ -f "$1" ]; then
    ok "present $1"
  else
    bad "missing $1"
  fi
}

require_kv() {
  local file="$1" key="$2" expected="$3"
  local line
  line="$(grep -E "^${key}=" "$file" | head -n1 | tr -d '\r' || true)"
  if [ -z "$line" ]; then
    bad "$file missing $key"
    return
  fi
  local value="${line#*=}"
  if [ "$value" = "$expected" ]; then
    ok "$file $key=$expected"
  else
    bad "$file $key expected=$expected got=$value"
  fi
}

echo "=== P01F hosted-beta web bake / pin guard ==="

require_file "$BAKE"
require_file "$MUNI_BAKE"
require_file "$PIN"
require_file "$FILES"
require_file "$AZURE_EX"

require_kv "$BAKE" VITE_APP_ENV hosted-beta
require_kv "$BAKE" VITE_API_BASE_URL 'https://api.parkio.dev/api/v1'
require_kv "$BAKE" VITE_PUBLIC_EXPLORE_ENABLED true
require_kv "$BAKE" VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED true
require_kv "$BAKE" PARKIO_PUBLIC_EXPLORE_ENABLED true

require_kv "$MUNI_BAKE" VITE_PUBLIC_EXPLORE_ENABLED true
require_kv "$MUNI_BAKE" VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED true
require_kv "$MUNI_BAKE" VITE_API_BASE_URL 'https://api.parkio.dev/api/v1'

# Example env stays fail-closed for municipal (omit/false); live release-bake is on.
require_kv "$AZURE_EX" VITE_PUBLIC_EXPLORE_ENABLED true
require_kv "$AZURE_EX" VITE_API_BASE_URL 'https://api.parkio.dev/api/v1'
require_kv "$AZURE_EX" VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED false

# Effective pin image + overlay precedence (comments cannot satisfy this).
set +e
pin_out="$("$NODE" --input-type=module <<'JS'
import { readFileSync } from 'node:fs';
import {
  assertWebReleasePinContract,
  parseComposeServiceImage,
} from './frontend/apps/web/scripts/lib/web-release-pin.mjs';

const listed = readFileSync('docker/compose.production.files', 'utf8')
  .split(/\r?\n/)
  .map((l) => l.trim())
  .filter((l) => l && !l.startsWith('#'));
const contents = listed.map((path) => ({ path, text: readFileSync(path, 'utf8') }));
const pinText = readFileSync('docker/docker-compose.web-release-pin.yml', 'utf8');
const result = assertWebReleasePinContract({
  pinText,
  composeFilesListText: readFileSync('docker/compose.production.files', 'utf8'),
  composeFileContents: contents,
});
console.log(`pin_image=${result.pinImage}`);
console.log(`pin_digest=${result.pinDigest}`);
console.log(`effective_from=${result.effective.path}`);
JS
)"
pin_status=$?
set -e
if [ "$pin_status" -eq 0 ]; then
  ok "effective web pin image + overlay precedence"
  echo "$pin_out" | sed 's/^/  /'
else
  bad "effective web pin image + overlay precedence"
  echo "$pin_out" >&2
fi

# Optional: docker compose config confirms the same effective image when Docker is available.
if command -v docker >/dev/null 2>&1; then
  pin_image="$(printf '%s\n' "$pin_out" | sed -n 's/^pin_image=//p' | head -n1)"
  if [ -n "$pin_image" ]; then
    args=()
    while IFS= read -r line || [ -n "$line" ]; do
      [[ -z "$line" || "$line" =~ ^[[:space:]]*# ]] && continue
      args+=(-f "$ROOT/$line")
    done < "$FILES"
    set +e
    cfg="$(docker compose --env-file "$AZURE_EX" "${args[@]}" config --format json 2>/dev/null)"
    cfg_status=$?
    set -e
    if [ "$cfg_status" -eq 0 ] && [ -n "$cfg" ]; then
      eff="$("$NODE" -e 'const m=JSON.parse(require("fs").readFileSync(0,"utf8")); process.stdout.write((m.services&&m.services.web&&m.services.web.image)||"")' <<<"$cfg")"
      if [ "$eff" = "$pin_image" ]; then
        ok "docker compose config web.image matches pin"
      else
        bad "docker compose config web.image=$eff expected=$pin_image"
      fi
    else
      echo "WARN: docker compose config skipped (env interpolation incomplete for example file)"
    fi
  fi
fi

if [ "$fail" -ne 0 ]; then
  echo "=== P01F bake/pin guard FAILED ($fail) ===" >&2
  exit 1
fi
echo "=== P01F bake/pin guard OK ==="
exit 0
