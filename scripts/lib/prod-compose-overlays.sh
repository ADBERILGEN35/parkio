#!/usr/bin/env bash
# N-10 / #370: shared, policy-aware production Compose overlay selection.
# Used by scripts/parkio-prod-compose.sh (Civo) and scripts/lib/deploy-common.sh
# (hosted-beta / azure-hosted-beta). Custom file lists never grow Civo overlays.
#
# shellcheck shell=bash

PARKIO_CIVO_ALERTMANAGER_OVERLAY_REL="docker/docker-compose.civo-alertmanager.yml"
PARKIO_CIVO_NONROOT_OVERLAY_REL="docker/docker-compose.civo-nonroot.yml"

# Observability services hosted-beta / azure-hosted-beta keep disabled (not the
# Civo Alertmanager activation path).
parkio_hosted_beta_disabled_observability_services() {
  printf '%s\n' alertmanager loki promtail tempo
}

# True when FILES_LIST is the hosted-beta production list Civo overlays may append to.
parkio_compose_files_list_is_civo_overlay_base() {
  local files_list="$1"
  [ -f "$files_list" ] || return 1
  grep -qx 'docker/docker-compose.azure-hosted-beta.yml' "$files_list"
}

# Resolve which Civo overlays a path should append.
# Usage: parkio_select_production_overlays ROOT POLICY FILES_LIST
#   POLICY:
#     civo        — append Alertmanager + non-root when FILES_LIST is the production base
#     hosted-beta — never append Civo overlays (deploy / rollback / DR)
#     omit        — never append
# Prints relative overlay paths (one per line). Sets:
#   PARKIO_PRODUCTION_OVERLAY_POLICY
#   PARKIO_CIVO_ALERTMANAGER_OVERLAY (0|1)
#   PARKIO_CIVO_NONROOT_OVERLAY (0|1)
parkio_select_production_overlays() {
  local root="$1" policy="$2" files_list="$3"
  PARKIO_PRODUCTION_OVERLAY_POLICY="$policy"
  PARKIO_CIVO_ALERTMANAGER_OVERLAY=0
  PARKIO_CIVO_NONROOT_OVERLAY=0
  export PARKIO_PRODUCTION_OVERLAY_POLICY PARKIO_CIVO_ALERTMANAGER_OVERLAY PARKIO_CIVO_NONROOT_OVERLAY

  case "$policy" in
    hosted-beta|omit) return 0 ;;
    civo) ;;
    *)
      echo "ERROR: parkio_select_production_overlays: unknown policy '$policy' (expected civo|hosted-beta|omit)" >&2
      return 2
      ;;
  esac

  if ! parkio_compose_files_list_is_civo_overlay_base "$files_list"; then
    return 0
  fi

  local am="$root/$PARKIO_CIVO_ALERTMANAGER_OVERLAY_REL"
  local nr="$root/$PARKIO_CIVO_NONROOT_OVERLAY_REL"
  if [ ! -f "$am" ]; then
    echo "ERROR: missing canonical Civo Alertmanager overlay $am" >&2
    return 1
  fi
  if [ ! -f "$nr" ]; then
    echo "ERROR: missing canonical Civo non-root overlay $nr" >&2
    return 1
  fi
  PARKIO_CIVO_ALERTMANAGER_OVERLAY=1
  PARKIO_CIVO_NONROOT_OVERLAY=1
  export PARKIO_CIVO_ALERTMANAGER_OVERLAY PARKIO_CIVO_NONROOT_OVERLAY
  printf '%s\n' "$PARKIO_CIVO_ALERTMANAGER_OVERLAY_REL" "$PARKIO_CIVO_NONROOT_OVERLAY_REL"
  return 0
}

# True when a Compose -f word list (space-separated or already split) includes the non-root overlay.
parkio_compose_files_include_nonroot_overlay() {
  local f
  for f in "$@"; do
    case "$f" in
      */docker-compose.civo-nonroot.yml|docker/docker-compose.civo-nonroot.yml) return 0 ;;
    esac
  done
  return 1
}
