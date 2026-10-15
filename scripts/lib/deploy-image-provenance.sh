#!/usr/bin/env bash
# Deploy image provenance gate (N-17 / #378). Source, do not execute.
#
# parkio_deploy_image_provenance_check MODEL_JSON
#   Fail-closed before `compose up` when any service image is mutable (no @sha256:),
#   missing from the local store when pull_policy is never, has build+image without an
#   explicit pull_policy, or fails the approved signer/attestation policy.
#
# Policy (owner-approved trust roots for Parkio application images):
#   - Every service image must be digest-pinned (@sha256:<64 hex>).
#   - Services that also declare build must set an explicit pull_policy.
#   - Application services (name *-service) require cosign attestation from
#     https://token.actions.githubusercontent.com with identity matching
#     PARKIO_PROVENANCE_IDENTITY_REGEXP.
#   - Infrastructure and mirrored images (postgres, redis, minio, ...) are digest-only.
#   - Web is skipped here: the stronger map guard owns verification + pull_policy: never.
#
# Break-glass (default off):
#   PARKIO_SKIP_IMAGE_PROVENANCE=I_ACCEPT_UNVERIFIED_IMAGE_PROVENANCE
#
# Cosign is invoked as `cosign` on PATH so tests can stub it.

PARKIO_IMAGE_PROVENANCE_BREAK_GLASS_TOKEN="I_ACCEPT_UNVERIFIED_IMAGE_PROVENANCE"
PARKIO_PROVENANCE_OIDC_ISSUER="${PARKIO_PROVENANCE_OIDC_ISSUER:-https://token.actions.githubusercontent.com}"
PARKIO_PROVENANCE_IDENTITY_REGEXP="${PARKIO_PROVENANCE_IDENTITY_REGEXP:-https://github.com/ADBERILGEN35/parkio/.github/workflows/.*}"

parkio_deploy_image_provenance_skip_requested() {
  local v="${PARKIO_SKIP_IMAGE_PROVENANCE:-}"
  if [ -z "$v" ]; then
    return 1
  fi
  if [ "$v" = "$PARKIO_IMAGE_PROVENANCE_BREAK_GLASS_TOKEN" ]; then
    return 0
  fi
  echo "ERROR: image provenance: invalid PARKIO_SKIP_IMAGE_PROVENANCE (refusing)" >&2
  return 2
}

parkio_deploy_image_provenance_check() {
  local model_json="${1:?model json path required}"
  if [ ! -f "$model_json" ]; then
    echo "ERROR: image provenance: model not found: $model_json" >&2
    return 1
  fi

  parkio_deploy_image_provenance_skip_requested
  local skip_rc=$?
  if [ "$skip_rc" -eq 0 ]; then
    echo "WARN: image provenance skipped via break-glass" >&2
    return 0
  fi
  if [ "$skip_rc" -eq 2 ]; then
    return 1
  fi

  PARKIO_PROVENANCE_OIDC_ISSUER="$PARKIO_PROVENANCE_OIDC_ISSUER" \
  PARKIO_PROVENANCE_IDENTITY_REGEXP="$PARKIO_PROVENANCE_IDENTITY_REGEXP" \
  python3 - "$model_json" <<'PY'
import json, os, re, subprocess, sys

model_path = sys.argv[1]
data = json.load(open(model_path, encoding="utf-8"))
services = data.get("services") or {}
oidc = os.environ["PARKIO_PROVENANCE_OIDC_ISSUER"]
ident = os.environ["PARKIO_PROVENANCE_IDENTITY_REGEXP"]
digest_re = re.compile(r"@sha256:[0-9a-f]{64}$", re.IGNORECASE)
errors = []

def has_build(svc):
    return bool(svc.get("build"))

def pull_policy(svc):
    return (svc.get("pull_policy") or svc.get("pullPolicy") or "").strip().lower()

def needs_attestation(name: str) -> bool:
    return name.endswith("-service")

for name, svc in sorted(services.items()):
    if not isinstance(svc, dict):
        continue
    # Web is owned by the stronger map guard (digest bind + pull_policy: never).
    # Provenance must not replace or weaken that path; skip services.web here.
    if name == "web":
        continue
    image = (svc.get("image") or "").strip()
    if not image:
        if has_build(svc):
            if not pull_policy(svc):
                errors.append(f"{name}: build without image and without explicit pull_policy")
            continue
        errors.append(f"{name}: missing image")
        continue
    if not digest_re.search(image):
        errors.append(f"{name}: mutable image (not digest-pinned): {image}")
        continue
    if has_build(svc) and not pull_policy(svc):
        errors.append(f"{name}: build+image without explicit pull_policy")
        continue
    pp = pull_policy(svc)
    if pp == "never":
        r = subprocess.run(
            ["docker", "image", "inspect", image],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        if r.returncode != 0:
            errors.append(f"{name}: image missing locally with pull_policy=never: {image}")
            continue
    if needs_attestation(name):
        cmd = [
            "cosign", "verify-attestation",
            "--type", "https://slsa.dev/provenance/v1",
            "--certificate-oidc-issuer", oidc,
            "--certificate-identity-regexp", ident,
            image,
        ]
        r = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        if r.returncode != 0:
            detail = (r.stderr or r.stdout or "cosign failed").strip().splitlines()
            detail = detail[-1] if detail else "cosign failed"
            errors.append(f"{name}: attestation/identity failed: {detail}")

if errors:
    for e in errors:
        print(f"ERROR: image provenance: {e}", file=sys.stderr)
    sys.exit(1)
print(f"image provenance: ok ({len(services)} services)")
sys.exit(0)
PY
}
