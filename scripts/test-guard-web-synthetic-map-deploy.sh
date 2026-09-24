#!/usr/bin/env bash
# Regression tests for the production web map deploy guard (audit F-05):
#   scripts/guard-web-synthetic-map-deploy.sh, scripts/lib/web_bundle_map_config.py,
#   scripts/lib/web-map-guard.sh and its callers scripts/parkio-prod-compose.sh and
#   parkio_compose_up (scripts/lib/deploy-common.sh).
#
# Part A drives the real scripts with a fake `docker` on PATH (controlled image
# fixtures; every compose mutation is logged so "nothing started" is asserted).
# Part B builds tiny real images and runs the guard against the real daemon; it
# runs when docker is usable and is REQUIRED when PARKIO_GUARD_TEST_REQUIRE_DOCKER=1.
# No registry, provider or production system is contacted.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TMP="$(mktemp -d "${TMPDIR:-/tmp}/web-map-guard-test.XXXXXX")"
REAL_PATH="$PATH"
TESTS=0
FAILED=0
trap 'rm -rf "$TMP"' EXIT

GOOD_KEY="nonsynthetic-fixture-4c1d9e"
BAD_MANIFEST="sha256:8d9bfca43d577afd62d20f7ffe3fcb2566fbf3bce9dbd758368562aec641487d"
BAD_CONFIG="sha256:985fd8a7684a63ea21cf10cbb36f8f2fc092b7d3f13ccbc8c18311380945f68c"
REPO="ghcr.io/adberilgen35/parkio/web"

pass() { TESTS=$((TESTS + 1)); echo "PASS $1"; }
bad() { TESTS=$((TESTS + 1)); FAILED=$((FAILED + 1)); echo "FAIL $1"; sed 's/^/     | /' "$TMP/out" "$TMP/err" 2>/dev/null | tail -n 20 || true; }

# ---------------------------------------------------------------------------
# Fake docker
# ---------------------------------------------------------------------------
FAKE="$TMP/fake"
mkdir -p "$FAKE/bin" "$FAKE/images" "$FAKE/roots"
cat >"$FAKE/bin/docker" <<'SH'
#!/usr/bin/env bash
# Minimal docker stand-in for guard tests. State lives under $FAKE_DOCKER_DIR.
d="$FAKE_DOCKER_DIR"
echo "$*" >>"$d/calls.log"
key() { printf '%s' "$1" | sha256sum | cut -c1-16; }
case "$1" in
  version) echo "${FAKE_DAEMON_PLATFORM:-linux/amd64}" ;;
  image)
    [ "$2" = "inspect" ] || exit 1
    f="$d/images/$(key "$3").json"
    [ -f "$f" ] || { echo "Error: No such image: $3" >&2; exit 1; }
    cat "$f"
    ;;
  create)
    id="${@: -1}"
    root="$d/roots/${id#sha256:}"
    [ -d "$root" ] || { echo "Error: No such image: $id" >&2; exit 1; }
    echo "cid-${id#sha256:}"
    ;;
  cp)
    src="${2%%:*}"; path="${2#*:}"
    root="$d/roots/${src#cid-}"
    [ -d "$root$path" ] || { echo "Error: Could not find the file $path" >&2; exit 1; }
    tar -C "$root$(dirname "$path")" -cf - "$(basename "$path")"
    ;;
  rm) ;;
  compose)
    for a in "$@"; do
      if [ "$a" = "config" ]; then
        echo "$*" >>"$d/config.log"
        [ -f "$d/compose-fail" ] && exit 1
        cat "$d/compose-config.json"
        exit 0
      fi
    done
    echo "MUTATION $*" >>"$d/mutations.log"
    ;;
  *) exit 1 ;;
esac
SH
chmod +x "$FAKE/bin/docker"
export FAKE_DOCKER_DIR="$FAKE"

fake_reset() {
  rm -f "$FAKE"/calls.log "$FAKE"/config.log "$FAKE"/mutations.log "$FAKE"/compose-fail
  unset FAKE_DAEMON_PLATFORM || true
}

# bundle KIND ROOT: write a web root under ROOT/usr/share/nginx/html
bundle() {
  local kind="$1" html="$2/usr/share/nginx/html"
  mkdir -p "$html/assets"
  echo '<!doctype html><div id="root"></div>' >"$html/index.html"
  local env_pre='const e={BASE_URL:"/",DEV:!1,MODE:"production",PROD:!0,SSR:!1,VITE_API_BASE_URL:"https://api.parkio.dev/api/v1",VITE_APP_ENV:"hosted-beta",'
  case "$kind" in
    good) echo "${env_pre}VITE_MAPTILER_KEY:\"${GOOD_KEY}\",VITE_MAPTILER_STYLE:\"streets-v2\"};export{e};" >"$html/assets/index-a1.js" ;;
    synthetic) echo "${env_pre}VITE_MAPTILER_KEY:\"ci-web-build-security-synthetic\"};" >"$html/assets/index-a1.js" ;;
    synthetic-suffixed) echo "${env_pre}VITE_MAPTILER_KEY:\"ci-web-build-security-synthetic-run42\"};" >"$html/assets/index-a1.js" ;;
    sentinel) echo "${env_pre}VITE_MAPTILER_KEY:\"SECRET_SENTINEL_MAPTILER_PUBLIC_KEY\"};" >"$html/assets/index-a1.js" ;;
    empty) echo "${env_pre}VITE_MAPTILER_KEY:\"\"};" >"$html/assets/index-a1.js" ;;
    missing-key) echo "${env_pre}VITE_MAPTILER_STYLE:\"streets-v2\"};" >"$html/assets/index-a1.js" ;;
    no-env) echo 'console.log("no inlined env here");' >"$html/assets/index-a1.js" ;;
    no-js) rm -rf "$html/assets" ;;
    conflicting)
      echo "${env_pre}VITE_MAPTILER_KEY:\"${GOOD_KEY}\"};" >"$html/assets/index-a1.js"
      echo "${env_pre}VITE_MAPTILER_KEY:\"another-nonsynthetic-7f\"};" >"$html/assets/chunk-b2.js"
      ;;
    raw-synthetic)
      echo "${env_pre}VITE_MAPTILER_KEY:\"${GOOD_KEY}\"};" >"$html/assets/index-a1.js"
      echo 'const k="ci-web-build-security-synthetic";' >"$html/assets/map-c3.js"
      ;;
    no-html) rm -rf "$2/usr/share/nginx/html"; mkdir -p "$2/srv" ;;
    *) echo "unknown bundle kind $kind" >&2; exit 2 ;;
  esac
}

# image REF CONFIG_ID PLATFORM REPO_DIGESTS(space-separated) BUNDLE_KIND
fake_image() {
  local ref="$1" id="$2" platform="$3" digests="$4" kind="$5" k
  k="$(printf '%s' "$ref" | sha256sum | cut -c1-16)"
  python3 - "$id" "$platform" "$digests" >"$FAKE/images/$k.json" <<'PY'
import json, sys
id_, platform, digests = sys.argv[1:4]
os_, arch = platform.split("/", 1)
print(json.dumps([{"Id": id_, "Os": os_, "Architecture": arch, "RepoDigests": digests.split()}]))
PY
  rm -rf "$FAKE/roots/${id#sha256:}"
  mkdir -p "$FAKE/roots/${id#sha256:}"
  bundle "$kind" "$FAKE/roots/${id#sha256:}"
}

cfg_id() { printf 'sha256:%064d' "$1"; }
dig() { printf 'sha256:%s' "$(printf '%s' "$1" | sha256sum | cut -c1-64)"; }

compose_model() { # compose_model IMAGE|-  (- = no web service)
  if [ "$1" = "-" ]; then
    echo '{"services":{"gateway-service":{"image":"gw:1"}}}' >"$FAKE/compose-config.json"
  else
    printf '{"services":{"gateway-service":{"image":"gw:1"},"web":{"image":"%s"}}}\n' "$1" >"$FAKE/compose-config.json"
  fi
}

GUARD="$ROOT/scripts/guard-web-synthetic-map-deploy.sh"

guard() { # guard EXPECTED_RC NAME ARGS...
  local expected="$1" name="$2" rc
  shift 2
  set +e
  PATH="$FAKE/bin:$REAL_PATH" bash "$GUARD" "$@" >"$TMP/out" 2>"$TMP/err"
  rc=$?
  set -e
  if [ "$rc" -eq "$expected" ]; then pass "$name (exit $rc)"; else bad "$name: expected $expected, got $rc"; fi
}

assert_no_key_leak() {
  if grep -q "$GOOD_KEY" "$TMP/out" "$TMP/err"; then bad "$1: key value leaked to output"; else pass "$1: key value not printed"; fi
}

echo "=== Part A: guard, classifier and callers (fake docker) ==="

# Fixtures
GOOD_ID="$(cfg_id 1)"; GOOD_DIG="$(dig good)"
fake_image "$REPO@$GOOD_DIG" "$GOOD_ID" linux/amd64 "$REPO@$GOOD_DIG" good
fake_image "$REPO:good-tag" "$GOOD_ID" linux/amd64 "$REPO@$GOOD_DIG" good
fake_image "$REPO:bad-config-alias" "$BAD_CONFIG" linux/amd64 "" synthetic
fake_image "$REPO:bad-manifest-alias" "$(cfg_id 2)" linux/amd64 "$REPO@$BAD_MANIFEST" good
fake_image "$REPO@$(dig mismatch)" "$(cfg_id 3)" linux/amd64 "$REPO@$(dig other)" good
fake_image "$REPO:arm" "$(cfg_id 4)" linux/arm64 "" good
n=10
for kind in synthetic synthetic-suffixed sentinel empty missing-key no-env no-js conflicting raw-synthetic no-html; do
  n=$((n + 1))
  fake_image "$REPO:$kind" "$(cfg_id "$n")" linux/amd64 "" "$kind"
done
fake_image "local/parkio-web:sha-abc123" "$(cfg_id 30)" linux/amd64 "" good

# --- blocked identities -------------------------------------------------------
fake_reset
guard 1 "known-bad manifest requested by digest is blocked" --image "$REPO@$BAD_MANIFEST"
if [ -f "$FAKE/calls.log" ]; then bad "known-bad digest decided before any docker call"; else pass "known-bad digest decided before any docker call"; fi
guard 1 "tag resolving to the known-bad config ID is blocked" --image "$REPO:bad-config-alias"
guard 1 "tag whose repo digest is the known-bad manifest is blocked" --image "$REPO:bad-manifest-alias"
guard 1 "digest ref whose local image lacks that digest is blocked" --image "$REPO@$(dig mismatch)"

# --- platform ----------------------------------------------------------------
guard 1 "image platform different from daemon platform is blocked" --image "$REPO:arm"
FAKE_DAEMON_PLATFORM=linux/arm64 guard 0 "matching arm64 daemon accepts arm64 image" --image "$REPO:arm"
unset FAKE_DAEMON_PLATFORM
guard 1 "explicit --expected-platform mismatch is blocked" --image "$REPO:good-tag" --expected-platform linux/arm64

# --- missing / unreadable evidence --------------------------------------------
guard 1 "image absent locally fails closed (never pulls)" --image "$REPO:not-pulled"
if grep -q '^pull' "$FAKE/calls.log"; then bad "guard never pulls"; else pass "guard never pulls"; fi
guard 2 "no image and no config is a usage error"
echo 'not json' >"$TMP/broken.json"
guard 1 "unreadable compose config fails closed" --compose-config-json "$TMP/broken.json"
guard 1 "missing env file fails closed" --image "$REPO:good-tag" --env-file "$TMP/nope.env"

# --- baked configuration ------------------------------------------------------
for kind in synthetic synthetic-suffixed sentinel empty missing-key no-env no-js conflicting raw-synthetic no-html; do
  guard 1 "bundle '$kind' is blocked" --image "$REPO:$kind"
done
guard 0 "valid non-synthetic image by digest passes" --image "$REPO@$GOOD_DIG" --evidence-out "$TMP/evidence.json"
assert_no_key_leak "digest pass"
if grep -q "fingerprint=$(printf '%s' "$GOOD_KEY" | sha256sum | cut -c1-12)" "$TMP/out"; then pass "pass line carries the key fingerprint"; else bad "pass line carries the key fingerprint"; fi
if python3 -c 'import json,sys; e=json.load(open(sys.argv[1])); sys.exit(0 if e["configId"]==sys.argv[2] and e["result"]=="PASS" and sys.argv[3] not in open(sys.argv[1]).read() else 1)' "$TMP/evidence.json" "$GOOD_ID" "$GOOD_KEY"; then
  pass "evidence JSON records config ID and omits the key"
else
  bad "evidence JSON records config ID and omits the key"
fi
GOOD_FP="$(printf '%s' "$GOOD_KEY" | sha256sum | cut -c1-12)"
guard 0 "expected fingerprint of the authorized key passes" --image "$REPO@$GOOD_DIG" --expected-map-key-fingerprint "$GOOD_FP"
grep -q 'fingerprintMatched=yes' "$TMP/out" && pass "pass line states the fingerprint was matched" || bad "pass line states the fingerprint was matched"
guard 1 "non-synthetic key with a different fingerprint is blocked" --image "$REPO@$GOOD_DIG" --expected-map-key-fingerprint 000000000000
PARKIO_WEB_EXPECTED_MAP_KEY_FINGERPRINT=000000000000 guard 1 "fingerprint from the environment is enforced" --image "$REPO@$GOOD_DIG"
guard 2 "malformed expected fingerprint is a usage error" --image "$REPO@$GOOD_DIG" --expected-map-key-fingerprint XYZ
guard 0 "mutable tag passes only via its resolved config ID" --image "$REPO:good-tag"
grep -q "configId=$GOOD_ID" "$TMP/out" && pass "tag pass reports the resolved config ID" || bad "tag pass reports the resolved config ID"
guard 0 "locally built image without repo digests passes" --image "local/parkio-web:sha-abc123"
if grep -q "^create .*$GOOD_ID" "$FAKE/calls.log"; then pass "bundle is read from the resolved config ID, not the tag"; else bad "bundle is read from the resolved config ID, not the tag"; fi

# --- host env signal: accepted parser forms ---------------------------------
i=0
while IFS= read -r line; do
  i=$((i + 1))
  printf '%b' "$line" >"$TMP/env-$i.env"
  guard 1 "host env form #$i ($(printf '%s' "$line" | head -c 50)) is blocked" --image "$REPO@$GOOD_DIG" --env-file "$TMP/env-$i.env"
done <<'EOF'
VITE_MAPTILER_KEY=ci-web-build-security-synthetic\n
VITE_MAPTILER_KEY="ci-web-build-security-synthetic"\n
VITE_MAPTILER_KEY='ci-web-build-security-synthetic'\n
export VITE_MAPTILER_KEY=ci-web-build-security-synthetic\n
VITE_MAPTILER_KEY = ci-web-build-security-synthetic  \n
VITE_MAPTILER_KEY=ci-web-build-security-synthetic\r\n
  VITE_MAPTILER_KEY=ci-web-build-security-synthetic-suffix\n
VITE_MAPTILER_KEY=real-looking\nVITE_MAPTILER_KEY=SECRET_SENTINEL_MAPTILER_PUBLIC_KEY\n
EOF
printf 'VITE_MAPTILER_KEY=%s\r\n' "$GOOD_KEY" >"$TMP/env-good.env"
guard 0 "non-synthetic host env with a valid image passes" --image "$REPO@$GOOD_DIG" --env-file "$TMP/env-good.env"
printf 'VITE_MAPTILER_KEY=%s\n' "$GOOD_KEY" >"$TMP/env-good2.env"
guard 1 "non-synthetic host env does NOT rescue a synthetic image" --image "$REPO:synthetic" --env-file "$TMP/env-good2.env"

# --- legacy pin-file signal -----------------------------------------------------
guard 0 "repo web pin file is accepted as a secondary signal" --image "$REPO@$GOOD_DIG" --pin-file "$ROOT/docker/docker-compose.web-release-pin.yml"
printf 'services:\n  web:\n    image: %s@%s\n' "$REPO" "$BAD_MANIFEST" >"$TMP/pin-bad.yml"
guard 1 "pin file naming the known-bad digest is blocked" --image "$REPO@$GOOD_DIG" --pin-file "$TMP/pin-bad.yml"

# ---------------------------------------------------------------------------
# Executable mode / fresh checkout
# ---------------------------------------------------------------------------
echo "--- executable mode and fresh checkout ---"
cp "$GUARD" "$TMP/guard-noexec.sh"
chmod 0644 "$TMP/guard-noexec.sh"
set +e
"$TMP/guard-noexec.sh" --help >/dev/null 2>&1; rc_path=$?
bash "$TMP/guard-noexec.sh" --help >/dev/null 2>&1; rc_bash=$?
set -e
if [ "$rc_path" -eq 126 ] && [ "$rc_bash" -eq 0 ]; then
  pass "negative control: non-executable guard fails by path (126) but runs via bash"
else
  bad "negative control: expected path=126 bash=0, got path=$rc_path bash=$rc_bash"
fi

if git -C "$ROOT" rev-parse --git-dir >/dev/null 2>&1; then
  for f in scripts/guard-web-synthetic-map-deploy.sh scripts/parkio-prod-compose.sh \
           scripts/deploy-hosted-beta.sh scripts/deploy-invite-production.sh \
           scripts/rollback-hosted-beta.sh scripts/rollback-invite-production.sh; do
    mode="$(git -C "$ROOT" ls-files -s -- "$f" | awk '{print $1}')"
    if [ "$mode" = "100755" ]; then pass "index mode 100755: $f"; else bad "index mode 100755: $f (got '${mode:-untracked}')"; fi
  done
  FRESH="$TMP/fresh"
  mkdir -p "$FRESH"
  (cd "$ROOT" && git checkout-index -a -f --prefix="$FRESH/" 2>/dev/null) || true
  if [ -f "$FRESH/scripts/parkio-prod-compose.sh" ]; then
    fake_reset
    compose_model "$REPO@$GOOD_DIG"
    printf 'VITE_MAPTILER_KEY=%s\n' "$GOOD_KEY" >"$TMP/fresh.env"
    set +e
    PATH="$FAKE/bin:$REAL_PATH" PARKIO_ENV_FILE="$TMP/fresh.env" \
      "$FRESH/scripts/parkio-prod-compose.sh" up -d --no-build --no-deps web >"$TMP/out" 2>"$TMP/err"
    rc=$?
    set -e
    if [ "$rc" -eq 0 ] && grep -q 'web-map-deploy-guard: PASS' "$TMP/out" && grep -q 'MUTATION .* up -d --no-build --no-deps web' "$FAKE/mutations.log"; then
      pass "fresh checkout: wrapper runs by path and executes the guard"
    else
      bad "fresh checkout: wrapper runs by path and executes the guard (rc=$rc)"
    fi
  else
    bad "fresh checkout export failed"
  fi
fi

# ---------------------------------------------------------------------------
# Real caller: scripts/parkio-prod-compose.sh
# ---------------------------------------------------------------------------
echo "--- scripts/parkio-prod-compose.sh ---"
printf 'VITE_MAPTILER_KEY=%s\n' "$GOOD_KEY" >"$TMP/prod.env"

wrapper() { # wrapper EXPECTED_RC EXPECT_MUTATION(yes|no) NAME ARGS...
  local expected="$1" want="$2" name="$3" rc mutated=no
  shift 3
  set +e
  PATH="$FAKE/bin:$REAL_PATH" PARKIO_ENV_FILE="${WRAPPER_ENV:-$TMP/prod.env}" \
    bash "$ROOT/scripts/parkio-prod-compose.sh" "$@" >"$TMP/out" 2>"$TMP/err"
  rc=$?
  set -e
  [ -s "$FAKE/mutations.log" ] && mutated=yes
  if [ "$rc" -eq "$expected" ] && [ "$mutated" = "$want" ]; then
    pass "$name (exit $rc, mutation=$mutated)"
  else
    bad "$name: expected exit $expected mutation=$want, got exit $rc mutation=$mutated"
  fi
}

fake_reset; compose_model "$REPO:synthetic"
wrapper 0 yes "gateway-only --no-deps up is not gated (no web)" up -d --no-build --no-deps gateway-service
if [ -f "$FAKE/config.log" ] || grep -q '^image inspect' "$FAKE/calls.log"; then bad "gateway-only op never inspects web"; else pass "gateway-only op never inspects web"; fi
fake_reset; compose_model "$REPO:synthetic"
wrapper 0 yes "non-creating subcommand (ps) is not gated" ps
fake_reset; compose_model "$REPO:synthetic"
wrapper 1 no "synthetic web image: 'up -d --no-deps web' blocked before compose up" up -d --no-build --no-deps web
fake_reset; compose_model "$REPO:synthetic"
wrapper 1 no "synthetic web image: 'up -d' (all services) blocked" up -d --no-build
fake_reset; compose_model "$REPO:synthetic"
wrapper 1 no "synthetic web image: 'up -d gateway-service' without --no-deps blocked" up -d gateway-service
fake_reset; compose_model "$REPO:synthetic"
wrapper 1 no "synthetic web image: value option not mistaken for a service (--timeout 30)" up -d --no-deps --timeout 30
fake_reset; compose_model "$REPO:synthetic"
wrapper 1 no "synthetic web image: global --profile before up is still gated" --profile ops up -d
fake_reset; compose_model "$REPO:synthetic"
wrapper 1 no "synthetic web image: 'create web' blocked" create web
fake_reset; compose_model "$REPO:synthetic"
wrapper 1 no "synthetic web image: 'run --rm web sh' blocked" run --rm web sh
fake_reset; compose_model "$REPO@$GOOD_DIG"
wrapper 1 no "'up --build' refused when web is targeted" up -d --build web
fake_reset; compose_model "$REPO@$GOOD_DIG"
wrapper 1 no "'up --pull always' refused when web is targeted" up -d --pull always
fake_reset; compose_model "$REPO@$GOOD_DIG"
wrapper 1 no "'up --pull=always' refused when web is targeted" up -d --pull=always web
fake_reset; compose_model "$REPO@$GOOD_DIG"
wrapper 0 yes "valid web image: 'up -d --no-deps --force-recreate web' passes" up -d --no-build --no-deps --force-recreate web
fake_reset; compose_model "$REPO@$GOOD_DIG"
cat >"$TMP/extra.yml" <<'EOF'
services: {}
EOF
wrapper 0 yes "operator -f overlay is included in the model the guard reads" -f "$TMP/extra.yml" up -d --no-deps web
grep -q -- "-f $TMP/extra.yml config --format json" "$FAKE/config.log" && pass "guard rendered config with the operator overlay" || bad "guard rendered config with the operator overlay"
fake_reset; compose_model "-"
wrapper 0 yes "model without a web service skips the guard" up -d
fake_reset; compose_model "$REPO@$GOOD_DIG"; touch "$FAKE/compose-fail"
wrapper 1 no "compose config render failure fails closed" up -d --no-deps web
fake_reset; compose_model "$REPO:not-pulled"
wrapper 1 no "web image not pulled yet: blocked, nothing started" up -d --no-deps web
fake_reset; compose_model "$REPO@$GOOD_DIG"
printf 'export VITE_MAPTILER_KEY="ci-web-build-security-synthetic"\r\n' >"$TMP/prod-bad.env"
WRAPPER_ENV="$TMP/prod-bad.env" wrapper 1 no "synthetic host env blocks even a valid image" up -d --no-deps web
fake_reset; compose_model "$REPO:synthetic"
PARKIO_SKIP_WEB_MAP_GUARD=1 wrapper 1 no "legacy PARKIO_SKIP_WEB_MAP_GUARD=1 no longer skips" up -d --no-deps web
fake_reset; compose_model "$REPO:synthetic"
PARKIO_SKIP_WEB_MAP_GUARD=I_ACCEPT_UNVERIFIED_WEB_IMAGE wrapper 0 yes "explicit break-glass token skips with a warning" up -d --no-deps web
grep -q 'break-glass' "$TMP/err" && pass "break-glass warning printed" || bad "break-glass warning printed"

# ---------------------------------------------------------------------------
# Real caller: parkio_compose_up (deploy-hosted-beta, deploy-invite-production, rollback)
# ---------------------------------------------------------------------------
echo "--- parkio_compose_up (scripts/lib/deploy-common.sh) ---"
compose_up() { # compose_up EXPECTED_RC EXPECT_MUTATION NAME [RUNTIME_SERVICES...]
  local expected="$1" want="$2" name="$3" rc mutated=no
  shift 3
  set +e
  PATH="$FAKE/bin:$REAL_PATH" bash -c '
    set -euo pipefail
    source "$1/scripts/lib/deploy-common.sh"
    PARKIO_COMPOSE_FILES="-f docker/docker-compose.yml"
    env_file="$2"; shift 2
    PARKIO_RUNTIME_SERVICES=("$@")
    parkio_compose_up "$env_file"
    echo AFTER_UP
  ' _ "$ROOT" "$TMP/prod.env" "$@" >"$TMP/out" 2>"$TMP/err"
  rc=$?
  set -e
  [ -s "$FAKE/mutations.log" ] && mutated=yes
  if [ "$rc" -eq "$expected" ] && [ "$mutated" = "$want" ]; then
    pass "$name (exit $rc, mutation=$mutated)"
  else
    bad "$name: expected exit $expected mutation=$want, got exit $rc mutation=$mutated"
  fi
}
fake_reset; compose_model "$REPO:synthetic"
compose_up 1 no "deploy/rollback up with synthetic web image aborts before compose up" gateway-service web caddy
grep -q AFTER_UP "$TMP/out" && bad "set -e caller stops after guard failure" || pass "set -e caller stops after guard failure"
fake_reset; compose_model "local/parkio-web:sha-abc123"
compose_up 0 yes "deploy/rollback up with locally built valid web image proceeds" gateway-service web caddy
fake_reset; compose_model "-"
compose_up 0 yes "deploy/rollback up with no web service in the model proceeds" gateway-service
fake_reset; compose_model "$REPO@$BAD_MANIFEST"
compose_up 1 no "rollback to the known-bad manifest is refused"

for f in scripts/deploy-hosted-beta.sh scripts/deploy-invite-production.sh scripts/rollback-hosted-beta.sh; do
  if grep -Eq '^[^#]*(docker compose|parkio_compose)[^#]*[[:space:]]up([[:space:]]|$)' "$ROOT/$f"; then
    bad "$f starts containers only through parkio_compose_up"
  else
    pass "$f starts containers only through parkio_compose_up"
  fi
done

# ---------------------------------------------------------------------------
# Part B: real docker daemon with tiny fixture images
# ---------------------------------------------------------------------------
echo "=== Part B: real docker fixtures ==="
if docker version >/dev/null 2>&1 && [ "${PARKIO_GUARD_TEST_REAL_DOCKER:-1}" != "0" ]; then
  TAG="parkio-web-map-guard-test"
  built=()
  build_fixture() { # build_fixture NAME KIND
    local ctx="$TMP/ctx-$1"
    mkdir -p "$ctx/root"
    bundle "$2" "$ctx/root"
    printf 'FROM scratch\nCOPY root/ /\n' >"$ctx/Dockerfile"
    docker build -q -t "$TAG:$1" "$ctx" >/dev/null
    built+=("$TAG:$1")
  }
  cleanup_real() { [ "${#built[@]}" -gt 0 ] && docker rmi -f "${built[@]}" >/dev/null 2>&1 || true; rm -rf "$TMP"; }
  trap cleanup_real EXIT
  build_fixture good good
  build_fixture synthetic synthetic
  build_fixture nohtml no-html
  docker tag "$TAG:synthetic" "$TAG:innocent-looking-alias"
  built+=("$TAG:innocent-looking-alias")

  real_guard() {
    local expected="$1" name="$2" rc
    shift 2
    set +e
    bash "$GUARD" "$@" >"$TMP/out" 2>"$TMP/err"
    rc=$?
    set -e
    if [ "$rc" -eq "$expected" ]; then pass "real docker: $name (exit $rc)"; else bad "real docker: $name: expected $expected, got $rc"; fi
  }
  before="$(docker ps -aq | sort)"
  real_guard 0 "valid non-synthetic image passes" --image "$TAG:good"
  assert_no_key_leak "real docker pass"
  real_guard 1 "synthetic baked key is blocked" --image "$TAG:synthetic"
  real_guard 1 "retagged synthetic image is blocked by content" --image "$TAG:innocent-looking-alias"
  real_guard 1 "image without a web root is blocked" --image "$TAG:nohtml"
  real_guard 1 "image absent locally is blocked" --image "$TAG:never-built"
  after="$(docker ps -aq | sort)"
  if [ "$before" = "$after" ]; then pass "real docker: inspection containers are removed"; else bad "real docker: inspection containers are removed"; fi
else
  if [ "${PARKIO_GUARD_TEST_REQUIRE_DOCKER:-0}" = "1" ]; then
    TESTS=$((TESTS + 1)); FAILED=$((FAILED + 1))
    echo "FAIL real docker required (PARKIO_GUARD_TEST_REQUIRE_DOCKER=1) but unavailable"
  else
    echo "SKIP Part B: docker daemon not available"
  fi
fi

if [ "$FAILED" -gt 0 ]; then
  echo "=== RESULT: FAIL — $FAILED of $TESTS assertions failed ==="
  exit 1
fi
echo "=== RESULT: PASS — all $TESTS assertions passed ==="
exit 0
