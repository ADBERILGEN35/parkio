#!/usr/bin/env bash
# Production web deploy guard (audit F-05): refuse to start a web image that is
# the known synthetic-map runtime or that bakes a synthetic MapTiler key.
#
# Usage:
#   bash scripts/guard-web-synthetic-map-deploy.sh --image REF [--env-file FILE] \
#     [--pin-file FILE] [--evidence-out FILE] [--expected-platform OS/ARCH] \
#     [--expected-map-key-fingerprint HEX12] [--bind-override-out FILE]
#   bash scripts/guard-web-synthetic-map-deploy.sh --compose-config-json FILE ...
#
# --image is the web image the deploy will actually start (callers resolve it from
# the rendered Compose model; --compose-config-json does that from a file). The
# image must already be present locally. The daemon image ID, any inspect
# Descriptor digest, RepoDigests, and (only when established) the OCI config
# digest are recorded separately. The bundle is copied from the daemon image ID
# and classified. A missing image, bundle or verdict fails closed. Nothing is
# pulled and no provider is contacted. Key values are never printed; only a
# 12-hex SHA-256 fingerprint.
# --expected-map-key-fingerprint (or PARKIO_WEB_EXPECTED_MAP_KEY_FINGERPRINT)
# additionally requires the baked key's fingerprint to equal the supplied one. That
# proves equality to whatever key the fingerprint was computed from - not that the
# fingerprint came from an approved release, nor that MapTiler accepts the key.
#
# --bind-override-out writes a Compose override (use it as the LAST -f) that pins
# services.web to exactly what was verified: the verified repo@sha256 reference
# when one was requested (platform or index digest as written), otherwise the
# daemon image ID; plus pull_policy: never, so no pull or tag move can substitute
# another image. The daemon image ID is not reported as configId unless it is
# established as the configuration digest (classic graphdriver). On a containerd
# snapshotter, inspect .Id is typically the platform manifest digest and is not
# treated as a config digest. The configuration digest is then taken from the
# local image's own OCI manifest (same image that was inspected). If that digest
# cannot be verified, the guard fails closed. A PASS always has an established
# config digest that is not the known-bad configuration.
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
BIND_OUT=""
MODEL_PLATFORM=""
WEB_ROOT_IN_IMAGE="/usr/share/nginx/html"

# Known bad runtime (PR #91 incident): the CI-acceptance archive published as
# the production web image. The requested digest, Descriptor digest and every
# RepoDigest are checked against the known-bad manifest. The known-bad
# configuration digest is checked against an established OCI config digest
# (classic graphdriver image ID, or the config descriptor of the local
# manifest). Unavailable config identity fails closed; the known-bad manifest
# check is the only other verified rejection of that same runtime.
BAD_MANIFEST="sha256:8d9bfca43d577afd62d20f7ffe3fcb2566fbf3bce9dbd758368562aec641487d"
BAD_CONFIG="sha256:985fd8a7684a63ea21cf10cbb36f8f2fc092b7d3f13ccbc8c18311380945f68c"
SYNTHETIC_CLASS="ci-web-build-security-synthetic"

usage() { sed -n '2,/^# Exit 0/p' "$0"; }

while [ "$#" -gt 0 ]; do
  case "$1" in
    --image) IMAGE="${2:-}"; shift 2 ;;
    --compose-config-json) CONFIG_JSON="${2:-}"; shift 2 ;;
    --env-file) ENV_FILE="${2:-}"; shift 2 ;;
    --pin-file) PIN_FILE="${2:-}"; shift 2 ;;
    --evidence-out) EVIDENCE_OUT="${2:-}"; shift 2 ;;
    --expected-platform) EXPECTED_PLATFORM="${2:-}"; shift 2 ;;
    --expected-map-key-fingerprint) EXPECTED_FINGERPRINT="${2:-}"; shift 2 ;;
    --bind-override-out) BIND_OUT="${2:-}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "ERROR: unknown argument '$1'" >&2; usage >&2; exit 2 ;;
  esac
done

fail() {
  echo "web-map-deploy-guard: BLOCKED: $1" >&2
  exit 1
}

# A stale override from an earlier run must never be mistaken for this verdict.
if [ -n "$BIND_OUT" ]; then
  rm -f "$BIND_OUT" || { echo "ERROR: cannot clear $BIND_OUT" >&2; exit 2; }
fi

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
print(web.get("platform") or "")
PY
)" || fail "cannot read services.web.image from the rendered compose config"
  MODEL_PLATFORM="$(printf '%s\n' "$IMAGE" | sed -n 2p)"
  IMAGE="$(printf '%s\n' "$IMAGE" | sed -n 1p)"
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
daemon_id = img.get("Id") or ""
platform = (img.get("Os") or "") + "/" + (img.get("Architecture") or "")
repo_digests = img.get("RepoDigests") or []
descriptor = img.get("Descriptor") or {}
descriptor_digest = descriptor.get("digest") or "" if isinstance(descriptor, dict) else ""
known_manifests = set()
for rd in repo_digests:
    if "@" in rd:
        known_manifests.add(rd.split("@", 1)[1])
    elif isinstance(rd, str) and rd.startswith("sha256:"):
        known_manifests.add(rd)
if descriptor_digest:
    known_manifests.add(descriptor_digest)
config_digest = ""
status = "unverified"
if daemon_id.startswith("sha256:") and daemon_id not in known_manifests:
    config_digest = daemon_id
    status = "established"
print(daemon_id)
print(platform)
print(" ".join(repo_digests))
print(descriptor_digest)
print(config_digest)
print(status)
')" || fail "cannot parse docker image inspect for '$IMAGE'"
DAEMON_IMAGE_ID="$(printf '%s\n' "$identity" | sed -n 1p)"
PLATFORM="$(printf '%s\n' "$identity" | sed -n 2p)"
REPO_DIGESTS="$(printf '%s\n' "$identity" | sed -n 3p)"
DESCRIPTOR_DIGEST="$(printf '%s\n' "$identity" | sed -n 4p)"
CONFIG_DIGEST="$(printf '%s\n' "$identity" | sed -n 5p)"
CONFIG_DIGEST_STATUS="$(printf '%s\n' "$identity" | sed -n 6p)"
case "$DAEMON_IMAGE_ID" in
  sha256:[0-9a-f]*) ;;
  *) fail "web image '$IMAGE' has no daemon image ID" ;;
esac

if [ "$DESCRIPTOR_DIGEST" = "$BAD_MANIFEST" ]; then
  fail "web image '$IMAGE' Descriptor digest is the known synthetic-map runtime ${BAD_MANIFEST}"
fi
for rd in $REPO_DIGESTS; do
  [ "${rd##*@}" != "$BAD_MANIFEST" ] \
    || fail "web image '$IMAGE' resolves to the known synthetic-map manifest ${BAD_MANIFEST}"
done
if [ "$CONFIG_DIGEST_STATUS" != "established" ] || [ -z "$CONFIG_DIGEST" ]; then
  save_dir="$(mktemp -d)"
  chmod 700 "$save_dir"
  extracted=""
  if docker save --output "$save_dir/img.tar" "$IMAGE" 2>/dev/null; then
    extracted="$("$PYTHON" - "$save_dir/img.tar" "$DAEMON_IMAGE_ID" "$DESCRIPTOR_DIGEST" "$REPO_DIGESTS" "$REQUESTED_DIGEST" "$IMAGE" <<'PY'
import hashlib, json, sys, tarfile

tar_path, daemon_id, descriptor, repo_csv, requested, image = sys.argv[1:7]
allowed = set()
for item in (daemon_id, descriptor, requested):
    if item.startswith("sha256:"):
        allowed.add(item)
for rd in repo_csv.split():
    if "@" in rd:
        allowed.add(rd.split("@", 1)[1])
    elif rd.startswith("sha256:"):
        allowed.add(rd)

def digest_hex(value):
    return value[7:] if value.startswith("sha256:") else value

def read_member(archive, name):
    member = archive.extractfile(name)
    if member is None:
        return None
    return member.read()

def is_config_digest(value):
    return isinstance(value, str) and value.startswith("sha256:") and len(value) == 71

try:
    archive = tarfile.open(tar_path, "r")
except Exception:
    sys.exit(3)
try:
    names = archive.getnames()
    name_by_hex = {}
    for name in names:
        base = name.rsplit("/", 1)[-1]
        if len(base) == 64 and all(c in "0123456789abcdef" for c in base):
            name_by_hex[base] = name
    if "index.json" in names:
        try:
            index = json.loads(read_member(archive, "index.json"))
        except Exception:
            index = None
        if isinstance(index, dict):
            for entry in index.get("manifests") or []:
                manifest_digest = entry.get("digest") or ""
                if allowed and manifest_digest not in allowed:
                    continue
                blob_name = name_by_hex.get(digest_hex(manifest_digest))
                if not blob_name:
                    continue
                try:
                    manifest = json.loads(read_member(archive, blob_name))
                except Exception:
                    continue
                config_digest = (manifest.get("config") or {}).get("digest") or ""
                if is_config_digest(config_digest):
                    print(config_digest)
                    sys.exit(0)
    if "manifest.json" in names:
        try:
            docker_manifests = json.loads(read_member(archive, "manifest.json"))
        except Exception:
            docker_manifests = None
        if isinstance(docker_manifests, list) and len(docker_manifests) == 1:
            entry = docker_manifests[0] or {}
            tags = entry.get("RepoTags") or []
            # Saved from the exact inspected reference: tag match, or a
            # single-image digest save with no tags.
            if tags and image not in tags and not any(t.endswith("@" + requested) for t in tags if requested):
                sys.exit(4)
            cfg_name = entry.get("Config") or ""
            if not cfg_name or cfg_name not in names:
                sys.exit(4)
            data = read_member(archive, cfg_name)
            if not data:
                sys.exit(4)
            digest = "sha256:" + hashlib.sha256(data).hexdigest()
            base = cfg_name.rsplit("/", 1)[-1]
            if len(base) == 64 and all(c in "0123456789abcdef" for c in base) and digest != "sha256:" + base:
                sys.exit(4)
            print(digest)
            sys.exit(0)
    sys.exit(4)
finally:
    archive.close()
PY
)" || extracted=""
  fi
  rm -rf "$save_dir"
  if [ -n "$extracted" ]; then
    CONFIG_DIGEST="$extracted"
    CONFIG_DIGEST_STATUS="established"
  fi
fi
if [ "$CONFIG_DIGEST_STATUS" != "established" ] || [ -z "$CONFIG_DIGEST" ]; then
  fail "web image '$IMAGE' has no verified configuration digest; refuse to start it"
fi
if [ "$CONFIG_DIGEST" = "$BAD_CONFIG" ]; then
  fail "web image config digest is the known synthetic-map runtime ${BAD_CONFIG} (requested as '$IMAGE')"
fi
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
# Any platform selection Compose would apply must agree with what was verified.
if [ -n "$MODEL_PLATFORM" ] && [ "$MODEL_PLATFORM" != "$PLATFORM" ]; then
  fail "compose model selects platform ${MODEL_PLATFORM} for web but the verified image is ${PLATFORM}"
fi
if [ -n "${DOCKER_DEFAULT_PLATFORM:-}" ] && [ "$DOCKER_DEFAULT_PLATFORM" != "$PLATFORM" ]; then
  fail "DOCKER_DEFAULT_PLATFORM=${DOCKER_DEFAULT_PLATFORM} differs from the verified image platform ${PLATFORM}"
fi

# --- Baked configuration: copy the bundle out of that exact daemon image ID ---
WORK="$(mktemp -d)"
CID=""
cleanup() {
  if [ -n "$CID" ]; then docker rm -f "$CID" >/dev/null 2>&1 || true; fi
  rm -rf "$WORK"
}
trap cleanup EXIT
# Created, never started; no network; the entrypoint is never executed.
CID="$(docker create --pull never --network none --entrypoint /parkio-web-map-guard-noop "$DAEMON_IMAGE_ID" 2>/dev/null)" \
  || fail "cannot create an inspection container from ${DAEMON_IMAGE_ID}"
mkdir -p "$WORK/extract"
docker cp "$CID:$WEB_ROOT_IN_IMAGE" - 2>/dev/null | tar -x -C "$WORK/extract" 2>/dev/null \
  || fail "cannot copy ${WEB_ROOT_IN_IMAGE} out of ${DAEMON_IMAGE_ID} (missing or unreadable bundle)"

set +e
verdict="$("$PYTHON" "$ROOT/scripts/lib/web_bundle_map_config.py" "$WORK/extract/$(basename "$WEB_ROOT_IN_IMAGE")")"
verdict_rc=$?
set -e
[ -n "$verdict" ] || fail "bundle classifier produced no verdict for ${DAEMON_IMAGE_ID}"

fingerprint="$(printf '%s' "$verdict" | "$PYTHON" -c 'import json,sys; print(json.load(sys.stdin)["mapKeyFingerprint"] or "")')"
reason="$(printf '%s' "$verdict" | "$PYTHON" -c 'import json,sys; v=json.load(sys.stdin); print(v["mapKeyStatus"]+": "+v["reason"])')"
fp_status="not-requested"
if [ -n "$EXPECTED_FINGERPRINT" ]; then
  fp_status="mismatch"
  [ -n "$fingerprint" ] && [ "$fingerprint" = "$EXPECTED_FINGERPRINT" ] && fp_status="matched"
fi
result="PASS"
[ "$verdict_rc" -eq 0 ] || result="BLOCKED"
[ "$fp_status" = "mismatch" ] && result="BLOCKED"
# Immutable deployment selection: a verified digest reference when one was
# requested, otherwise the daemon image ID (not labelled configId unless
# established).
BOUND_IMAGE="$DAEMON_IMAGE_ID"
[ -n "$REQUESTED_DIGEST" ] && BOUND_IMAGE="$IMAGE"

evidence="$("$PYTHON" - "$IMAGE" "$DAEMON_IMAGE_ID" "$DESCRIPTOR_DIGEST" "$CONFIG_DIGEST" "$CONFIG_DIGEST_STATUS" "$PLATFORM" "$REPO_DIGESTS" "$verdict" "$result" "$fp_status" "$BOUND_IMAGE" <<'PY'
import json, sys
(image, daemon_id, descriptor_digest, config_digest, config_status,
 platform, repo_digests, verdict, result, fp_status, bound) = sys.argv[1:12]
payload = {
    "guard": "web-map-deploy-guard/4",
    "requestedImage": image,
    "daemonImageId": daemon_id,
    "descriptorDigest": descriptor_digest or None,
    "configDigest": config_digest or None,
    "configDigestStatus": config_status,
    "platform": platform,
    "repoDigests": repo_digests.split(),
    "bundle": json.loads(verdict),
    "fingerprintCheck": fp_status,
    "boundImage": bound if result == "PASS" else None,
    "result": result,
}
if config_status == "established" and config_digest:
    payload["configId"] = config_digest
print(json.dumps(payload, sort_keys=True))
PY
)"
if [ -n "$EVIDENCE_OUT" ]; then
  printf '%s\n' "$evidence" > "$EVIDENCE_OUT"
fi

if [ "$verdict_rc" -ne 0 ]; then
  fail "web image ${DAEMON_IMAGE_ID} (${PLATFORM}, requested '$IMAGE'): ${reason}"
fi
if [ "$fp_status" = "mismatch" ]; then
  fail "web image ${DAEMON_IMAGE_ID}: baked map key fingerprint ${fingerprint} is not the expected fingerprint ${EXPECTED_FINGERPRINT}"
fi
if [ -n "$BIND_OUT" ]; then
  "$PYTHON" - "$BIND_OUT" "$BOUND_IMAGE" <<'PY' || fail "cannot write the binding override"
import json, os, sys
path, image = sys.argv[1:3]
fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
with os.fdopen(fd, "w") as fh:
    # JSON is valid YAML; Compose merges this last, so it wins for these keys.
    json.dump({"services": {"web": {"image": image, "pull_policy": "never"}}}, fh)
    fh.write("\n")
PY
fi
if [ "$CONFIG_DIGEST_STATUS" = "established" ]; then
  identity_note="configId=${CONFIG_DIGEST}"
else
  identity_note="configDigest=unverified daemonImageId=${DAEMON_IMAGE_ID}"
fi
echo "web-map-deploy-guard: PASS image=${IMAGE} ${identity_note} platform=${PLATFORM} mapKey=PRESENT fingerprint=${fingerprint} fingerprintCheck=${fp_status} bound=${BOUND_IMAGE}"
exit 0
