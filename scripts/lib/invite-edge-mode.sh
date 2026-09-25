#!/usr/bin/env bash
# Invite-production edge mode resolution (PROD-DEPLOY-01B-01).
# shellcheck shell=bash

# parkio_invite_edge_mode_from_env [env-file]
# Prints: dark | public
# Exit 2 on unknown/invalid mode.
parkio_invite_edge_mode_from_env() {
  local env_file="${1:-}"
  local mode="${PARKIO_INVITE_EDGE_MODE:-}"

  if [ -z "$mode" ] && [ -n "$env_file" ] && [ -f "$env_file" ]; then
    # shellcheck source=deploy-common.sh
    mode="$(grep '^PARKIO_INVITE_EDGE_MODE=' "$env_file" | tail -n 1 | cut -d= -f2- \
      | sed -e 's/^"\(.*\)"$/\1/' -e "s/^'\(.*\)'$/\1/")"
  fi

  mode="${mode:-dark}"
  case "$mode" in
    dark|public) printf '%s' "$mode" ;;
    *)
      echo "ERROR: unsupported PARKIO_INVITE_EDGE_MODE='$mode' (expected dark or public)" >&2
      return 2
      ;;
  esac
}

# parkio_invite_acme_authorized_from_env [env-file]
# Prints: true | false
parkio_invite_acme_authorized_from_env() {
  local env_file="${1:-}"
  local value="${PARKIO_INVITE_ACME_AUTHORIZED:-}"

  if [ -z "$value" ] && [ -n "$env_file" ] && [ -f "$env_file" ]; then
    value="$(grep '^PARKIO_INVITE_ACME_AUTHORIZED=' "$env_file" | tail -n 1 | cut -d= -f2- \
      | sed -e 's/^"\(.*\)"$/\1/' -e "s/^'\(.*\)'$/\1/")"
  fi

  value="${value:-false}"
  case "$value" in
    true|1|yes|TRUE|Yes|YES) printf 'true' ;;
    false|0|no|FALSE|No|NO|'') printf 'false' ;;
    *)
      echo "ERROR: unsupported PARKIO_INVITE_ACME_AUTHORIZED='$value'" >&2
      return 2
      ;;
  esac
}
