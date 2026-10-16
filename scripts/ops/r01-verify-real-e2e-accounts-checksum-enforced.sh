#!/usr/bin/env bash
# R-01 read-only host helper: discover live env/compose, verify target script
# checksum, then run scripts/ops/verify-real-e2e-accounts.sh.
#
# This file does NOT embed its own SHA-256 (verified by the operator command
# before bash executes it). It enforces the TARGET script checksum only.
#
# Prints no env values, passwords, hashes of credentials, or SQL secrets.
# Does not modify /opt/parkio trees; refuses to overwrite on mismatch.
set -euo pipefail

# Target: scripts/ops/verify-real-e2e-accounts.sh @ api blob 4d5c10e83166aa174800ec788f85a7edde73729b
EXPECTED_TARGET_SHA256='4d3db3f28acd223f22f461b9b3f0df112a4472ba123caf2bf98bf7588fe53c8a'

ROOT="${PARKIO_ROOT:-/opt/parkio}"
TARGET_REL='scripts/ops/verify-real-e2e-accounts.sh'
TARGET="$ROOT/$TARGET_REL"

echo "== R-01 checksum-enforced verify (read-only) =="
echo "host=$(hostname) utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
echo "parkio_root=$ROOT"
echo "NOTE: helper self-checksum is enforced by the operator wrapper before this script runs."
echo "NOTE: EXPECTED_TARGET_SHA256 below is for $TARGET_REL only (not this helper)."

cd "$ROOT"

echo "== discover live deployment (names only; no secret values) =="
ENV_CANDIDATES=()
for f in docker/.env.azure-hosted-beta docker/.env.hosted-beta docker/.env; do
  [ -f "$f" ] && ENV_CANDIDATES+=("$f")
done
echo "env_files_present: $(ls -1 docker/.env* 2>/dev/null | xargs -n1 basename 2>/dev/null | tr '\n' ' ' || true)"

if [ "${#ENV_CANDIDATES[@]}" -eq 0 ]; then
  echo "ERROR: no docker/.env* under $ROOT" >&2
  exit 2
fi
ENV_FILE="${PARKIO_ENV_FILE:-${ENV_CANDIDATES[0]}}"
echo "selected_env_file=$ENV_FILE"

COMPOSE_ARGS=()
if command -v docker >/dev/null 2>&1; then
  cid="$(docker ps -qf name=postgres-auth | head -1 || true)"
  if [ -n "$cid" ]; then
    CFG="$(docker inspect -f '{{index .Config.Labels "com.docker.compose.project.config_files"}}' "$cid" 2>/dev/null || true)"
    PROJ="$(docker inspect -f '{{index .Config.Labels "com.docker.compose.project.working_dir"}}' "$cid" 2>/dev/null || true)"
    echo "postgres_auth_working_dir=${PROJ:-unknown}"
    echo "postgres_auth_config_files=${CFG:-unknown}"
    if [ -n "$CFG" ]; then
      IFS=',' read -r -a cfg_arr <<< "$CFG"
      for p in "${cfg_arr[@]}"; do
        case "$p" in
          "$ROOT"/*) COMPOSE_ARGS+=(-f "${p#"$ROOT"/}") ;;
          *) COMPOSE_ARGS+=(-f "$p") ;;
        esac
      done
    fi
  else
    echo "postgres_auth_container=absent"
  fi
fi

if [ "${#COMPOSE_ARGS[@]}" -eq 0 ]; then
  for f in \
    docker/docker-compose.yml \
    docker/docker-compose.apps.yml \
    docker/docker-compose.hosted-beta.yml \
    docker/docker-compose.azure-hosted-beta.yml
  do
    [ -f "$f" ] && COMPOSE_ARGS+=(-f "$f")
  done
  echo "compose_source=fallback_present_overlays count=${#COMPOSE_ARGS[@]}"
else
  echo "compose_source=running_container_labels count=${#COMPOSE_ARGS[@]}"
fi

echo "== target script integrity ($TARGET_REL) =="
if [ ! -f "$TARGET" ]; then
  echo "ERROR: target absent at $TARGET" >&2
  echo "Stage reviewed api copy to a NEW path only (do not overwrite dirty files), e.g.:" >&2
  echo "  $TARGET.reviewed-r01" >&2
  echo "Then: TARGET=$TARGET.reviewed-r01 PARKIO_ROOT=$ROOT bash <this-helper>" >&2
  exit 2
fi

if [ -n "${TARGET_OVERRIDE:-}" ]; then
  TARGET="$TARGET_OVERRIDE"
  echo "target_override=$TARGET"
fi

ACTUAL_TARGET_SHA256="$(sha256sum "$TARGET" | awk '{print $1}')"
echo "target_path=$TARGET"
echo "target_sha256_actual=$ACTUAL_TARGET_SHA256"
echo "target_sha256_expected=$EXPECTED_TARGET_SHA256"
if [ "$ACTUAL_TARGET_SHA256" != "$EXPECTED_TARGET_SHA256" ]; then
  echo "ERROR: TARGET checksum mismatch — refusing to execute verify. Leaving dirty checkout untouched." >&2
  exit 3
fi

echo "== execute read-only verify (script prints no credential material) =="
set +e
PARKIO_ENV_FILE="$ENV_FILE" bash "$TARGET" "${COMPOSE_ARGS[@]}"
ec=$?
set -e
echo "verify_exit=$ec"
exit "$ec"
