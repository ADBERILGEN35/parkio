#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
source "${SCRIPT_DIR}/lib/safety-guards.sh"
assert_staging_safety

cd "${ROOT}"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-/tmp/gradle-wp062-replay}"
mkdir -p "${GRADLE_USER_HOME}"
./gradlew :services:parking-service:test \
  --tests "com.parkio.parking.decision.audit.DecisionAuditReplayTest" \
  --tests "com.parkio.parking.availability.replay.AvailabilityReplayerTest" \
  --tests "com.parkio.parking.outcome.replay.OutcomeReplayerTest" \
  --tests "com.parkio.parking.infrastructure.persistence.trust.TrustShadowPersistencePostgresIT" \
  --tests "com.parkio.parking.infrastructure.persistence.reward.RewardShadowPersistencePostgresIT" \
  --tests "com.parkio.parking.infrastructure.persistence.fraud.FraudShadowPersistencePostgresIT" \
  --tests "com.parkio.parking.infrastructure.persistence.fraud.FraudShadowMigrationPostgresIT" \
  --tests "com.parkio.parking.infrastructure.persistence.calibration.CalibrationShadowMigrationPostgresIT" \
  --no-daemon -q
echo "OK WP-05 replay regression suite"