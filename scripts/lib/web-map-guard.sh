#!/usr/bin/env bash
# Shared helpers that put scripts/guard-web-synthetic-map-deploy.sh in front of
# every supported web deployment entrypoint (audit F-05). Source, do not execute.
#
#   parkio_web_guard_split_args ARGS...   -> PWG_GLOBAL[], PWG_SUBCMD, PWG_SUBARGS[], PWG_AMBIGUOUS
#   parkio_web_guard_decide               -> PWG_DECISION: run | skip | refuse (+ PWG_REASON)
#   parkio_web_guard_bind CONFIG_JSON OVERRIDE_OUT [guard args...]
#       verify services.web of a rendered `compose config --format json` and write the
#       binding override (use it as the LAST -f). Returns 0 with OVERRIDE_OUT absent
#       when the model has no web service.
#   parkio_web_guard_skip_requested       -> 0 only for the explicit break-glass token
#
# Parsing is table-driven from the Compose CLI reference for `up`, `create` and
# `run`. Anything not recognised is AMBIGUOUS and runs the guard: unsupported
# syntax can make a gateway-only call stricter, never make a web-creating call skip.
# The guard is always invoked through `bash`, so a checkout that lost the
# executable bit (core.fileMode=false, archives) still runs it.

PARKIO_WEB_GUARD_BREAK_GLASS_TOKEN="I_ACCEPT_UNVERIFIED_WEB_IMAGE"

_pwg_root() {
  (cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
}

# Option tables: space-padded so `*" $opt "*` is an exact word match.
_PWG_GLOBAL_VALUE=" -f --file -p --project-name --profile --env-file --project-directory --ansi --progress --parallel "
_PWG_GLOBAL_BOOL=" --compatibility --dry-run --all-resources "
_PWG_UP_BOOL=" --abort-on-container-exit --abort-on-container-failure --always-recreate-deps --build -d --detach --force-recreate --menu --no-build --no-color --no-deps --no-log-prefix --no-recreate --no-start --quiet-build --quiet-pull --remove-orphans -V --renew-anon-volumes --wait -w --watch -y --yes "
_PWG_UP_VALUE=" --attach --exit-code-from --no-attach --pull --scale -t --timeout --wait-timeout "
_PWG_CREATE_BOOL=" --build --force-recreate --no-build --no-recreate --quiet-build --quiet-pull --remove-orphans -y --yes "
_PWG_CREATE_VALUE=" --pull --scale "
# `run`: --use-aliases, --rm, -T/--no-TTY, -P/--service-ports are flags; -t is --tty here.
_PWG_RUN_BOOL=" --build -d --detach -i --interactive --no-deps -T --no-TTY --quiet-build --quiet-pull --remove-orphans --rm -P --service-ports --use-aliases -t --tty "
_PWG_RUN_VALUE=" --cap-add --cap-drop -e --env --env-from-file --entrypoint -l --label --name -p --publish --pull -u --user -v --volume -w --workdir "

# Split `docker compose` arguments into global options, the subcommand and its
# arguments. An unknown global option makes the split ambiguous (its value could
# be taken for the subcommand).
parkio_web_guard_split_args() {
  PWG_GLOBAL=()
  PWG_SUBCMD=""
  PWG_SUBARGS=()
  PWG_AMBIGUOUS=""
  PWG_ALL_ARGS=("$@")
  local opt
  while [ "$#" -gt 0 ]; do
    case "$1" in
      --*=*)
        opt="${1%%=*}"
        if [[ "$_PWG_GLOBAL_VALUE" != *" $opt "* ]]; then
          PWG_AMBIGUOUS="unrecognised global option '$1'"
        fi
        PWG_GLOBAL+=("$1")
        shift
        ;;
      -*)
        if [[ "$_PWG_GLOBAL_VALUE" == *" $1 "* ]]; then
          PWG_GLOBAL+=("$1" "${2:-}")
          shift
          [ "$#" -gt 0 ] && shift
        else
          if [[ "$_PWG_GLOBAL_BOOL" != *" $1 "* ]]; then
            PWG_AMBIGUOUS="unrecognised global option '$1'"
          fi
          PWG_GLOBAL+=("$1")
          shift
        fi
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

  if [ -n "$PWG_AMBIGUOUS" ]; then
    # The subcommand itself is uncertain: gate if any creating verb appears at all.
    local a
    for a in "${PWG_ALL_ARGS[@]}"; do
      case "$a" in
        up|create|run)
          PWG_DECISION="run"
          PWG_REASON="ambiguous invocation (${PWG_AMBIGUOUS}); guarding"
          return 0
          ;;
      esac
    done
    return 0
  fi

  local bool_opts value_opts
  case "$PWG_SUBCMD" in
    up) bool_opts="$_PWG_UP_BOOL"; value_opts="$_PWG_UP_VALUE" ;;
    create) bool_opts="$_PWG_CREATE_BOOL"; value_opts="$_PWG_CREATE_VALUE" ;;
    run) bool_opts="$_PWG_RUN_BOOL"; value_opts="$_PWG_RUN_VALUE" ;;
    *) return 0 ;;
  esac

  local no_deps=0 build=0 pull_value="" ambiguous="" scale_web=0
  local names=() arg opt val i=0 n=${#PWG_SUBARGS[@]} end_of_opts=0
  while [ "$i" -lt "$n" ]; do
    arg="${PWG_SUBARGS[$i]}"
    if [ "$end_of_opts" -eq 1 ] || [[ "$arg" != -* ]] || [ "$arg" = "-" ]; then
      names+=("$arg")
      # `run SERVICE [COMMAND [ARGS...]]`: everything after the service is the
      # container command, never Compose options or services.
      if [ "$PWG_SUBCMD" = "run" ]; then
        break
      fi
      i=$((i + 1))
      continue
    fi
    if [ "$arg" = "--" ]; then
      end_of_opts=1
      i=$((i + 1))
      continue
    fi
    opt="$arg"; val=""
    if [[ "$arg" == --*=* ]]; then
      opt="${arg%%=*}"; val="${arg#*=}"
    fi
    if [[ "$value_opts" == *" $opt "* ]]; then
      if [ "$opt" = "$arg" ]; then
        i=$((i + 1))
        val="${PWG_SUBARGS[$i]:-}"
      fi
      [ "$opt" = "--pull" ] && pull_value="$val"
      [ "$opt" = "--scale" ] && [[ "$val" == web=* ]] && scale_web=1
    elif [[ "$bool_opts" == *" $opt "* ]]; then
      if [ "$opt" != "$arg" ] && [ "$val" != "true" ] && [ "$val" != "false" ]; then
        ambiguous="option '$arg' has a non-boolean value"
      elif [ "$val" != "false" ]; then
        [ "$opt" = "--no-deps" ] && no_deps=1
        [ "$opt" = "--build" ] && build=1
      fi
    else
      ambiguous="unrecognised '$PWG_SUBCMD' option '$arg'"
    fi
    i=$((i + 1))
  done

  local targets_web=1
  if [ -z "$ambiguous" ] && [ "$no_deps" -eq 1 ] && [ "${#names[@]}" -gt 0 ] && [ "$scale_web" -eq 0 ]; then
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
    PWG_REASON="'--build' would rebuild web after verification; build first, then run without --build"
    return 0
  fi
  case "$pull_value" in
    always|newer|build)
      PWG_DECISION="refuse"
      PWG_REASON="'--pull ${pull_value}' can replace the verified web image; pull first, then run without it"
      return 0
      ;;
  esac
  PWG_DECISION="run"
  if [ -n "$ambiguous" ]; then
    PWG_REASON="ambiguous invocation (${ambiguous}); guarding"
  else
    PWG_REASON="'${PWG_SUBCMD}' can create the web container"
  fi
}

parkio_web_guard_skip_requested() {
  local v="${PARKIO_SKIP_WEB_MAP_GUARD:-}"
  [ -z "$v" ] && return 1
  if [ "$v" = "$PARKIO_WEB_GUARD_BREAK_GLASS_TOKEN" ]; then
    echo "WARNING: PARKIO_SKIP_WEB_MAP_GUARD break-glass set — web image is NOT verified or bound" >&2
    return 0
  fi
  echo "ERROR: PARKIO_SKIP_WEB_MAP_GUARD='${v}' is not accepted; the web map guard only" >&2
  echo "       skips for the explicit token ${PARKIO_WEB_GUARD_BREAK_GLASS_TOKEN}" >&2
  return 2
}

# Verify the web image of a rendered compose model and write the binding override.
parkio_web_guard_bind() {
  local config_json="$1" override_out="$2"
  shift 2
  rm -f "$override_out"
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
  bash "$(_pwg_root)/scripts/guard-web-synthetic-map-deploy.sh" \
    --compose-config-json "$config_json" --bind-override-out "$override_out" "$@" || return 1
  if [ ! -s "$override_out" ]; then
    echo "web-map-deploy-guard: BLOCKED: guard passed but produced no binding override" >&2
    return 1
  fi
}
