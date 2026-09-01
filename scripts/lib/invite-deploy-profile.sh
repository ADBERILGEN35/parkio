#!/usr/bin/env bash
# Invite-production deployment profile resolution (PROD-DEPLOY-01B-03E-A1).
# shellcheck shell=bash

# Explicit human gate token required for public+Caddy cutover deploys.
PARKIO_CUTOVER_AUTHORIZATION_TOKEN="${PARKIO_CUTOVER_AUTHORIZATION_TOKEN:-PROD-DEPLOY-01B-03E-B}"

# parkio_invite_deploy_profile_label edge_mode acme_authorized
# Prints: dark | public-staged | public-cutover
# Exit 2 on unknown values, 4 on invalid combinations.
parkio_invite_deploy_profile_label() {
  local edge_mode="${1:-}"
  local acme_authorized="${2:-}"

  case "$edge_mode" in
    dark)
      if [ "$acme_authorized" = "true" ]; then
        echo "ERROR: edge=dark cannot be combined with acme=true" >&2
        return 4
      fi
      printf 'dark'
      ;;
    public)
      if [ "$acme_authorized" = "true" ]; then
        printf 'public-cutover'
      else
        printf 'public-staged'
      fi
      ;;
    *)
      echo "ERROR: missing or unsupported PARKIO_INVITE_EDGE_MODE='$edge_mode' (expected dark or public)" >&2
      return 2
      ;;
  esac
}

# parkio_validate_invite_registration_mode value
parkio_validate_invite_registration_mode() {
  local registration_mode="${1:-}"
  if [ -z "$registration_mode" ]; then
    echo "ERROR: PARKIO_REGISTRATION_MODE is required (fail-closed)" >&2
    return 4
  fi
  case "$registration_mode" in
    closed) ;;
    invite|open)
      echo "ERROR: invite-production cutover requires registration_mode=closed (got '$registration_mode')" >&2
      return 4
      ;;
    *)
      echo "ERROR: unsupported PARKIO_REGISTRATION_MODE='$registration_mode'" >&2
      return 2
      ;;
  esac
}

# parkio_validate_invite_dispatch_inputs
# Env:
#   PARKIO_DISPATCH_INVITE_EDGE_MODE
#   PARKIO_DISPATCH_INVITE_ACME_AUTHORIZED
#   PARKIO_DISPATCH_REGISTRATION_MODE
#   PARKIO_DISPATCH_CUTOVER_AUTHORIZATION (required when acme=true)
parkio_validate_invite_dispatch_inputs() {
  local edge_mode="${PARKIO_DISPATCH_INVITE_EDGE_MODE:-}"
  local acme_authorized="${PARKIO_DISPATCH_INVITE_ACME_AUTHORIZED:-}"
  local registration_mode="${PARKIO_DISPATCH_REGISTRATION_MODE:-}"
  local cutover_authorization="${PARKIO_DISPATCH_CUTOVER_AUTHORIZATION:-}"

  if [ -z "$edge_mode" ]; then
    echo "ERROR: invite_edge_mode dispatch input is required" >&2
    return 4
  fi
  if [ -z "$acme_authorized" ]; then
    echo "ERROR: invite_acme_authorized dispatch input is required" >&2
    return 4
  fi
  if [ -z "$registration_mode" ]; then
    echo "ERROR: registration_mode dispatch input is required" >&2
    return 4
  fi

  if [ "$edge_mode" != "public" ]; then
    echo "ERROR: workflow deploy requires invite_edge_mode=public (got '$edge_mode')" >&2
    return 4
  fi

  case "$acme_authorized" in
    true|false) ;;
    *)
      echo "ERROR: unsupported invite_acme_authorized='$acme_authorized' (expected true or false)" >&2
      return 2
      ;;
  esac

  parkio_validate_invite_registration_mode "$registration_mode" || return $?

  local profile
  profile="$(parkio_invite_deploy_profile_label "$edge_mode" "$acme_authorized")" || return $?

  if [ "$profile" = "public-cutover" ]; then
    if [ -z "$cutover_authorization" ]; then
      echo "ERROR: public cutover requires cutover_authorization dispatch input" >&2
      return 4
    fi
    if [ "$cutover_authorization" != "$PARKIO_CUTOVER_AUTHORIZATION_TOKEN" ]; then
      echo "ERROR: invalid cutover_authorization token" >&2
      return 4
    fi
  elif [ -n "$cutover_authorization" ]; then
    echo "ERROR: cutover_authorization is only valid for public cutover deploys" >&2
    return 4
  fi

  printf '%s' "$profile"
}
