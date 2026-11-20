#!/usr/bin/env bash
# One-shot legacy parking-spot policy reset (operator only).
# Never enable on routine deploy. Prefer dry-run first.
#
# Dry-run (default):
#   PARKIO_PARKING_LEGACY_POLICY_RESET_ENABLED=true \
#   PARKIO_PARKING_LEGACY_POLICY_RESET_DRY_RUN=true \
#   ./scripts/parking/legacy-policy-reset.sh
#
# Execute:
#   PARKIO_PARKING_LEGACY_POLICY_RESET_ENABLED=true \
#   PARKIO_PARKING_LEGACY_POLICY_RESET_DRY_RUN=false \
#   ./scripts/parking/legacy-policy-reset.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

export PARKIO_PARKING_LEGACY_POLICY_RESET_ENABLED="${PARKIO_PARKING_LEGACY_POLICY_RESET_ENABLED:-true}"
export PARKIO_PARKING_LEGACY_POLICY_RESET_DRY_RUN="${PARKIO_PARKING_LEGACY_POLICY_RESET_DRY_RUN:-true}"
export PARKIO_PARKING_LEGACY_POLICY_RESET_TARGET="${PARKIO_PARKING_LEGACY_POLICY_RESET_TARGET:-2026-07-photo-policy-v3-recall}"
export PARKIO_PARKING_LEGACY_POLICY_RESET_BATCH_SIZE="${PARKIO_PARKING_LEGACY_POLICY_RESET_BATCH_SIZE:-500}"

MODE="DRY_RUN"
if [[ "${PARKIO_PARKING_LEGACY_POLICY_RESET_DRY_RUN}" == "false" ]]; then
  MODE="EXECUTE"
fi

echo "Starting parking-service once for legacy policy reset (${MODE})"
echo "  target-policy=${PARKIO_PARKING_LEGACY_POLICY_RESET_TARGET}"
echo "  batch-size=${PARKIO_PARKING_LEGACY_POLICY_RESET_BATCH_SIZE}"
echo "Inspect parking-service logs for 'Legacy policy reset' summary."
echo
echo "Example (docker compose):"
echo "  PARKIO_PARKING_LEGACY_POLICY_RESET_ENABLED=true \\"
echo "  PARKIO_PARKING_LEGACY_POLICY_RESET_DRY_RUN=${PARKIO_PARKING_LEGACY_POLICY_RESET_DRY_RUN} \\"
echo "  docker compose run --rm parking-service"
echo
echo "Or boot the service locally with the env vars above; the ApplicationRunner exits after logging."
