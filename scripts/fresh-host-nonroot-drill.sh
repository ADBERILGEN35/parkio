#!/usr/bin/env bash
# Y-06 / #440: disposable-Docker proof of fresh-host non-root bring-up for every Civo overlay
# volume service. Records one line per service: service=… plan=… bringup=… owner=… health=…
# Needs Docker and pulls pinned images from docker/.env.azure-hosted-beta.example.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
ENV_EXAMPLE="$ROOT/docker/.env.azure-hosted-beta.example"
NONROOT_OVERLAY="$ROOT/docker/docker-compose.civo-nonroot.yml"
MAP="$ROOT/scripts/lib/nonroot-volume-map.tsv"
HELPER="busybox@sha256:fd7dc98638c8e305f4dc34e979f1c0fdfdcaeb0fbf8fcff77ae834b6da3d7e6e"
PROJECT="parkio-y06-$(date +%s)-$RANDOM"
EVIDENCE="$(mktemp -d "${TMPDIR:-/tmp}/parkio-y06-evidence.XXXXXX")"
RESULTS="${PARKIO_Y06_DRILL_RESULTS:-$ROOT/build/y06-fresh-host-nonroot-drill.txt}"
COMPOSE=(docker compose -p "$PROJECT" --env-file "$ENV_EXAMPLE" -f "$ROOT/docker/docker-compose.yml" -f "$NONROOT_OVERLAY")

SERVICES=(
  postgres-auth postgres-gateway postgres-user postgres-parking postgres-media
  postgres-gamification postgres-notification postgres-moderation postgres-analytics
  postgres-ai-validation redis minio
)

declare -A VOLUME_KEY USER_SPEC HEALTH_TIMEOUT
while IFS=$'\t' read -r svc key owner _readiness absent_bringup _note; do
  svc="${svc//$'\r'/}"; key="${key//$'\r'/}"; owner="${owner//$'\r'/}"
  case "$svc" in ''|'#'*) continue ;; esac
  VOLUME_KEY["$svc"]="$key"
  USER_SPEC["$svc"]="${owner%:*}"
  case "$svc" in
    postgres-parking) HEALTH_TIMEOUT["$svc"]=180 ;;
    minio) HEALTH_TIMEOUT["$svc"]=90 ;;
    redis) HEALTH_TIMEOUT["$svc"]=60 ;;
    *) HEALTH_TIMEOUT["$svc"]=120 ;;
  esac
done < "$MAP"

log() { printf '%s\n' "$*" | tee -a "$RESULTS"; }

cleanup_project() {
  "${COMPOSE[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
}

trap 'cleanup_project; rm -rf "$EVIDENCE"' EXIT

mkdir -p "$(dirname "$RESULTS")"
: >"$RESULTS"
log "# Y-06 fresh-host non-root drill project=$PROJECT $(date -u +%Y-%m-%dT%H:%M:%SZ)"

if ! docker info >/dev/null 2>&1; then
  log "ERROR drill=SKIP reason=docker_unavailable"
  exit 1
fi

wait_healthy() {
  local svc="$1" timeout="${2:-120}" i=0 cid h
  while [ "$i" -lt "$timeout" ]; do
    cid="$("${COMPOSE[@]}" ps -q "$svc" 2>/dev/null | head -n1 || true)"
    if [ -n "$cid" ]; then
      h="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$cid" 2>/dev/null || true)"
      case "$h" in
        healthy) return 0 ;;
        running) return 0 ;;
        unhealthy) return 1 ;;
        exited) return 1 ;;
      esac
    fi
    sleep 2
    i=$((i + 2))
  done
  return 1
}

# The production gate plans every overlay volume; minio ABSENT is refused until init-absent runs.
ensure_minio_gate_ready() {
  local st
  st="$(plan_status minio)"
  [ "$st" = COMPLIANT ] && return 0
  [ "$st" = ABSENT ] || return 1
  bash "$ROOT/scripts/nonroot-volume-migration.sh" init-absent \
    --project "$PROJECT" --service minio --confirm-project "$PROJECT" --evidence-dir "$EVIDENCE/minio-gate"
}

volume_owner() {
  local key="$1" vol
  vol="$(docker volume ls -q --filter "label=com.docker.compose.project=$PROJECT" \
    --filter "label=com.docker.compose.volume=$key" | head -n1)"
  [ -n "$vol" ] || { echo "missing"; return; }
  docker run --rm --network none -v "$vol:/v:ro" --entrypoint stat "$HELPER" -c '%u:%g' /v 2>/dev/null || echo "unknown"
}

plan_status() {
  local svc="$1"
  bash "$ROOT/scripts/nonroot-volume-migration.sh" plan --project "$PROJECT" --service "$svc" 2>/dev/null \
    | sed -n 's/.*status=\([A-Z_]*\).*/\1/p' | head -n1
}

drill_service() {
  local svc="$1"
  local key="${VOLUME_KEY[$svc]:-}"
  local plan bringup owner health rc=0

  cleanup_project
  plan="$(plan_status "$svc")"
  [ -n "$plan" ] || plan=UNKNOWN

  if [ "$svc" = minio ] && [ "$plan" = ABSENT ]; then
    if ! bash "$ROOT/scripts/nonroot-volume-migration.sh" init-absent \
      --project "$PROJECT" --service minio --confirm-project "$PROJECT" --evidence-dir "$EVIDENCE/$svc" \
      >/dev/null 2>&1; then
      log "service=$svc plan=$plan bringup=SKIP owner=n/a health=FAILED reason=init-absent_failed"
      return 1
    fi
    plan="$(plan_status "$svc")"
  elif [ "$svc" != minio ]; then
    ensure_minio_gate_ready || {
      log "service=$svc plan=$plan bringup=SKIP owner=n/a health=FAILED reason=minio_gate_prep_failed"
      return 1
    }
  fi

  if ! PARKIO_ENV_FILE="$ENV_EXAMPLE" \
    PARKIO_SKIP_WEB_MAP_GUARD=I_ACCEPT_UNVERIFIED_WEB_IMAGE \
    PARKIO_SKIP_WEB_CONF_D_CHECK=I_ACCEPT_UNCHECKED_WEB_CONF_D \
    COMPOSE_PROJECT_NAME="$PROJECT" \
    bash "$ROOT/scripts/parkio-prod-compose.sh" up -d --no-build --no-deps "$svc" >/dev/null 2>&1; then
    log "service=$svc plan=$plan bringup=FAILED owner=$(volume_owner "$key") health=FAILED reason=compose_up"
    return 1
  fi
  bringup=OK

  if wait_healthy "$svc" "${HEALTH_TIMEOUT[$svc]:-120}"; then
    health=healthy
  else
    health=unhealthy
    rc=1
  fi
  owner="$(volume_owner "$key")"
  log "service=$svc plan=$plan bringup=$bringup owner=$owner health=$health uid=${USER_SPEC[$svc]:-}"
  return "$rc"
}

failed=0
for svc in "${SERVICES[@]}"; do
  drill_service "$svc" || failed=$((failed + 1))
done

log "# summary failed_services=$failed project=$PROJECT"
[ "$failed" -eq 0 ]
