#!/usr/bin/env bash
# PP-01B-SPIKE-02 Mode A — local PostGIS runtime parity orchestration.
# Does NOT provision Azure, touch hosted-beta, or start SPIKE-03.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
EVIDENCE_ROOT="${PARKIO_SPIKE02_EVIDENCE_DIR:-$ROOT/deploy-artifacts/pp-01b-spike-02}"
RUN_ID="modea-$(date -u +%Y%m%dT%H%M%SZ)-$$"
WORKDIR="$EVIDENCE_ROOT/$RUN_ID"
mkdir -p "$WORKDIR"

BASELINE_IMAGE="${PARKIO_POSTGIS_BASELINE_IMAGE:-postgis/postgis:16-3.4}"
NEWER_IMAGE="${PARKIO_POSTGIS_NEWER_IMAGE:-imresamu/postgis:16-3.6.1-bookworm}"

cleanup() {
  local code=$?
  echo "cleanup_exit=$code" | tee -a "$WORKDIR/cleanup.log"
  docker ps -aq --filter "label=parkio.pp01b.spike02=$RUN_ID" | xargs -r docker rm -f >/dev/null 2>&1 || true
  docker volume ls -q --filter "label=parkio.pp01b.spike02=$RUN_ID" | xargs -r docker volume rm >/dev/null 2>&1 || true
  docker network ls -q --filter "label=parkio.pp01b.spike02=$RUN_ID" | xargs -r docker network rm >/dev/null 2>&1 || true
  echo "cleanup_complete=true" | tee -a "$WORKDIR/cleanup.log"
  exit "$code"
}
trap cleanup EXIT

{
  echo "git_sha=$(git -C "$ROOT" rev-parse HEAD)"
  echo "timestamp_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "host_arch=$(uname -m 2>/dev/null || echo unknown)"
  echo "docker_version=$(docker version --format '{{.Server.Version}}' 2>/dev/null || echo unavailable)"
} | tee "$WORKDIR/preflight.txt"

record_image() {
  local label="$1"
  local image="$2"
  local digest
  digest="$(docker image inspect "$image" --format '{{index .RepoDigests 0}}')"
  echo "$label.image=$image" | tee -a "$WORKDIR/images.txt"
  echo "$label.digest=$digest" | tee -a "$WORKDIR/images.txt"
  echo "$label.arch=$(docker image inspect "$image" --format '{{.Architecture}}')" | tee -a "$WORKDIR/images.txt"
}

docker pull "$BASELINE_IMAGE"
docker pull "$NEWER_IMAGE"
record_image baseline "$BASELINE_IMAGE"
record_image newer "$NEWER_IMAGE"

probe_versions() {
  local label="$1"
  local image="$2"
  local cname="pp01b-s02-${label}-${RUN_ID}"
  docker run -d --name "$cname" \
    --label "parkio.pp01b.spike02=$RUN_ID" \
    -e POSTGRES_PASSWORD=parkio -e POSTGRES_USER=parkio -e POSTGRES_DB=parkio_parking \
    "$image" >/dev/null
  for _ in $(seq 1 60); do
    if docker exec "$cname" pg_isready -U parkio -d parkio_parking >/dev/null 2>&1; then
      sleep 2
      break
    fi
    sleep 1
  done
  docker exec "$cname" psql -U parkio -d parkio_parking -v ON_ERROR_STOP=1 -c "CREATE EXTENSION IF NOT EXISTS postgis;" >/dev/null
  {
    echo "=== $label ==="
    docker exec "$cname" psql -U parkio -d parkio_parking -tAc "SELECT version();"
    docker exec "$cname" psql -U parkio -d parkio_parking -tAc "SELECT PostGIS_Full_Version();"
    docker exec "$cname" psql -U parkio -d parkio_parking -tAc "SELECT extversion FROM pg_extension WHERE extname='postgis';"
  } | tee "$WORKDIR/versions-${label}.txt"
  docker rm -f "$cname" >/dev/null
}

probe_versions baseline "$BASELINE_IMAGE"
probe_versions newer "$NEWER_IMAGE"

export PARKIO_SPIKE02_EVIDENCE_DIR="$WORKDIR"
cd "$ROOT"

run_its() {
  local label="$1"
  local image="$2"
  local out="$WORKDIR/gradle-${label}.txt"
  echo "Running integrationTest against $image" | tee -a "$out"
  set +e
  ./gradlew :services:parking-service:integrationTest \
    -Pparkio.integrationTest.requireDocker=true \
    -Dparkio.postgis.image="$image" \
    --no-daemon 2>&1 | tee -a "$out"
  local rc=${PIPESTATUS[0]}
  set -e
  echo "exit_code=$rc" | tee -a "$out"
  return "$rc"
}

BASE_RC=0
NEW_RC=0
run_its baseline "$BASELINE_IMAGE" || BASE_RC=$?
run_its newer "$NEWER_IMAGE" || NEW_RC=$?

{
  echo "baseline_exit=$BASE_RC"
  echo "newer_exit=$NEW_RC"
  if [[ "$BASE_RC" -eq 0 && "$NEW_RC" -eq 0 ]]; then
    echo "mode_a_decision=PASS_OR_NOTES_PENDING_DOC_REVIEW"
  else
    echo "mode_a_decision=HOLD_OR_FAIL"
  fi
} | tee "$WORKDIR/summary.txt"

echo "Evidence written under $WORKDIR"
exit $(( BASE_RC != 0 ? BASE_RC : NEW_RC ))