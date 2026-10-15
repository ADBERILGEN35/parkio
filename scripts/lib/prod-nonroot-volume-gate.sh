#!/usr/bin/env bash
# N-05 / #354 / R-05 #414: before a mutating Compose command that includes the Civo non-root overlay,
# require every overlay-affected volume to be COMPLIANT or ABSENT (fresh host) per
# nonroot-volume-migration.sh plan. Read-only: never calls apply/restore.
#
# Sourced by scripts/parkio-prod-compose.sh. Expects ROOT to be set.
#
# ABSENT: named volumes that non-root images initialise on first up are allowed so a
# fresh DR host can bring the stack up. NEEDS_CHOWN / other non-COMPLIANT still block.
# Owner break-glass (default off):
#   PARKIO_SKIP_NONROOT_VOLUME_GATE=I_ACCEPT_UNMIGRATED_VOLUMES
#
# Test override:
#   PARKIO_NONROOT_VOLUME_MIGRATION_SCRIPT=/path/to/stub

# Overlay services that have a ready volume row in scripts/lib/nonroot-volume-map.tsv
# (minio-setup and blackbox-exporter have no volume rows).
PARKIO_NONROOT_OVERLAY_VOLUME_SERVICES=(
  postgres-auth postgres-gateway postgres-user postgres-parking postgres-media
  postgres-gamification postgres-notification postgres-moderation postgres-analytics
  postgres-ai-validation redis minio
)

parkio_nonroot_volume_gate_skip_requested() {
  local v="${PARKIO_SKIP_NONROOT_VOLUME_GATE:-}"
  if [ -z "$v" ]; then
    return 1
  fi
  if [ "$v" = "I_ACCEPT_UNMIGRATED_VOLUMES" ]; then
    echo "WARNING: PARKIO_SKIP_NONROOT_VOLUME_GATE break-glass set — non-root overlay volumes are NOT verified COMPLIANT" >&2
    return 0
  fi
  echo "ERROR: PARKIO_SKIP_NONROOT_VOLUME_GATE='${v}' is not accepted; the non-root volume gate only" >&2
  echo "ERROR: skips for PARKIO_SKIP_NONROOT_VOLUME_GATE=I_ACCEPT_UNMIGRATED_VOLUMES" >&2
  return 2
}

# Resolve Compose project name for volume labels: -p/--project-name, else COMPOSE_PROJECT_NAME, else parkio.
parkio_nonroot_volume_gate_project() {
  local project="${COMPOSE_PROJECT_NAME:-}"
  local prev=""
  local a
  for a in "$@"; do
    case "$prev" in
      -p|--project-name) project="$a"; prev=""; continue ;;
    esac
    case "$a" in
      -p|--project-name) prev="$a" ;;
      --project-name=*) project="${a#--project-name=}"; prev="" ;;
      *) prev="" ;;
    esac
  done
  if [ -z "$project" ]; then
    project=parkio
  fi
  if [[ ! "$project" =~ ^[a-z0-9][a-z0-9_-]*$ ]]; then
    echo "ERROR: non-root volume gate: invalid Compose project name '$project'" >&2
    return 1
  fi
  printf '%s' "$project"
}

# True when the first compose subcommand can create/replace containers.
# Unknown global flags that take a value are skipped as flag+value pairs.
# If the subcommand cannot be identified, fail closed (treat as mutating).
parkio_nonroot_volume_gate_is_mutating() {
  local prev="" a sub=""
  for a in "$@"; do
    if [ -n "$prev" ]; then
      prev=""
      continue
    fi
    case "$a" in
      -f|--file|--env-file|--profile|-p|--project-name|--project-directory|--ansi|--progress)
        prev="$a"
        continue
        ;;
      --file=*|--env-file=*|--profile=*|--project-name=*|--project-directory=*|--ansi=*|--progress=*)
        continue
        ;;
      --*=*)
        continue
        ;;
      --*)
        # Unknown long option without = : assume it takes a following value (e.g. --parallel N)
        prev="$a"
        continue
        ;;
      -*)
        continue
        ;;
      *)
        sub="$a"
        break
        ;;
    esac
  done
  case "$sub" in
    up|create|run) return 0 ;;
    ps|config|images|ls|logs|top|events|port|version|wait|kill|pause|unpause|restart|stop|start|rm|down|pull|push|build|exec|attach|cp|diff|inspect|convert)
      return 1
      ;;
    *)
      # Unidentified / empty / numeric leftover from a misparsed flag → fail closed
      return 0
      ;;
  esac
}

# Statuses allowed through the gate without break-glass.
# ABSENT = volume not created yet (fresh host / DR); non-root images initialise ownership on first up.
parkio_nonroot_volume_gate_status_ok() {
  case "$1" in
    COMPLIANT|ABSENT) return 0 ;;
    *) return 1 ;;
  esac
}

# Run read-only plan and require COMPLIANT or ABSENT for every overlay volume service.
parkio_nonroot_volume_gate_check() {
  local project="$1"
  local script="${PARKIO_NONROOT_VOLUME_MIGRATION_SCRIPT:-$ROOT/scripts/nonroot-volume-migration.sh}"
  if [ ! -x "$script" ] && [ ! -f "$script" ]; then
    echo "ERROR: non-root volume gate: migration script missing: $script" >&2
    return 1
  fi
  local args=(plan --project "$project")
  local s
  for s in "${PARKIO_NONROOT_OVERLAY_VOLUME_SERVICES[@]}"; do
    args+=(--service "$s")
  done
  local plan_out plan_rc=0
  local err_file
  err_file="$(mktemp "${TMPDIR:-/tmp}/parkio-nonroot-volume-gate.XXXXXX.err")"
  chmod 600 "$err_file" 2>/dev/null || true
  plan_out="$(bash "$script" "${args[@]}" 2>"$err_file")" || plan_rc=$?
  if [ "$plan_rc" -ne 0 ]; then
    echo "ERROR: non-root volume gate: plan failed (exit $plan_rc) for project '$project'" >&2
    sed 's/^/ERROR: /' "$err_file" >&2 || true
    rm -f "$err_file"
    return 1
  fi
  rm -f "$err_file"
  local line status svc
  local -A seen=()
  while IFS= read -r line || [ -n "$line" ]; do
    [ -z "$line" ] && continue
    [[ "$line" == service=* ]] || continue
    svc="${line#service=}"
    svc="${svc%% *}"
    svc="${svc%% volume=*}"
    status=""
    if [[ "$line" =~ status=([A-Z_]+) ]]; then
      status="${BASH_REMATCH[1]}"
    fi
    seen["$svc"]=1
    if ! parkio_nonroot_volume_gate_status_ok "${status:-}"; then
      echo "ERROR: non-root volume gate: service=$svc is not COMPLIANT/ABSENT (status=${status:-unknown})" >&2
      echo "ERROR: migrate with scripts/nonroot-volume-migration.sh or set" >&2
      echo "ERROR: PARKIO_SKIP_NONROOT_VOLUME_GATE=I_ACCEPT_UNMIGRATED_VOLUMES (owner break-glass)" >&2
      echo "ERROR: plan line: $line" >&2
      return 1
    fi
  done <<< "$plan_out"
  for s in "${PARKIO_NONROOT_OVERLAY_VOLUME_SERVICES[@]}"; do
    if [ -z "${seen[$s]:-}" ]; then
      echo "ERROR: non-root volume gate: plan omitted required service '$s'" >&2
      return 1
    fi
  done
  echo "non-root volume gate: PASS project=$project (${#PARKIO_NONROOT_OVERLAY_VOLUME_SERVICES[@]} services COMPLIANT or ABSENT)" >&2
  return 0
}

# Entry: call when the non-root overlay is on the compose file list.
parkio_nonroot_volume_gate_maybe() {
  if ! parkio_nonroot_volume_gate_is_mutating "$@"; then
    return 0
  fi
  local skip_rc=0
  parkio_nonroot_volume_gate_skip_requested || skip_rc=$?
  if [ "$skip_rc" -eq 0 ]; then
    return 0
  fi
  if [ "$skip_rc" -eq 2 ]; then
    return 1
  fi
  local project
  project="$(parkio_nonroot_volume_gate_project "$@")" || return 1
  parkio_nonroot_volume_gate_check "$project"
}
