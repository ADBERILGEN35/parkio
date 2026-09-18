#!/usr/bin/env bash
# Fail-closed parity check: compose/env/script MinIO defaults must match the
# quay digest pins used by stack-backed CI (backup/runtime/performance/chaos).
# Hub short tags previously broke image pull and caused PA-11 false skips.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
EXPECTED_SERVER='quay.io/minio/minio@sha256:cf3dadcfa1fb0324f43958bad1abba986d53c4ecc04d4d50b46c7dcda28bd3cd'
EXPECTED_MC='quay.io/minio/mc@sha256:a7fe349ef4bd8521fb8497f55c6042871b2ae640607cf99d9bede5e9bdf11727'
FAIL=0

require_contains() {
  local file="$1" needle="$2" label="$3"
  if [[ ! -f "$file" ]]; then
    echo "FAIL: missing file $file ($label)" >&2
    FAIL=1
    return
  fi
  if ! grep -F -q "$needle" "$file"; then
    echo "FAIL: $label — expected digest pin missing in $file" >&2
    echo "      expected: $needle" >&2
    FAIL=1
  else
    echo "OK: $label ($file)"
  fi
}

forbid_hub_short_tag() {
  local file="$1" label="$2"
  if [[ ! -f "$file" ]]; then
    return
  fi
  if grep -E -q 'minio/(minio|mc):RELEASE\.' "$file"; then
    echo "FAIL: $label still references Docker Hub RELEASE short tag in $file" >&2
    grep -n -E 'minio/(minio|mc):RELEASE\.' "$file" >&2 || true
    FAIL=1
  fi
}

echo "Asserting MinIO image pin parity against CI digests…"
require_contains "$ROOT/docker/docker-compose.yml" "$EXPECTED_SERVER" "compose minio default"
require_contains "$ROOT/docker/docker-compose.yml" "$EXPECTED_MC" "compose mc default"
require_contains "$ROOT/docker/.env.example" "$EXPECTED_SERVER" ".env.example MINIO_IMAGE"
require_contains "$ROOT/docker/.env.example" "$EXPECTED_MC" ".env.example MINIO_MC_IMAGE"

for wf in backup-restore-drill.yml runtime-validation.yml performance-smoke.yml chaos-validation.yml; do
  require_contains "$ROOT/.github/workflows/$wf" "$EXPECTED_SERVER" "workflow $wf MINIO_IMAGE"
  require_contains "$ROOT/.github/workflows/$wf" "$EXPECTED_MC" "workflow $wf MINIO_MC_IMAGE"
done

require_contains \
  "$ROOT/services/media-service/src/test/java/com/parkio/media/infrastructure/storage/MediaInfrastructureIntegrationTest.java" \
  "$EXPECTED_SERVER" \
  "Testcontainers MinIO"

for f in \
  scripts/backup-minio.sh \
  scripts/restore-drill-minio.sh \
  scripts/restore-drill-offsite.sh \
  scripts/restore-hosted-beta.sh \
  scripts/lib/backup-common.sh \
  scripts/staging/verify-minio-roundtrip.sh \
  scripts/staging/run-wp062b-restored-stack-verification.sh
do
  require_contains "$ROOT/$f" "$EXPECTED_MC" "script mc default $f"
  forbid_hub_short_tag "$ROOT/$f" "script $f"
done

forbid_hub_short_tag "$ROOT/docker/docker-compose.yml" "compose"
forbid_hub_short_tag "$ROOT/docker/.env.example" ".env.example"

if [[ "$FAIL" -ne 0 ]]; then
  echo "MinIO image pin parity check FAILED." >&2
  exit 1
fi
echo "MinIO image pin parity check PASSED."
