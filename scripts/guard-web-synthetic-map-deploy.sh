#!/usr/bin/env bash
# Production web deploy guard (audit F-05): refuse to start a web image that is
# the known synthetic-map runtime or that bakes a synthetic MapTiler key.
#
# Usage:
#   bash scripts/guard-web-synthetic-map-deploy.sh --image REF [--env-file FILE] \
#     [--pin-file FILE] [--evidence-out FILE] [--expected-platform OS/ARCH] \
#     [--expected-map-key-fingerprint HEX12]
#   bash scripts/guard-web-synthetic-map-deploy.sh --compose-config-json FILE ...
#
# --image is the web image the deploy will actually start (callers resolve it from
# the rendered Compose model; --compose-config-json does that from a file). The
# image must already be present locally: it is identified by its immutable config
# ID, and its bundle is copied out of that exact ID and classified. A missing
# image, bundle or verdict fails closed. Nothing is pulled and no provider is
# contacted. Key values are never printed; only a 12-hex SHA-256 fingerprint.
# --expected-map-key-fingerprint (or PARKIO_WEB_EXPECTED_MAP_KEY_FINGERPRINT)
# additionally requires the baked key to match a fingerprint recorded from the
# authorized release key; without it the guard proves "not synthetic", not "authorized".
#
# Exit 0 = pass, 1 = blocked, 2 = usage.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PYTHON="${PYTHON:-python3}"
IMAGE=""
CONFIG_JSON=""
ENV_FILE=""
PIN_FILE=""
EVIDENCE_OUT=""
EXPECTED_PLATFORM=""
EXPECTED_FINGERPRINT="${PARKIO_WEB_EXPECTED_MAP_KEY_FINGERPRINT:-}"
WEB_ROOT_IN_IMAGE="/usr/share/nginx/html"

# Known bad runtime (PR #91 incident): the CI-acceptance archive published as
# the production web image. Blocked by manifest AND by config ID, so a re-push,
# retag or different manifest wrapping the same image is still refused.
BAD_MANIFEST="sha256:8d9bfca43d577afd62d20f7ffe3fcb2566fbf3bce9dbd758368562aec641487d"
BAD_CONFIG="sha256:985fd8a7684a63ea21cf10cbb36f8f2fc092b7d3f13ccbc8c18311380945f68c"
SYNTHETIC_CLASS="ci-web-build-security-synthetic"

usage() { sed -n '2,21p' "$0"; }

while [ "$#" -gt 0 ]; do
  case "$1" in
    --image) IMAGE="${2:-}"; shift 2 ;;
    --compose-config-json) CONFIG_JSON="${2:-}"; shift 2 ;;
    --env-file) ENV_FILE="${2:-}"; shift 2 ;;
    --pin-file) PIN_FILE="${2:-}"; shift 2 ;;
    --evidence-out) EVIDENCE_OUT="${2:-}"; shift 2 ;;
    --expected-platform) EXPECTED_PLATFORM="${2:-}"; shift 2 ;;
    --expected-map-key-fingerprint) EXPECTED_FINGERPRINT="${2:-}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "ERROR: unknown argument '$1'" >&2; usage >&2; exit 2 ;;
  esac
done

fail() {
  echo "web-map-deploy-guard: BLOCKED: $1" >&2
  exit 1
}

if [ -n "$EXPECTED_FINGERPRINT" ] && ! printf '%s' "$EXPECTED_FINGERPRINT" | grep -Eq '^[0-9a-f]{12}$'; then
  echo "ERROR: expected map key fingerprint must be 12 lowercase hex characters" >&2
  exit 2
fi

# --- Requested image reference -------------------------------------------------
if [ -n "$CONFIG_JSON" ]; then
  [ -r "$CONFIG_JSON" ] || fail "rendered compose config not readable: $CONFIG_JSON"
  IMAGE="$("$PYTHON" - "$CONFIG_JSON" <<'PY'
import json, sys
try:
    model = json.load(open(sys.argv[1]))
except Exception:
    sys.exit(3)
web = (model.get("services") or {}).get("web")
if web is None:
    sys.exit(4)
print(web.get("image") or "")
PY
)" || fail "cannot read services.web.image from the rendered compose config"
fi
if [ -z "$IMAGE" ]; then
  echo "ERROR: --image or --compose-config-json with a web service image is required" >&2
  exit 2
fi
case "$IMAGE" in
  *[[:space:]]*) fail "web image reference contains whitespace" ;;
esac
REQUESTED_DIGEST=""
case "$IMAGE" in
  *@sha256:*) REQUESTED_DIGEST="sha256:${IMAGE##*@sha256:}" ;;
esac
if [ "$REQUESTED_DIGEST" = "$BAD_MANIFEST" ]; then
  fail "requested web image is the known synthetic-map runtime ${BAD_MANIFEST}"
fi

# --- Host environment signal (early warning only; never proof of the bundle) ---
if [ -n "$ENV_FILE" ]; then
  [ -r "$ENV_FILE" ] || fail "env file not readable: $ENV_FILE"
  # The value stays inside the classifier process: never echoed, never in argv.
  if "$PYTHON" - "$ENV_FILE" "$ROOT/scripts/lib" <<'PY'
import re, sys
sys.path.insert(0, sys.argv[2])
import web_bundle_map_config as m
value = None
with open(sys.argv[1], "r", encoding="utf-8", errors="replace") as fh:
    for raw in fh:
        line = raw.rstrip("\r\n").strip()
        match = re.match(r"^(?:export\s+)?VITE_MAPTILER_KEY\s*=\s*(.*)$", line)
        if not match:
            continue
        v = match.group(1).strip()
        if len(v) >= 2 and v[0] == v[-1] and v[0] in "\"'":
            v = v[1:-1]
        value = v.strip()
sys.exit(0 if value is not None and m.is_synthetic(value) else 1)
PY
  then
    fail "host env VITE_MAPTILER_KEY is a known synthetic/placeholder value"
  fi
fi

# --- Legacy pin-file signal (requested reference as committed) -----------------
if [ -n "$PIN_FILE" ]; then
  [ -r "$PIN_FILE" ] || fail "web pin file not readable: $PIN_FILE"
  if grep -Fq "${BAD_MANIFEST#sha256:}" "$PIN_FILE" || grep -Fq "$SYNTHETIC_CLASS" "$PIN_FILE"; then
    fail "web pin file names the known synthetic-map runtime or key class"
  fi
fi

# --- Immutable identity of the image that will actually run -------------------
inspect_json="$(docker image inspect "$IMAGE" 2>/dev/null)" \
  || fail "web image '$IMAGE' is not present locally; pull it first (the guard verifies local image content and never pulls)"
identity="$(printf '%s' "$inspect_json" | "$PYTHON" -c '
import json, sys
data = json.load(sys.stdin)
if not isinstance(data, list) or len(data) != 1:
    sys.exit(3)
img = data[0]
print(img.get("Id") or "")
print((img.get("Os") or "") + "/" + (img.get("Architecture") or ""))
print(" ".join(img.get("RepoDigests") or []))
')" || fail "cannot parse docker image inspect for '$IMAGE'"
CONFIG_ID="$(printf '%s\n' "$identity" | sed -n 1p)"
PLATFORM="$(printf '%s\n' "$identity" | sed -n 2p)"
REPO_DIGESTS="$(printf '%s\n' "$identity" | sed -n 3p)"
case "$CONFIG_ID" in
  sha256:[0-9a-f]*) ;;
  *) fail "web image '$IMAGE' has no immutable config ID" ;;
esac

[ "$CONFIG_ID" != "$BAD_CONFIG" ] \
  || fail "web image config ID is the known synthetic-map runtime ${BAD_CONFIG} (requested as '$IMAGE')"
for rd in $REPO_DIGESTS; do
  [ "${rd##*@}" != "$BAD_MANIFEST" ] \
    || fail "web image '$IMAGE' resolves to the known synthetic-map manifest ${BAD_MANIFEST}"
done
if [ -n "$REQUESTED_DIGEST" ]; then
  matched=0
  for rd in $REPO_DIGESTS; do
    [ "${rd##*@}" = "$REQUESTED_DIGEST" ] && matched=1
  done
  [ "$matched" -eq 1 ] || fail "local image for '$IMAGE' does not carry the requested manifest digest"
fi

if [ -z "$EXPECTED_PLATFORM" ]; then
  EXPECTED_PLATFORM="$(docker version --format '{{.Server.Os}}/{{.Server.Arch}}' 2>/dev/null)" \
    || fail "cannot determine the docker daemon platform"
fi
[ "$PLATFORM" = "$EXPECTED_PLATFORM" ] \
  || fail "web image platform ${PLATFORM} does not match the runtime platform ${EXPECTED_PLATFORM}"

# --- Baked configuration: copy the bundle out of that exact config ID ----------
WORK="$(mktemp -d)"
CID=""
cleanup() {
  if [ -n "$CID" ]; then docker rm -f "$CID" >/dev/null 2>&1 || true; fi
  rm -rf "$WORK"
}
trap cleanup EXIT
# Created, never started; no network; the entrypoint is never executed.
CID="$(docker create --pull never --network none --entrypoint /parkio-web-map-guard-noop "$CONFIG_ID" 2>/dev/null)" \
  || fail "cannot create an inspection container from ${CONFIG_ID}"
mkdir -p "$WORK/extract"
docker cp "$CID:$WEB_ROOT_IN_IMAGE" - 2>/dev/null | tar -x -C "$WORK/extract" 2>/dev/null \
  || fail "cannot copy ${WEB_ROOT_IN_IMAGE} out of ${CONFIG_ID} (missing or unreadable bundle)"

set +e
verdict="$("$PYTHON" "$ROOT/scripts/lib/web_bundle_map_config.py" "$WORK/extract/$(basename "$WEB_ROOT_IN_IMAGE")")"
verdict_rc=$?
set -e
[ -n "$verdict" ] || fail "bundle classifier produced no verdict for ${CONFIG_ID}"

evidence="$("$PYTHON" - "$IMAGE" "$CONFIG_ID" "$PLATFORM" "$REPO_DIGESTS" "$verdict" "$verdict_rc" <<'PY'
import json, sys
image, config_id, platform, repo_digests, verdict, rc = sys.argv[1:7]
print(json.dumps({
    "guard": "web-map-deploy-guard/2",
    "requestedImage": image,
    "configId": config_id,
    "platform": platform,
    "repoDigests": repo_digests.split(),
    "bundle": json.loads(verdict),
    "result": "PASS" if rc == "0" else "BLOCKED",
}, sort_keys=True))
PY
)"
if [ -n "$EVIDENCE_OUT" ]; then
  printf '%s\n' "$evidence" > "$EVIDENCE_OUT"
fi

reason="$(printf '%s' "$verdict" | "$PYTHON" -c 'import json,sys; v=json.load(sys.stdin); print(v["mapKeyStatus"]+": "+v["reason"])')"
if [ "$verdict_rc" -ne 0 ]; then
  fail "web image ${CONFIG_ID} (${PLATFORM}, requested '$IMAGE'): ${reason}"
fi
fingerprint="$(printf '%s' "$verdict" | "$PYTHON" -c 'import json,sys; print(json.load(sys.stdin)["mapKeyFingerprint"])')"
if [ -n "$EXPECTED_FINGERPRINT" ] && [ "$fingerprint" != "$EXPECTED_FINGERPRINT" ]; then
  fail "web image ${CONFIG_ID}: baked map key fingerprint ${fingerprint} is not the expected release fingerprint ${EXPECTED_FINGERPRINT}"
fi
echo "web-map-deploy-guard: PASS image=${IMAGE} configId=${CONFIG_ID} platform=${PLATFORM} mapKey=PRESENT fingerprint=${fingerprint} fingerprintMatched=$([ -n "$EXPECTED_FINGERPRINT" ] && echo yes || echo not-requested)"
exit 0
