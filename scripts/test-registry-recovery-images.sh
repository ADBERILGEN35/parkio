#!/usr/bin/env bash
# Tests for scripts/registry-recovery-images.sh (U14): inventory from fixtures, fail-closed on a
# mutable pin, and the pull check against a fake docker on PATH. Needs no network and no docker.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCRIPT="$ROOT/scripts/registry-recovery-images.sh"
TMP="$(mktemp -d "${TMPDIR:-/tmp}/parkio-registry-recovery.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT
# Never write fixture rows into a real GitHub job summary.
export GITHUB_STEP_SUMMARY="$TMP/summary.md"
PASS=0
FAIL=0
ok() { echo "PASS $1"; PASS=$((PASS + 1)); }
bad() { echo "FAIL $1"; FAIL=$((FAIL + 1)); }

D1='sha256:1111111111111111111111111111111111111111111111111111111111111111'
D2='sha256:2222222222222222222222222222222222222222222222222222222222222222'
D3='sha256:3333333333333333333333333333333333333333333333333333333333333333'
D4='sha256:4444444444444444444444444444444444444444444444444444444444444444'
AUTH="ghcr.io/adberilgen35/parkio/auth-service@$D1"
WEB="ghcr.io/adberilgen35/parkio/web@$D2"
MINIO="ghcr.io/adberilgen35/parkio/minio@$D3"
MC="ghcr.io/adberilgen35/parkio/mc@$D4"

make_fixture() {
  local root="$1"
  mkdir -p "$root/docker"
  cat > "$root/docker/compose.production.files" <<FIX
# fixture file set
docker/docker-compose.yml   # base, has the MinIO defaults
docker/docker-compose.apps.yml
docker/docker-compose.auth-release-pin.yml
docker/docker-compose.web-release-pin.yml
FIX
  cat > "$root/docker/docker-compose.yml" <<FIX
services:
  prometheus:
    image: prom/prometheus:v2.54.1
  minio:
    image: \${MINIO_IMAGE:-$MINIO}
  minio-setup:
    image: \${MINIO_MC_IMAGE:-$MC}
FIX
  cat > "$root/docker/docker-compose.apps.yml" <<FIX
services:
  user-service:
    build:
      context: ..
    image: parkio/user-service:\${PARKIO_IMAGE_TAG:-local}
FIX
  cat > "$root/docker/docker-compose.auth-release-pin.yml" <<FIX
# Rollback: ghcr.io/adberilgen35/parkio/auth-service@sha256:9999999999999999999999999999999999999999999999999999999999999999
services:
  auth-service:
    image: $AUTH  # pinned
FIX
  cat > "$root/docker/docker-compose.web-release-pin.yml" <<FIX
services:
  web:
    image: $WEB
FIX
  cat > "$root/docker/.env.example" <<FIX
MINIO_IMAGE=$MINIO
MINIO_MC_IMAGE=$MC
FIX
}

# ---- list ----
make_fixture "$TMP/good"
expected="$(printf '%s\n' "$AUTH" "$MC" "$MINIO" "$WEB" | sort -u)"
actual="$("$SCRIPT" list --root "$TMP/good" 2>"$TMP/good.err" || true)"
if [ "$actual" = "$expected" ]; then ok "list: digest pins from the file set plus MinIO, deduplicated, comments and build-only services ignored"; else bad "list output differs"; printf '%s\n' "$actual"; fi
if ! printf '%s\n' "$actual" | grep -q 9999999999; then ok "list: rollback digest in a comment is not inventory"; else bad "list: rollback comment digest leaked into the inventory"; fi

make_fixture "$TMP/mutable"
cat > "$TMP/mutable/docker/docker-compose.web-release-pin.yml" <<FIX
services:
  web:
    image: ghcr.io/adberilgen35/parkio/web:sha-3bb89c6c
FIX
if "$SCRIPT" list --root "$TMP/mutable" >/dev/null 2>"$TMP/mutable.err"; then bad "list: a mutable tag in a pin file was accepted"; else
  rc=$?; if [ "$rc" -eq 3 ] && grep -q 'pins an image without a digest' "$TMP/mutable.err"; then ok "list: a pin file with a mutable tag is refused with exit 3"; else bad "list: mutable pin refused with rc=$rc and message: $(cat "$TMP/mutable.err")"; fi
fi

make_fixture "$TMP/nominio"
printf 'MINIO_IMAGE=minio/minio:RELEASE.2025-01-01\nMINIO_MC_IMAGE=%s\n' "$MC" > "$TMP/nominio/docker/.env.example"
if "$SCRIPT" list --root "$TMP/nominio" >/dev/null 2>"$TMP/nominio.err"; then bad "list: a MinIO default without a ghcr digest was accepted"; else
  if grep -q 'MINIO_IMAGE is not a ghcr.io digest pin' "$TMP/nominio.err"; then ok "list: MinIO defaults must be ghcr.io digest pins"; else bad "list: unexpected MinIO message: $(cat "$TMP/nominio.err")"; fi
fi

make_fixture "$TMP/missing"
rm "$TMP/missing/docker/docker-compose.web-release-pin.yml"
if "$SCRIPT" list --root "$TMP/missing" >/dev/null 2>"$TMP/missing.err"; then bad "list: a missing file in the set was accepted"; else
  if grep -q 'names a missing file' "$TMP/missing.err"; then ok "list: a missing file in the file set is an error"; else bad "list: unexpected missing-file message"; fi
fi

# ---- pull, against a fake docker ----
# The fake reads its behaviour from FAKE_DOCKER_MODE: allow (pull ok, digest and platform right),
# deny (every pull is refused by the registry), ghcrhidden (GHCR's "manifest unknown" for a private
# manifest and a token without access), wrongdigest (the pulled image carries another digest),
# arm64, network (every pull fails without a denial), noinspect (pull ok, inspect fails).
FAKE_BIN="$TMP/bin"
mkdir -p "$FAKE_BIN"
cat > "$FAKE_BIN/docker" <<'FAKE'
#!/usr/bin/env bash
set -euo pipefail
mode="${FAKE_DOCKER_MODE:-allow}"
echo "$*" >> "${FAKE_DOCKER_LOG:?}"
case "$1 $2" in
  "pull --quiet")
    [ "$mode" = deny ] && { echo "Error response from daemon: Head \"https://ghcr.io/v2/x/manifests/sha256:0\": denied: denied" >&2; exit 1; }
    [ "$mode" = ghcrhidden ] && { echo "Error response from daemon: manifest unknown" >&2; exit 1; }
    [ "$mode" = network ] && { echo "Error response from daemon: Get \"https://ghcr.io/v2/\": dial tcp: lookup ghcr.io: no such host" >&2; exit 1; }
    exit 0 ;;
  "image inspect")
    [ "$mode" = noinspect ] && { echo "Error: No such image" >&2; exit 1; }
    fmt="$4"; ref="$5"
    case "$fmt" in
      *RepoDigests*)
        if [ "$mode" = wrongdigest ]; then echo "${ref%@*}@sha256:abababababababababababababababababababababababababababababababab"; else echo "$ref"; fi ;;
      *Architecture*)
        if [ "$mode" = arm64 ]; then echo "linux/arm64"; else echo "linux/amd64"; fi ;;
      *) echo "unexpected format $fmt" >&2; exit 1 ;;
    esac ;;
  *) echo "unexpected docker call: $*" >&2; exit 1 ;;
esac
FAKE
chmod +x "$FAKE_BIN/docker"
export FAKE_DOCKER_LOG="$TMP/docker.log"

run_pull() {  # MODE [args...] -> rc, output in $TMP/pull.out
  local mode="$1"; shift
  : > "$FAKE_DOCKER_LOG"
  if FAKE_DOCKER_MODE="$mode" PATH="$FAKE_BIN:$PATH" "$SCRIPT" pull --root "$TMP/good" "$@" > "$TMP/pull.out" 2>&1; then return 0; else return $?; fi
}

if run_pull allow; then
  if [ "$(grep -c '^OK pulled and verified' "$TMP/pull.out")" = 4 ] && grep -q '4/4 images pulled' "$TMP/pull.out" && [ "$(grep -c '^pull --quiet' "$FAKE_DOCKER_LOG")" = 4 ]; then ok "pull: every pinned image is pulled once and verified"; else bad "pull: unexpected success output"; cat "$TMP/pull.out"; fi
else bad "pull: allow mode failed"; cat "$TMP/pull.out"; fi

if run_pull deny; then bad "pull: denied pulls reported success"; else
  if grep -q 'could not pull' "$TMP/pull.out" && grep -q '0/4 images pulled' "$TMP/pull.out"; then ok "pull: a denied pull fails the check"; else bad "pull: unexpected deny output"; cat "$TMP/pull.out"; fi
fi

if run_pull wrongdigest; then bad "pull: a different digest was accepted"; else
  if grep -q 'does not carry the pinned digest' "$TMP/pull.out"; then ok "pull: a pulled image with another digest fails the check"; else bad "pull: unexpected wrong-digest output"; cat "$TMP/pull.out"; fi
fi

if run_pull arm64; then bad "pull: a non-amd64 image was accepted"; else
  if grep -q 'expected linux/amd64' "$TMP/pull.out"; then ok "pull: a non-linux/amd64 image fails the check"; else bad "pull: unexpected platform output"; cat "$TMP/pull.out"; fi
fi

if run_pull deny --expect-denied; then
  if [ "$(grep -c '^OK denied' "$TMP/pull.out")" = 4 ] && grep -q '4/4 pulls denied' "$TMP/pull.out"; then ok "pull --expect-denied: every refused pull passes"; else bad "pull --expect-denied: unexpected output"; cat "$TMP/pull.out"; fi
else bad "pull --expect-denied failed with a denying registry"; cat "$TMP/pull.out"; fi

if run_pull ghcrhidden --expect-denied; then
  if [ "$(grep -c '^OK denied' "$TMP/pull.out")" = 4 ]; then ok "pull --expect-denied: GHCR's 'manifest unknown' for a hidden private manifest counts as a denial"; else bad "pull --expect-denied: ghcr hidden output"; cat "$TMP/pull.out"; fi
else bad "pull --expect-denied failed on GHCR's manifest-unknown denial"; cat "$TMP/pull.out"; fi

if run_pull network --expect-denied; then bad "pull --expect-denied: a network failure passed as a denial"; else
  if grep -q 'not because access was denied' "$TMP/pull.out" && grep -q 'no such host' "$TMP/pull.out"; then ok "pull --expect-denied: a failure that is not a denial fails the check and names the reason"; else bad "pull --expect-denied: unexpected network output"; cat "$TMP/pull.out"; fi
fi

if run_pull network; then bad "pull: a network failure reported success"; else
  if grep -q 'could not pull .*no such host' "$TMP/pull.out"; then ok "pull: a failed pull prints the registry's reason"; else bad "pull: network reason missing"; cat "$TMP/pull.out"; fi
fi

if run_pull noinspect; then bad "pull: an uninspectable image was accepted"; else
  if grep -q 'could not inspect it' "$TMP/pull.out" && grep -q '0/4 images pulled' "$TMP/pull.out"; then ok "pull: an inspect failure fails the image and keeps the final count"; else bad "pull: inspect failure output"; cat "$TMP/pull.out"; fi
fi

if run_pull allow --expect-denied; then bad "pull --expect-denied: a successful pull passed"; else
  if grep -q 'although this credential must have no read access' "$TMP/pull.out"; then ok "pull --expect-denied: a credential that can pull fails the check"; else bad "pull --expect-denied: unexpected output"; cat "$TMP/pull.out"; fi
fi

: > "$GITHUB_STEP_SUMMARY"
if run_pull allow && [ "$(grep -c 'pulled, digest verified' "$GITHUB_STEP_SUMMARY")" = 4 ]; then ok "pull: the job summary table lists every image"; else bad "pull: job summary missing rows"; fi

if "$SCRIPT" 2>/dev/null; then bad "usage: no command was accepted"; else ok "usage: a missing command exits non-zero"; fi
if "$SCRIPT" list --root 2>"$TMP/root.err"; then bad "usage: --root without a directory was accepted"; else grep -q -- '--root needs a directory' "$TMP/root.err" && ok "usage: --root without a directory is a usage error" || bad "usage: --root message"; fi

echo
echo "=== registry-recovery-images tests: pass=$PASS fail=$FAIL ==="
[ "$FAIL" -eq 0 ]
