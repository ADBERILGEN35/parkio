#!/usr/bin/env bash
# Shared helpers that put scripts/guard-web-synthetic-map-deploy.sh in front of
# every supported web deployment entrypoint (audit F-05). Source, do not execute.
#
#   parkio_web_guard_split_args ARGS...   -> PWG_GLOBAL[], PWG_SUBCMD, PWG_SUBARGS[]
#   parkio_web_guard_decide               -> PWG_DECISION: run | skip | refuse (+ PWG_REASON)
#   parkio_web_guard_check_config FILE [guard args...]
#       run the guard on services.web.image of a rendered `compose config --format json`
#       (returns 0 when the model has no web service)
#   parkio_web_guard_skip_requested       -> 0 only for the explicit break-glass token
#
# The guard is always invoked through `bash`, so a checkout that lost the
# executable bit (core.fileMode=false, archives) still runs it.

PARKIO_WEB_GUARD_BREAK_GLASS_TOKEN="I_ACCEPT_UNVERIFIED_WEB_IMAGE"

_pwg_root() {
  (cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
}

# Split `docker compose` arguments into global options, the subcommand and its
# arguments. Global options that take a value are consumed with it so the value is
# never mistaken for the subcommand.
parkio_web_guard_split_args() {
  PWG_GLOBAL=()
  PWG_SUBCMD=""
  PWG_SUBARGS=()
  while [ "$#" -gt 0 ]; do
    case "$1" in
      -f|--file|-p|--project-name|--profile|--env-file|--project-directory|--ansi|--progress|--parallel)
        PWG_GLOBAL+=("$1" "${2:-}")
        shift 2 || shift
        ;;
      -*)
        PWG_GLOBAL+=("$1")
        shift
        ;;
      *)
        PWG_SUBCMD="$1"
        shift
        PWG_SUBARGS=("$@")
        return 0
        ;;
    esac
  done
}

# Decide whether the split invocation can create or recreate the web container.
parkio_web_guard_decide() {
  PWG_DECISION="skip"
  PWG_REASON="subcommand '${PWG_SUBCMD:-<none>}' does not create containers"
  case "$PWG_SUBCMD" in
    up|create|run) ;;
    *) return 0 ;;
  esac

  local no_deps=0 names=() arg i=0 n=${#PWG_SUBARGS[@]} pull_value="" build=0
  while [ "$i" -lt "$n" ]; do
    arg="${PWG_SUBARGS[$i]}"
    case "$arg" in
      --no-deps) no_deps=1 ;;
      --build) build=1 ;;
      --pull=*) pull_value="${arg#--pull=}" ;;
      --pull) i=$((i + 1)); pull_value="${PWG_SUBARGS[$i]:-}" ;;
      # up/create/run options that consume a value.
      --attach|--no-attach|--exit-code-from|--scale|-t|--timeout|--wait-timeout|\
      -e|--env|--env-file|--name|-v|--volume|-p|--publish|-w|--workdir|-u|--user|\
      -l|--label|--entrypoint|--cap-add|--cap-drop|--network|--use-aliases)
        i=$((i + 1)) ;;
      -*) ;;
      *) names+=("$arg") ;;
    esac
    i=$((i + 1))
  done

  local targets_web=1
  if [ "$PWG_SUBCMD" = "run" ]; then
    # `run SERVICE [COMMAND...]`: only the first name is the service.
    if [ "${#names[@]}" -gt 0 ] && [ "${names[0]}" != "web" ] && [ "$no_deps" -eq 1 ]; then
      targets_web=0
    fi
  elif [ "${#names[@]}" -gt 0 ] && [ "$no_deps" -eq 1 ]; then
    targets_web=0
    for arg in "${names[@]}"; do
      [ "$arg" = "web" ] && targets_web=1
    done
  fi
  # Without --no-deps a named service may pull web in as a dependency; with no
  # names every service (including web) is targeted. Both run the guard.

  if [ "$targets_web" -eq 0 ]; then
    PWG_REASON="'${PWG_SUBCMD}' with --no-deps does not target web"
    return 0
  fi
  if [ "$build" -eq 1 ]; then
    PWG_DECISION="refuse"
    PWG_REASON="'--build' would rebuild web after verification; build first, then up without --build"
    return 0
  fi
  case "$pull_value" in
    always|newer|build)
      PWG_DECISION="refuse"
      PWG_REASON="'--pull ${pull_value}' can replace the verified web image; pull first, then up without it"
      return 0
      ;;
  esac
  PWG_DECISION="run"
  PWG_REASON="'${PWG_SUBCMD}' can create the web container"
}

parkio_web_guard_skip_requested() {
  local v="${PARKIO_SKIP_WEB_MAP_GUARD:-}"
  [ -z "$v" ] && return 1
  if [ "$v" = "$PARKIO_WEB_GUARD_BREAK_GLASS_TOKEN" ]; then
    echo "WARNING: PARKIO_SKIP_WEB_MAP_GUARD break-glass set — web image is NOT verified" >&2
    return 0
  fi
  echo "ERROR: PARKIO_SKIP_WEB_MAP_GUARD='${v}' is not accepted; the web map guard only" >&2
  echo "       skips for the explicit token ${PARKIO_WEB_GUARD_BREAK_GLASS_TOKEN}" >&2
  return 2
}

# Run the guard against the web image of a rendered compose model.
parkio_web_guard_check_config() {
  local config_json="$1"
  shift
  local has_web
  has_web="$(python3 - "$config_json" <<'PY'
import json, sys
try:
    model = json.load(open(sys.argv[1]))
except Exception:
    print("error"); sys.exit(0)
print("yes" if "web" in (model.get("services") or {}) else "no")
PY
)"
  case "$has_web" in
    no)
      echo "web-map-deploy-guard: SKIP (compose model has no web service)"
      return 0
      ;;
    yes) ;;
    *)
      echo "web-map-deploy-guard: BLOCKED: rendered compose config is unreadable" >&2
      return 1
      ;;
  esac
  bash "$(_pwg_root)/scripts/guard-web-synthetic-map-deploy.sh" --compose-config-json "$config_json" "$@"
}
