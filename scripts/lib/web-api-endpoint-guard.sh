#!/usr/bin/env bash
# Shared helper that puts scripts/lib/web_api_endpoint_guard.py in front of the web deployment
# entrypoints of the Civo production wrapper and the hosted-beta profile (owner decision
# 2026-10-05, H2: hosted-beta must not silently use production's API endpoint). Source, do not
# execute.
#
#   parkio_web_api_endpoint_check CONFIG_JSON [BINDING_OVERRIDE]
#       CONFIG_JSON is the rendered `docker compose config --format json`, and BINDING_OVERRIDE the
#       file parkio_web_guard_bind wrote, if any. It returns:
#         0 when the web image bakes exactly the API base URL the model's env intends (web's
#           VITE_API_BASE_URL build argument, whose host must be PARKIO_DOMAIN when the model sets
#           it), or when the model has no web service;
#         1 otherwise, and the caller must not start anything.
#       Without a binding override (the web map guard's own break-glass skipped it), the image the
#       model's services.web names is read instead.
#
# The check has no break-glass: the map guard's and the conf.d check's do not skip it. The image is
# created, never started, and removed again (docker create/cp/rm only).

parkio_web_api_endpoint_check() {
  local guard
  guard="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/web_api_endpoint_guard.py"
  if [ -n "${2:-}" ] && [ -f "$2" ]; then
    "${PYTHON:-python3}" "$guard" --compose-config-json "$1" --binding-override "$2"
  else
    "${PYTHON:-python3}" "$guard" --compose-config-json "$1"
  fi
}
