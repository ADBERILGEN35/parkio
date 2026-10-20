#!/usr/bin/env bash
# N-17 / #378: stub-driven end-to-end provenance gate on the real deploy path.
#
# Drives scripts/parkio-prod-compose.sh with a fake docker + cosign. Unpinned,
# missing (pull_policy=never), wrong-identity and invalid-attestation fixtures
# must produce zero `up` calls. Accepted immutable fixtures proceed. Also
# validates compose overlays synthetically for digest pins and explicit
# pull_policy on build+image services.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
chmod +x scripts/parkio-prod-compose.sh scripts/lib/deploy-image-provenance.sh

pass=0
fail=0
ok() { echo "PASS $1"; pass=$((pass + 1)); }
bad() { echo "FAIL $1"; fail=$((fail + 1)); }

WORK="$(mktemp -d "${TMPDIR:-/tmp}/parkio-n17-prov.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
BIN="$WORK/bin"
STATE="$WORK/state"
mkdir -p "$BIN" "$STATE/images" "$WORK/tree/scripts/lib" "$WORK/tree/docker" "$WORK/tree/scripts"

# Minimal production-like tree for the wrapper
cp scripts/parkio-prod-compose.sh "$WORK/tree/scripts/"
cp scripts/lib/deploy-image-provenance.sh \
   scripts/lib/prod-compose-overlays.sh \
   scripts/lib/prod-nonroot-volume-gate.sh \
   scripts/lib/web-map-guard.sh \
   scripts/lib/web-conf-d-guard.sh \
   scripts/lib/web-api-endpoint-guard.sh \
   "$WORK/tree/scripts/lib/" 2>/dev/null || true
# Stub web guards so this suite isolates provenance
cat >"$WORK/tree/scripts/lib/web-map-guard.sh" <<'EOF'
PARKIO_WEB_GUARD_BREAK_GLASS_TOKEN="I_ACCEPT_UNVERIFIED_WEB_IMAGE"
parkio_web_guard_split_args() {
  PWG_GLOBAL=(); PWG_SUBCMD=""; PWG_SUBARGS=(); PWG_AMBIGUOUS=""; PWG_ALL_ARGS=("$@")
  while [ "$#" -gt 0 ]; do
    case "$1" in
      -f|--file|--env-file|-p|--project-name|--profile|--project-directory)
        PWG_GLOBAL+=("$1" "${2:-}"); shift; [ "$#" -gt 0 ] && shift ;;
      --*=*|-*) PWG_GLOBAL+=("$1"); shift ;;
      *) PWG_SUBCMD="$1"; shift; PWG_SUBARGS=("$@"); return 0 ;;
    esac
  done
}
parkio_web_guard_decide() {
  # Treat plain `up` as non-web so we exercise provenance without the map guard.
  PWG_DECISION=skip
  PWG_REASON="stub: provenance suite"
}
parkio_web_guard_skip_requested() { return 0; }
parkio_web_guard_bind() { :; }
EOF
printf '%s\n' '# stub' >"$WORK/tree/scripts/lib/web-conf-d-guard.sh"
cat >>"$WORK/tree/scripts/lib/web-conf-d-guard.sh" <<'EOF'
parkio_web_conf_d_skip_requested() { return 0; }
parkio_web_conf_d_check() { return 0; }
EOF
cat >"$WORK/tree/scripts/lib/web-api-endpoint-guard.sh" <<'EOF'
parkio_web_api_endpoint_check() { return 0; }
EOF
# Nonroot gate + overlays: no-op for this suite
cat >"$WORK/tree/scripts/lib/prod-compose-overlays.sh" <<'EOF'
PARKIO_CIVO_NONROOT_OVERLAY_REL="docker/docker-compose.civo-nonroot.yml"
PARKIO_CIVO_ALERTMANAGER_OVERLAY_REL="docker/docker-compose.civo-alertmanager.yml"
parkio_select_production_overlays() { return 0; }
EOF
cat >"$WORK/tree/scripts/lib/prod-nonroot-volume-gate.sh" <<'EOF'
parkio_nonroot_volume_gate_maybe() { return 0; }
EOF
cp scripts/lib/deploy-image-provenance.sh "$WORK/tree/scripts/lib/"

# Tiny compose file list + env
printf 'docker/fixture.yml\n' >"$WORK/tree/docker/compose.production.files"
printf 'PARKIO_DOMAIN=example.invalid\n' >"$WORK/env"
# fixture.yml is unused because docker compose config is stubbed from STATE/model.json
printf 'services: {}\n' >"$WORK/tree/docker/fixture.yml"

DIGEST_OK="sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
DIGEST_BAD="sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
APP_IMG="ghcr.io/adberilgen35/parkio/gateway-service@${DIGEST_OK}"
INFRA_IMG="postgres@${DIGEST_OK}"

write_model() {
  python3 - "$STATE/model.json" "$@" <<'PY'
import json, sys
out = sys.argv[1]
services = {}
i = 2
while i < len(sys.argv):
    name = sys.argv[i]
    image = sys.argv[i+1]
    pull = sys.argv[i+2]
    build = sys.argv[i+3]
    svc = {"image": image}
    if pull != "-":
        svc["pull_policy"] = pull
    if build == "build":
        svc["build"] = {"context": ".."}
    services[name] = svc
    i += 4
json.dump({"name": "parkio", "services": services}, open(out, "w"))
PY
}

cat >"$BIN/docker" <<'EOF'
#!/usr/bin/env bash
set -u
S="$FAKE_STATE"
echo "DOCKER $*" >>"$S/calls.log"
case "${1:-}" in
  compose)
    shift
    while [ $# -gt 0 ]; do
      case "$1" in
        --env-file|-f|--file|-p|--project-name|--profile|--project-directory) shift 2 ;;
        *) break ;;
      esac
    done
    sub="${1:-}"; shift || true
    case "$sub" in
      config)
        if [[ " $* " == *" --format json "* ]]; then
          cat "$S/model.json"
        else
          echo "services: {}"
        fi
        ;;
      up) echo "UP $*" >>"$S/ups.log"; echo "up-ok" ;;
      *) echo "REFUSED compose $sub $*" >>"$S/calls.log"; exit 97 ;;
    esac
    ;;
  image)
    [ "${2:-}" = inspect ] || exit 1
    ref="${3:-}"
    key=$(printf '%s' "$ref" | tr '/:@' '___')
    if [ -f "$S/images/$key" ]; then
      echo "[{\"Id\":\"sha256:deadbeef\"}]"
      exit 0
    fi
    echo "Error: No such image: $ref" >&2
    exit 1
    ;;
  *) echo "REFUSED $*" >>"$S/calls.log"; exit 97 ;;
esac
EOF
chmod +x "$BIN/docker"

cat >"$BIN/cosign" <<'EOF'
#!/usr/bin/env bash
set -u
S="$FAKE_STATE"
echo "COSIGN $*" >>"$S/cosign.log"
# Expect: verify-attestation ... <image>
img="${*: -1}"
ident_re=""
issuer=""
prev=""
for a in "$@"; do
  if [ "$prev" = "--certificate-identity-regexp" ]; then ident_re="$a"; fi
  if [ "$prev" = "--certificate-oidc-issuer" ]; then issuer="$a"; fi
  prev="$a"
done
case "$img" in
  *wrong-identity*)
    echo "error: identity mismatch for $img (got wrong, want $ident_re)" >&2
    exit 1
    ;;
  *invalid-attest*)
    echo "error: invalid attestation for $img" >&2
    exit 1
    ;;
esac
if [ -f "$S/cosign-fail-all" ]; then
  echo "error: injected cosign failure" >&2
  exit 1
fi
# Accept only the approved issuer
if [ -n "$issuer" ] && [ "$issuer" != "${PARKIO_PROVENANCE_OIDC_ISSUER:-https://token.actions.githubusercontent.com}" ]; then
  echo "error: issuer mismatch" >&2
  exit 1
fi
echo "verified $img"
exit 0
EOF
chmod +x "$BIN/cosign"

have_image() {
  local ref="$1"
  local key
  key=$(printf '%s' "$ref" | tr '/:@' '___')
  mkdir -p "$STATE/images"
  echo present >"$STATE/images/$key"
}

run_up() {
  : >"$STATE/calls.log"
  : >"$STATE/ups.log"
  : >"$STATE/cosign.log"
  rm -f "$STATE/cosign-fail-all"
  (
    cd "$WORK/tree"
    env -u PARKIO_SKIP_IMAGE_PROVENANCE \
      PATH="$BIN:$PATH" FAKE_STATE="$STATE" \
      PARKIO_ENV_FILE="$WORK/env" \
      PARKIO_COMPOSE_FILES_LIST="$WORK/tree/docker/compose.production.files" \
      bash scripts/parkio-prod-compose.sh up -d --no-build --no-deps gateway-service
  ) >"$WORK/out" 2>"$WORK/err" || true
}

ups() { [ -f "$STATE/ups.log" ] && wc -l <"$STATE/ups.log" | tr -d ' ' || echo 0; }

# --- Case 1: unpinned image -> zero ups ---
write_model gateway-service "ghcr.io/adberilgen35/parkio/gateway-service:latest" never - \
            redis "$INFRA_IMG" never -
have_image "ghcr.io/adberilgen35/parkio/gateway-service:latest"
have_image "$INFRA_IMG"
run_up
if [ "$(ups)" = "0" ] && grep -q 'mutable image' "$WORK/err"; then
  ok "unpinned image refuses up"
else
  bad "unpinned image refuses up (ups=$(ups))"
  sed 's/^/  | /' "$WORK/err" | tail -20
fi

# --- Case 2: missing local image with pull_policy=never -> zero ups ---
write_model gateway-service "$APP_IMG" never - \
            redis "$INFRA_IMG" never -
rm -rf "$STATE/images"
have_image "$INFRA_IMG"
# APP_IMG intentionally missing
run_up
if [ "$(ups)" = "0" ] && grep -q 'missing locally' "$WORK/err"; then
  ok "missing pull_policy=never image refuses up"
else
  bad "missing pull_policy=never image refuses up (ups=$(ups))"
  sed 's/^/  | /' "$WORK/err" | tail -20
fi

# --- Case 3: wrong identity -> zero ups ---
WRONG_IMG="ghcr.io/adberilgen35/parkio/gateway-service-wrong-identity@${DIGEST_OK}"
write_model gateway-service "$WRONG_IMG" never - \
            redis "$INFRA_IMG" never -
rm -rf "$STATE/images"; mkdir -p "$STATE/images"
have_image "$WRONG_IMG"
have_image "$INFRA_IMG"
run_up
if [ "$(ups)" = "0" ] && grep -qi 'attestation/identity failed\|identity' "$WORK/err"; then
  ok "wrong-identity attestation refuses up"
else
  bad "wrong-identity attestation refuses up (ups=$(ups))"
  sed 's/^/  | /' "$WORK/err" | tail -20
fi

# --- Case 4: invalid attestation -> zero ups ---
BAD_IMG="ghcr.io/adberilgen35/parkio/gateway-service-invalid-attest@${DIGEST_OK}"
write_model gateway-service "$BAD_IMG" never - \
            redis "$INFRA_IMG" never -
rm -rf "$STATE/images"; mkdir -p "$STATE/images"
have_image "$BAD_IMG"
have_image "$INFRA_IMG"
run_up
if [ "$(ups)" = "0" ] && grep -qi 'attestation/identity failed\|invalid attestation' "$WORK/err"; then
  ok "invalid attestation refuses up"
else
  bad "invalid attestation refuses up (ups=$(ups))"
  sed 's/^/  | /' "$WORK/err" | tail -20
fi

# --- Case 5: accepted immutable fixtures proceed ---
write_model gateway-service "$APP_IMG" never - \
            redis "$INFRA_IMG" never -
rm -rf "$STATE/images"; mkdir -p "$STATE/images"
have_image "$APP_IMG"
have_image "$INFRA_IMG"
run_up
if [ "$(ups)" = "1" ] && grep -q 'image provenance: ok' "$WORK/out" "$WORK/err"; then
  ok "accepted immutable fixtures call up once"
else
  bad "accepted immutable fixtures call up once (ups=$(ups))"
  sed 's/^/  | /' "$WORK/out" "$WORK/err" | tail -30
fi

# --- Case 6: build+image without pull_policy refuses ---
write_model gateway-service "$APP_IMG" - build \
            redis "$INFRA_IMG" never -
rm -rf "$STATE/images"; mkdir -p "$STATE/images"
have_image "$APP_IMG"
have_image "$INFRA_IMG"
run_up
if [ "$(ups)" = "0" ] && grep -q 'build+image without explicit pull_policy' "$WORK/err"; then
  ok "build+image without pull_policy refuses up"
else
  bad "build+image without pull_policy refuses up (ups=$(ups))"
  sed 's/^/  | /' "$WORK/err" | tail -20
fi

# --- Synthetic overlay validation on the real tree ---
python3 - "$ROOT" <<'PY'
import re, sys
from pathlib import Path
root = Path(sys.argv[1])
errors = []
digest_re = re.compile(r"@sha256:[0-9a-fA-F]{64}")

# Infra pin overlay: every image line must be digest-pinned; pull_policy never present
infra = (root / "docker/docker-compose.infra-release-pins.yml").read_text()
for i, line in enumerate(infra.splitlines(), 1):
    s = line.strip()
    if s.startswith("image:"):
        if not digest_re.search(s):
            errors.append(f"infra-release-pins.yml:{i}: non-digest image: {s}")
if "pull_policy: never" not in infra:
    errors.append("infra-release-pins.yml: missing pull_policy: never")

# production file set includes infra pins
files = (root / "docker/compose.production.files").read_text()
if "docker/docker-compose.infra-release-pins.yml" not in files:
    errors.append("compose.production.files missing infra-release-pins.yml")

# images.yml: every parkio image has pull_policy: build
images = (root / "docker/docker-compose.images.yml").read_text().splitlines()
for i, line in enumerate(images):
    if "image: parkio/" in line:
        window = "\n".join(images[i:i+4])
        if "pull_policy:" not in window:
            errors.append(f"images.yml:{i+1}: image without nearby pull_policy")

# Dockerfiles: Temurin digests
for df in (root / "services").glob("*/Dockerfile"):
    text = df.read_text()
    if "eclipse-temurin:21-" in text:
        errors.append(f"{df}: still uses mutable temurin tag")
    if "eclipse-temurin@sha256:" not in text:
        errors.append(f"{df}: missing temurin digest pin")

# Base compose defaults digest-pinned
base = (root / "docker/docker-compose.yml").read_text()
for tag in ["postgres:16-alpine", "redis:7-alpine", "cp-kafka:7.7.1", "clamav:1.4"]:
    if f":-{tag}" in base or f"IMAGE:-{tag}" in base:
        # check default forms
        pass
mutable_defaults = re.findall(r"\$\{[A-Z0-9_]+:-([^}@]+:[^}]+)\}", base)
# filter image-like defaults that are not digests
for d in mutable_defaults:
    if "/" in d or d.startswith(("postgres", "redis", "prom/", "grafana/", "clamav", "caddy", "confluentinc", "danielqsj", "postgis")):
        if "@sha256:" not in d and "ghcr.io/adberilgen35/parkio/" not in d:
            # MinIO already digest; others should be
            if "@sha256:" not in d:
                errors.append(f"docker-compose.yml mutable default: {d}")

if errors:
    for e in errors:
        print("OVERLAY-FAIL", e)
    sys.exit(1)
print("OVERLAY-OK")
sys.exit(0)
PY
if [ $? -eq 0 ]; then
  ok "synthetic compose overlay validation"
else
  bad "synthetic compose overlay validation"
fi

echo "=== B-02 gate args match cosign-pin.env ==="
# shellcheck source=scripts/lib/cosign-pin.env
source scripts/lib/cosign-pin.env
grep -Fq "https://slsa.dev/provenance/v1" scripts/lib/deploy-image-provenance.sh
grep -Fq "$PARKIO_PROVENANCE_OIDC_ISSUER" scripts/lib/deploy-image-provenance.sh
grep -Fq "$PARKIO_PROVENANCE_IDENTITY_REGEXP" scripts/lib/deploy-image-provenance.sh
ok "cosign-pin.env identity matches deploy gate"

echo
echo "Results: pass=$pass fail=$fail"
[ "$fail" -eq 0 ]
