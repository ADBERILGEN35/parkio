#!/usr/bin/env bash
# B8b: scripts/lib/web_conf_d_guard.py, which refuses a web image that would lose its server config
# under a tmpfs at /etc/nginx/conf.d.
#   Part A: unit tests with a recording fake docker (always).
#   Part B: a real docker daemon and two tiny scratch images. One has
#           /etc/nginx/templates/default.conf.template, as images from #198 on do; the other has only
#           a baked /etc/nginx/conf.d/default.conf, as earlier images do. Part B runs when docker is
#           usable and is REQUIRED when PARKIO_GUARD_TEST_REQUIRE_DOCKER=1. Nothing is started:
#           the guard only creates, copies from and removes containers.
# The wrapper hook (scripts/parkio-prod-compose.sh) is covered by
# scripts/test-guard-web-synthetic-map-deploy.sh.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PY="${PYTHON:-python3}"
GUARD="$ROOT/scripts/lib/web_conf_d_guard.py"
fails=0
pass() { echo "PASS $*"; }
bad() { echo "FAIL $*"; fails=$((fails + 1)); }

echo "=== Part A: unit tests (fake docker) ==="
if "$PY" "$ROOT/scripts/test_web_conf_d_guard.py"; then pass "unit tests"; else bad "unit tests"; fi

echo "=== Part B: real docker fixtures ==="
if docker version >/dev/null 2>&1; then
  TMP="$(mktemp -d)"
  TAG="parkio-web-conf-d-guard-test-$$"
  cleanup() {
    docker rmi -f "$TAG:new" "$TAG:old" >/dev/null 2>&1 || true
    rm -rf "${TMP:?}"
  }
  trap cleanup EXIT
  mkdir -p "$TMP/new/root/etc/nginx/templates" "$TMP/old/root/etc/nginx/conf.d"
  printf 'server { listen 80; }\n' >"$TMP/new/root/etc/nginx/templates/default.conf.template"
  printf 'server { listen 80; }\n' >"$TMP/old/root/etc/nginx/conf.d/default.conf"
  for kind in new old; do
    printf 'FROM scratch\nCOPY root/ /\n' >"$TMP/$kind/Dockerfile"
    docker build -q -t "$TAG:$kind" "$TMP/$kind" >/dev/null
  done
  printf '{"services":{"web":{"image":"x","tmpfs":["/etc/nginx/conf.d:size=1m,mode=755"]}}}' >"$TMP/tmpfs.json"
  printf '{"services":{"web":{"image":"x"}}}' >"$TMP/plain.json"
  containers() { docker ps -aq --filter "ancestor=$TAG:new" --filter "ancestor=$TAG:old" | wc -l; }

  real() { # real EXPECTED_RC EXPECTED_TEXT NAME MODEL IMAGE
    local expected="$1" text="$2" name="$3" rc=0
    "$PY" "$GUARD" --config-json "$4" --image "$5" >"$TMP/out" 2>&1 || rc=$?
    if [ "$rc" -eq "$expected" ] && grep -qF "$text" "$TMP/out"; then
      pass "real docker: $name (exit $rc)"
    else
      bad "real docker: $name: expected exit $expected and '$text', got exit $rc: $(cat "$TMP/out")"
    fi
  }
  real 1 "built before #198" "an image built before #198 under the tmpfs is refused" "$TMP/tmpfs.json" "$TAG:old"
  real 0 "PASS" "an image from #198 on under the tmpfs is allowed" "$TMP/tmpfs.json" "$TAG:new"
  real 0 "SKIP" "a model without the tmpfs is skipped" "$TMP/plain.json" "$TAG:old"
  real 1 "cannot inspect" "an image that is not present is refused" "$TMP/tmpfs.json" "$TAG:absent"
  if [ "$(containers)" -eq 0 ]; then pass "real docker: no inspection container is left"; else bad "real docker: inspection containers left behind"; fi
elif [ "${PARKIO_GUARD_TEST_REQUIRE_DOCKER:-0}" = "1" ]; then
  bad "real docker required (PARKIO_GUARD_TEST_REQUIRE_DOCKER=1) but unavailable"
else
  echo "SKIP real docker unavailable"
fi

if [ "$fails" -ne 0 ]; then
  echo "web conf.d guard tests: $fails FAILED"
  exit 1
fi
echo "web conf.d guard tests: PASS"
