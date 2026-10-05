#!/usr/bin/env bash
# Shared helper that puts scripts/lib/web_conf_d_guard.py next to the web map guard in front of
# every supported web deployment entrypoint (B8b). Source, do not execute.
#
#   parkio_web_conf_d_check CONFIG_JSON BINDING_OVERRIDE
#       CONFIG_JSON is the rendered `docker compose config --format json` and BINDING_OVERRIDE the
#       file parkio_web_guard_bind wrote. Returns 0 when the model mounts no tmpfs at
#       /etc/nginx/conf.d for web, or when the bound image renders that directory at start (built
#       from #198 or later); returns 1 otherwise, and the caller must not start anything.
#
# The image is created, never started, and removed again (docker create/cp/rm only).

parkio_web_conf_d_check() {
  "${PYTHON:-python3}" "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/web_conf_d_guard.py" \
    --config-json "$1" --binding "$2"
}
