#!/usr/bin/env bash
# Y-06 / #440: disposable-Docker proof of fresh-host non-root bring-up for every Civo overlay
# volume service. Records one line per service: service=… plan=… bringup=… owner=… health=…
# Needs Docker and pulls pinned images from docker/.env.azure-hosted-beta.example.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
ENV_EXAMPLE="$ROOT/docker/.env.azure-hosted-beta.example"
MAP="$ROOT/scripts/lib/nonroot-volume-map.tsv"
HELPER="busybox@sha256:fd7dc98638c8e305f4dc34e979f1c0fdfdcaeb0fbf8fcff77ae834b6da3d7e6e"
PROJECT="parkio-y06-$(date +%s)-$RANDOM"
EVIDENCE="$(mktemp -d "${TMPDIR:-/tmp}/parkio-y06-evidence.XXXXXX")"
RESULTS="${PARKIO_Y06_DRILL_RESULTS:-$ROOT/build/y06-fresh-host-nonroot-drill.txt}"

SERVICES=(
  postgres-auth postgres-gateway postgres-user postgres-parking postgres-media
  postgres-gamification postgres-notification postgres-moderation postgres-analytics
  postgres-ai-validation redis minio
)

declare -A VOLUME_KEY USER_SPEC HEALTH_TIMEOUT ABSENT_BRINGUP TARGET_OWNER
while IFS=$'\t' read -r svc key owner _readiness absent_bringup _note; do
  svc="${svc//$'\r'/}"; key="${key//$'\r'/}"; owner="${owner//$'\r'/}"
  absent_bringup="${absent_bringup//$'\r'/}"
  case "$svc" in ''|'#'*) continue ;; esac
  VOLUME_KEY["$svc"]="$key"
  USER_SPEC["$svc"]="${owner%:*}"
  TARGET_OWNER["$svc"]="$owner"
  ABSENT_BRINGUP["$svc"]="$absent_bringup"
  case "$svc" in
    postgres-parking) HEALTH_TIMEOUT["$svc"]=180 ;;
    minio) HEALTH_TIMEOUT["$svc"]=90 ;;
    redis) HEALTH_TIMEOUT["$svc"]=60 ;;
    *) HEALTH_TIMEOUT["$svc"]=120 ;;
  esac
done < "$MAP"

PROD_FILES=()
while IFS= read -r line || [ -n "$line" ]; do
  line="${line//$'\r'/}"
  [[ -z "$line" || "$line" =~ ^[[:space:]]*# ]] && continue
  PROD_FILES+=("$ROOT/$line")
done < "$ROOT/docker/compose.production.files"

OVERLAY=(docker compose -p "$PROJECT" --env-file "$ENV_EXAMPLE")
for f in "${PROD_FILES[@]}"; do
  OVERLAY+=(-f "$f")
done
OVERLAY+=(-f "$ROOT/docker/docker-compose.civo-alertmanager.yml" -f "$ROOT/docker/docker-compose.civo-nonroot.yml")

COMPOSE_ACTIVE=("${OVERLAY[@]}")

log() { printf '%s\n' "$*" | tee -a "$RESULTS"; }

cleanup_project() {
  "${OVERLAY[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
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
    cid="$("${COMPOSE_ACTIVE[@]}" ps -q "$svc" 2>/dev/null | head -n1 || true)"
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

ensure_minio_gate_ready() {
  local st
  st="$(plan_status minio)"
  [ "$st" = COMPLIANT ] && return 0
  [ "$st" = ABSENT ] || return 1
  bash "$ROOT/scripts/nonroot-volume-migration.sh" init-absent \
    --project "$PROJECT" --service minio --confirm-project "$PROJECT" --evidence-dir "$EVIDENCE/minio-gate"
}

chown_empty_labelled_volume() { # key owner
  local key="$1" owner="$2" vol="${PROJECT}_${key}"
  docker volume create \
    --label "com.docker.compose.project=$PROJECT" \
    --label "com.docker.compose.volume=$key" \
    "$vol" >/dev/null
  docker run --rm --network none --read-only --cap-drop ALL --cap-add CHOWN --cap-add DAC_READ_SEARCH \
    -v "$vol:/v" --entrypoint chown "$HELPER" -R -h "$owner" /v
}

bootstrap_absent() {
  local svc="$1" st="${ABSENT_BRINGUP[$svc]:-}" plan key owner
  plan="$(plan_status "$svc")"
  [ "$plan" = ABSENT ] || return 0
  key="${VOLUME_KEY[$svc]:-}"
  owner="${TARGET_OWNER[$svc]:-}"
  case "$st" in
    self-init)
      # Overlay-only fresh path: seed an empty labelled volume at the target owner (same outcome as
      # root-start init, without pulling the full production stack in this drill).
      chown_empty_labelled_volume "$key" "$owner"
      ;;
    init-chown)
      bash "$ROOT/scripts/nonroot-volume-migration.sh" init-absent \
        --project "$PROJECT" --service "$svc" --confirm-project "$PROJECT" --evidence-dir "$EVIDENCE/$svc-init"
      ;;
    *) return 1 ;;
  esac
}

drill_service() {
  local svc="$1"
  local key="${VOLUME_KEY[$svc]:-}"
  local plan bringup owner health rc=0 bootstrap=skipped

  cleanup_project
  plan="$(plan_status "$svc")"
  [ -n "$plan" ] || plan=UNKNOWN

  if [ "$svc" != minio ]; then
    ensure_minio_gate_ready || {
      log "service=$svc plan=$plan bringup=SKIP owner=n/a health=FAILED reason=minio_gate_prep_failed"
      return 1
    }
  fi

  if [ "$plan" = ABSENT ]; then
    bootstrap="${ABSENT_BRINGUP[$svc]:-}"
    if ! bootstrap_absent "$svc"; then
      log "service=$svc plan=$plan bringup=SKIP owner=n/a health=FAILED reason=bootstrap_failed bootstrap=$bootstrap"
      return 1
    fi
    plan="$(plan_status "$svc")"
  fi

  # Same gate the production wrapper runs before a mutating command with the non-root overlay.
  # Bring-up uses the merged overlay model directly (disposable CI; no web/provenance guards).
  # shellcheck source=lib/prod-nonroot-volume-gate.sh
  source "$ROOT/scripts/lib/prod-nonroot-volume-gate.sh"
  if ! parkio_nonroot_volume_gate_check "$PROJECT" >"$EVIDENCE/last-gate.out" 2>"$EVIDENCE/last-gate.err"; then
    log "service=$svc plan=$plan bringup=FAILED owner=$(volume_owner "$key") health=FAILED reason=volume_gate bootstrap=$bootstrap"
    tail -n 3 "$EVIDENCE/last-gate.err" >>"$RESULTS" 2>/dev/null || true
    return 1
  fi
  if ! "${OVERLAY[@]}" up -d --no-build --no-deps --pull missing "$svc" \
    >"$EVIDENCE/last-up.out" 2>"$EVIDENCE/last-up.err"; then
    log "service=$svc plan=$plan bringup=FAILED owner=$(volume_owner "$key") health=FAILED reason=compose_up bootstrap=$bootstrap"
    tail -n 5 "$EVIDENCE/last-up.err" >>"$RESULTS" 2>/dev/null || true
    return 1
  fi
  bringup=OK
  COMPOSE_ACTIVE=("${OVERLAY[@]}")

  if wait_healthy "$svc" "${HEALTH_TIMEOUT[$svc]:-120}"; then
    health=healthy
  else
    health=unhealthy
    rc=1
  fi
  owner="$(volume_owner "$key")"
  log "service=$svc plan=$plan bringup=$bringup owner=$owner health=$health uid=${USER_SPEC[$svc]:-} bootstrap=$bootstrap"
  return "$rc"
}

failed=0
for svc in "${SERVICES[@]}"; do
  drill_service "$svc" || failed=$((failed + 1))
done

log "# summary failed_services=$failed project=$PROJECT"
[ "$failed" -eq 0 ]
