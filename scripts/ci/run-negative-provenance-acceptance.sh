#!/usr/bin/env bash
# Real cosign negative acceptance: controlled unattested digest (not live release pins).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
# shellcheck source=scripts/lib/cosign-pin.env
source scripts/lib/cosign-pin.env
# shellcheck source=scripts/ci/fixtures/provenance-negative-digest.env
source scripts/ci/fixtures/provenance-negative-digest.env

ref="${PARKIO_NEGATIVE_IMAGE}@${PARKIO_NEGATIVE_DIGEST}"
echo "negative_fixture=$ref"
echo "selected_utc=${PARKIO_NEGATIVE_SELECTED_UTC}"

if ! command -v cosign >/dev/null 2>&1; then
  echo "::error::cosign missing" >&2
  exit 2
fi

# Separate registry/existence check — must succeed before attestation verdict.
set +e
docker buildx imagetools inspect "$ref" >/tmp/neg-manifest.out 2>/tmp/neg-manifest.err
man_ec=$?
set -e
if [ "$man_ec" -ne 0 ]; then
  echo "::error::registry/access/existence check failed (not an attestation rejection)" >&2
  cat /tmp/neg-manifest.err /tmp/neg-manifest.out >&2 || true
  exit 1
fi
echo "registry_existence_ok=yes"

set +e
cosign verify-attestation \
  --type "$PARKIO_PROVENANCE_PREDICATE_TYPE" \
  --certificate-oidc-issuer "$PARKIO_PROVENANCE_OIDC_ISSUER" \
  --certificate-identity-regexp "$PARKIO_PROVENANCE_IDENTITY_REGEXP" \
  "$ref" > /tmp/cosign-neg.out 2>&1
ec=$?
set -e
cat /tmp/cosign-neg.out

if [ "$ec" -eq 0 ]; then
  echo "::error::expected verify-attestation to FAIL on controlled unattested digest" >&2
  echo "::error::fixture may be stale — update scripts/ci/fixtures/provenance-negative-digest.env" >&2
  exit 1
fi
if grep -qiE 'UNAUTHORIZED|authentication required|DENIED' /tmp/cosign-neg.out; then
  echo "::error::negative proof failed for registry auth, not missing attestation" >&2
  exit 1
fi
if grep -qiE 'MANIFEST_UNKNOWN|NAME_UNKNOWN|not found|no such host|dial tcp|connection refused|timeout|EOF' /tmp/cosign-neg.out; then
  echo "::error::negative proof failed for missing image/network, not missing attestation" >&2
  exit 1
fi
# Require the specific attestation-miss signal (not arbitrary verify errors).
if ! grep -qiE 'no matching attestations' /tmp/cosign-neg.out; then
  echo "::error::verify-attestation failed without 'no matching attestations' (insufficient proof)" >&2
  exit 1
fi
echo "PASS: real cosign fail-closed on controlled unattested digest (exit $ec)"
