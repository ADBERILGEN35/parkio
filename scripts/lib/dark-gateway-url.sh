#!/usr/bin/env bash
# Strict allowlist for the invite-production dark acceptance endpoint.
#
# PROD-DEPLOY-01A-R3 / D1. The previous guard was a literal comparison against
# "https://api.parkio.dev". That is not sufficient: "https://api.parkio.dev:443",
# "https://api.parkio.dev/", "http://api.parkio.dev" and every other spelling of
# the public host slip through and resolve to the LIVE hosted-beta VM, which
# would make dark smoke pass against the wrong environment.
#
# Blacklisting hostnames cannot be made complete, so this is an allowlist of the
# single endpoint the dark topology actually publishes (see
# docker/docker-compose.invite-dark.yml). Anything else fails closed.
#
# shellcheck shell=bash

# The one endpoint invite-production dark acceptance may target.
PARKIO_DARK_GATEWAY_ALLOWED_URL="http://127.0.0.1:8080"

# parkio_validate_dark_gateway_url <url>
# Exit 0 when the URL is exactly the allowed dark endpoint, 2 otherwise.
# Prints the reason to stderr. Never resolves DNS and never emits the URL's
# userinfo back to the caller.
parkio_validate_dark_gateway_url() {
  local url="${1-}"
  local normalized

  if [ -z "$url" ]; then
    echo "ERROR: dark gateway URL is required and must be exactly ${PARKIO_DARK_GATEWAY_ALLOWED_URL}" >&2
    return 2
  fi

  # Reject anything carrying a credential, query, fragment, whitespace or control
  # character before comparing, so a rejection message can never echo userinfo.
  case "$url" in
    *@*)
      echo "ERROR: dark gateway URL must not contain userinfo." >&2
      return 2
      ;;
    *[\?\#]*)
      echo "ERROR: dark gateway URL must not contain a query string or fragment." >&2
      return 2
      ;;
    *[[:space:]]*|*[[:cntrl:]]*)
      echo "ERROR: dark gateway URL must not contain whitespace or control characters." >&2
      return 2
      ;;
  esac

  # Tolerate exactly one trailing slash; everything else must match byte for byte.
  normalized="${url%/}"

  if [ "$normalized" != "$PARKIO_DARK_GATEWAY_ALLOWED_URL" ]; then
    echo "ERROR: refusing dark gateway URL '${url}'." >&2
    echo "       invite-production dark acceptance may target only ${PARKIO_DARK_GATEWAY_ALLOWED_URL}" >&2
    echo "       (the loopback endpoint published by docker/docker-compose.invite-dark.yml)." >&2
    echo "       Public Parkio hostnames resolve to the hosted-beta VM until PROD-DEPLOY-01B." >&2
    return 2
  fi

  return 0
}

# parkio_assert_dark_redirect_target <location-header>
# A 3xx away from the dark endpoint is a hard failure: following it could leave
# the dark runtime entirely. Relative redirects stay on the dark endpoint and are
# therefore permitted.
parkio_assert_dark_redirect_target() {
  local location="${1-}"

  if [ -z "$location" ]; then
    return 0
  fi
  case "$location" in
    /*)
      return 0
      ;;
    "$PARKIO_DARK_GATEWAY_ALLOWED_URL"|"$PARKIO_DARK_GATEWAY_ALLOWED_URL"/*)
      return 0
      ;;
    *)
      echo "ERROR: dark gateway redirected off the dark endpoint." >&2
      echo "       Refusing to follow a redirect that leaves ${PARKIO_DARK_GATEWAY_ALLOWED_URL}." >&2
      return 2
      ;;
  esac
}

# Allow direct invocation so CI and tests can exercise the guard as a command:
#   scripts/lib/dark-gateway-url.sh <url>
if [ "${BASH_SOURCE[0]}" = "${0}" ]; then
  parkio_validate_dark_gateway_url "${1-}"
  exit $?
fi
