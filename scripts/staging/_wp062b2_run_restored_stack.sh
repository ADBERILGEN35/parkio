#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
RUN_SUFFIX="$(date -u +%Y%m%d%H%M%S)"
RUN_ID="wp062b2-${RUN_SUFFIX}"
PROJECT="parkio-wp062-b2-${RUN_SUFFIX}"
MARKER="wp062b2_${RUN_SUFFIX}"
EVID="${ROOT}/build/operational-evidence/${RUN_ID}"
mkdir -p "${EVID}/pre-run" "${EVID}/logs"

# Pre-run inventories
docker compose ls > "${EVID}/pre-run/compose-ls.txt" 2>&1 || true
docker ps -a --format '{{.Names}}\t{{.Status}}\t{{.Label "com.docker.compose.project"}}' > "${EVID}/pre-run/docker-ps-a.txt" 2>&1 || true
docker network ls --format '{{.Name}}' > "${EVID}/pre-run/networks.txt" 2>&1 || true
docker volume ls --format '{{.Name}}' > "${EVID}/pre-run/volumes.txt" 2>&1 || true
docker ps -a --filter 'label=com.docker.compose.project=parkio' --format '{{.Names}}' > "${EVID}/pre-run/parkio-containers.txt" 2>&1 || true
docker ps -a --filter 'name=parkio-wp062' --format '{{.Names}}' > "${EVID}/pre-run/wp062-containers.txt" 2>&1 || true
git rev-parse HEAD > "${EVID}/pre-run/HEAD.txt"
git status --porcelain=v1 > "${EVID}/pre-run/git-status-porcelain.txt" || true
printf '%s\n' "${RUN_ID}" > "${EVID}/pre-run/planned-run-id.txt"

export COMPOSE_PROJECT_NAME="${PROJECT}"
export PARKIO_WP062B_RST_MARKER="${MARKER}"
export PARKIO_EVIDENCE_DIR="${EVID}"
export PARKIO_EVIDENCE_RUN_ID="${RUN_ID}"
export PARKIO_STAGING_ALLOW_DESTRUCTIVE=yes
export PARKIO_STAGING_ISOLATION_MARKER="wp062b2-isolated-${RUN_SUFFIX}"
export PARKIO_ENVIRONMENT_TYPE=STAGING_LOCAL
export PARKIO_WP062B_EXECUTION_CLASS=LOCAL_REPRESENTATIVE
export PARKIO_GATEWAY_URL="http://127.0.0.1:18080"
# Ensure JWT material path if required by orchestrator defaults
export PARKIO_ENV_FILE="${ROOT}/docker/.env"

echo "LAUNCH runId=${RUN_ID} project=${PROJECT} marker=${MARKER} evid=${EVID}"
set +e
bash scripts/staging/run-wp062b-restored-stack-verification.sh
RC=$?
set -e
echo "ORCHESTRATOR_EXIT=${RC}" | tee "${EVID}/orchestrator-exit.txt"

# Post-run live cleanup inventory
mkdir -p "${EVID}/post-run"
docker compose ls > "${EVID}/post-run/compose-ls.txt" 2>&1 || true
docker ps -a --format '{{.Names}}\t{{.Status}}\t{{.Label "com.docker.compose.project"}}' > "${EVID}/post-run/docker-ps-a.txt" 2>&1 || true
docker network ls --format '{{.Name}}' > "${EVID}/post-run/networks.txt" 2>&1 || true
docker volume ls --format '{{.Name}}' > "${EVID}/post-run/volumes.txt" 2>&1 || true
docker ps -a --filter 'label=com.docker.compose.project=parkio' --format '{{.Names}}' > "${EVID}/post-run/parkio-containers.txt" 2>&1 || true
docker ps -a --filter 'name=parkio-wp062' --format '{{.Names}}' > "${EVID}/post-run/wp062-containers.txt" 2>&1 || true
ss -ltn 2>/dev/null | grep -E ':(1808|1900|16379|19092|1543|13310)' > "${EVID}/post-run/ports-listen.txt" || true

# Emit simple live revalidation JSON via python
python3 - <<PY
import json, os
from pathlib import Path
evid=Path(os.environ["PARKIO_EVIDENCE_DIR"])
pre_p=set((evid/"pre-run"/"parkio-containers.txt").read_text().splitlines())
post_p=set((evid/"post-run"/"parkio-containers.txt").read_text().splitlines())
wp062= [l for l in (evid/"post-run"/"wp062-containers.txt").read_text().splitlines() if l.strip()]
nets=[l for l in (evid/"post-run"/"networks.txt").read_text().splitlines() if "wp062" in l.lower()]
report={
  "runId": os.environ.get("PARKIO_EVIDENCE_RUN_ID"),
  "status": "PASSED" if (not wp062 and pre_p==post_p) else "FAILED",
  "developerParkioUnchanged": pre_p==post_p,
  "preParkioCount": len(pre_p),
  "postParkioCount": len(post_p),
  "remainingWp062Containers": wp062,
  "remainingWp062Networks": nets,
  "orchestratorExitCode": int(Path(evid/"orchestrator-exit.txt").read_text().split("=")[-1]),
}
(evid/"cleanup-live-revalidation.json").write_text(json.dumps(report, indent=2)+"\n")
print(json.dumps(report, indent=2))
PY

echo "FINAL_RUN_ID=${RUN_ID}"
echo "EVID=${EVID}"
exit ${RC}