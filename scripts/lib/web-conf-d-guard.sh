#!/usr/bin/env bash
# Shared helper that puts scripts/lib/web_conf_d_guard.py next to the web map guard in front of
# every supported web deployment entrypoint (B8b). Source, do not execute.
#
#   parkio_web_conf_d_check CONFIG_JSON [BINDING_OVERRIDE]
#       CONFIG_JSON is the rendered `docker compose config --format json` and BINDING_OVERRIDE the
#       file parkio_web_guard_bind wrote. It returns:
#         0 when the model mounts no tmpfs at /etc/nginx/conf.d for web, or when the image renders
#           that directory at start (built from #198 or later);
#         1 otherwise, and the caller must not start anything.
#       Without a binding override (the web map guard's own break-glass skipped it), the image the
#       model's services.web names is inspected instead.
#   parkio_web_conf_d_skip_requested
#       The check's own break-glass, separate from the web map guard's. It returns:
#         0 only for PARKIO_SKIP_WEB_CONF_D_CHECK=I_ACCEPT_UNCHECKED_WEB_CONF_D, with a warning;
#         1 when the variable is unset or empty (the default);
#         2 for any other value, which the caller refuses.
#
# The image is created, never started, and removed again (docker create/cp/rm only).

PARKIO_WEB_CONF_D_BREAK_GLASS_TOKEN="I_ACCEPT_UNCHECKED_WEB_CONF_D"

parkio_web_conf_d_check() {
  local guard
  guard="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/web_conf_d_guard.py"
  if [ -n "${2:-}" ] && [ -f "$2" ]; then
    "${PYTHON:-python3}" "$guard" --config-json "$1" --binding "$2"
  else
    "${PYTHON:-python3}" "$guard" --config-json "$1" --model-image
  fi
}

parkio_web_conf_d_skip_requested() {
  local v="${PARKIO_SKIP_WEB_CONF_D_CHECK:-}"
  [ -z "$v" ] && return 1
  if [ "$v" = "$PARKIO_WEB_CONF_D_BREAK_GLASS_TOKEN" ]; then
    echo "WARNING: PARKIO_SKIP_WEB_CONF_D_CHECK break-glass set — the web image is NOT checked for a" >&2
    echo "         rendered /etc/nginx/conf.d; an image built before #198 starts without a server" >&2
    return 0
  fi
  echo "ERROR: PARKIO_SKIP_WEB_CONF_D_CHECK='${v}' is not accepted; the conf.d check only skips for" >&2
  echo "       the explicit token ${PARKIO_WEB_CONF_D_BREAK_GLASS_TOKEN}" >&2
  return 2
}
