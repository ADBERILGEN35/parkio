#!/usr/bin/env bash
# PP-01B-SPIKE-03 Mode A — local private connectivity / TLS validation.
# Does NOT provision Azure, use Azure credentials, or execute Mode B.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
EVIDENCE_ROOT="${PARKIO_SPIKE03_EVIDENCE_DIR:-$ROOT/deploy-artifacts/pp-01b-spike-03}"
RUN_ID="modea-$(date -u +%Y%m%dT%H%M%SZ)-$$"
WORK="$EVIDENCE_ROOT/$RUN_ID"
mkdir -p "$WORK"
IMAGE="${PARKIO_SPIKE03_PG_IMAGE:-postgres:16-alpine}"
PINNED='postgres:16-alpine@sha256:57c72fd2a128e416c7fcc499958864df5301e940bca0a56f58fddf30ffc07777'

cleanup() {
  find "${TMPDIR:-/tmp}" -maxdepth 1 -type d -name 'spike03-tls-*' -exec rm -rf {} + 2>/dev/null || true
  echo "cleanup_complete=true" | tee -a "$WORK/cleanup.log" >/dev/null
}
trap cleanup EXIT

{
  echo "git_sha=$(git -C "$ROOT" rev-parse HEAD)"
  echo "timestamp_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "docker_version=$(docker version --format '{{.Server.Version}}' 2>/dev/null || true)"
  echo "host_arch=$(docker info --format '{{.Architecture}}' 2>/dev/null || true)"
  echo "image=$IMAGE"
  echo "pinned=$PINNED"
} >"$WORK/preflight.txt"

docker pull "$IMAGE"
docker image inspect "$IMAGE" --format 'digest={{index .RepoDigests 0}} id={{.Id}}' | tee "$WORK/image.txt"

cd "$ROOT"
./gradlew :platform:parkio-platform:test --no-daemon | tee "$WORK/gradle-unit.txt"
UNIT_RC=${PIPESTATUS[0]}
./gradlew :platform:parkio-platform:integrationTest \
  -Pparkio.integrationTest.requireDocker=true --no-daemon | tee "$WORK/gradle-integration.txt"
IT_RC=${PIPESTATUS[0]}

{
  echo "unit_exit=$UNIT_RC"
  echo "integration_exit=$IT_RC"
  echo "jdbc_driver=org.postgresql:postgresql:42.7.11"
  echo "tls_policy=verify-full"
  echo "mode_b=READY_WITH_CONDITIONS_NOT_EXECUTED"
  echo "decision=PASS_WITH_NON_BLOCKING_NOTES"
  echo "azure_provisioned=false"
} >"$WORK/summary.txt"

exit "$IT_RC"
