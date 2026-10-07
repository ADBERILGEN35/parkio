#!/usr/bin/env bash
#
# U02 isolated recovery: replay the trusted erasure set into a restored copy (contract stage 4).
#
#   scripts/recovery-replay.sh up   --ticket T --stamp DIR --state DIR --env-file F --project NAME
#   scripts/recovery-replay.sh run  --ticket T --stamp DIR --state DIR --env-file F --project NAME \
#       --recovery-dir DIR --trust TRUST [--timeout-seconds N]
#   scripts/recovery-replay.sh down --state DIR --env-file F --project NAME
#
# Run it after restore-hosted-beta.sh restored a stamp into the targets of an isolated ticket
# (restore-isolated-fixture.sh up --with-minio) with --erasure-evidence, so the recovery dir holds
# the trusted-set file and a CLOSED expose gate.
#
# up    starts Kafka, Redis and the eight participants with restore replay on, attached only to
#       the ticket's internal network (docker/docker-compose.recovery-drill-isolated.yml); no port
#       is published. Each service role of the ticket's PostgreSQL gets a fresh password, kept in
#       STATE (mode 600) and never on a command line or in output.
# run   runs auth-service once as the recovery-replay command (profile plus flag, no web server)
#       and exits with its code: 0 COMPLETE; 20-26 refused, blocked or timed out (contract stage 4).
#       Attempt and dataset come from the trusted-set file, the target identity from the ticket.
#       The verdict is written to DIR/replay-verdict-<attempt>.json. Open the expose gate only
#       with scripts/lib/recovery-expose-gate.py open on a COMPLETE verdict.
# down  removes the recovery apps project (its containers and volumes). Tear the ticket targets
#       down afterwards with restore-isolated-fixture.sh down.
#
# Images are neither built nor pulled: PARKIO_IMAGE_TAG must name images built from the reviewed
# source (docker/docker-compose.images.yml). The project must be named parkio-rd-* or
# parkio-recovery-*, never a developer or hosted project. Production restore stays refused.
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
INSPECT="${ROOT}/scripts/lib/restore-isolated-inspect.py"

usage() {
  sed -n '2,28p' "$0"
  exit 2
}

CMD="${1:-}"
[ -n "${CMD}" ] || usage
shift

TICKET=""
STAMP=""
STATE=""
ENV_FILE=""
PROJECT=""
RECOVERY_DIR_ARG=""
TRUST=""
TIMEOUT_SECONDS="900"

while [ "$#" -gt 0 ]; do
  case "$1" in
    --ticket) TICKET="${2:-}"; shift 2 ;;
    --stamp) STAMP="${2:-}"; shift 2 ;;
    --state) STATE="${2:-}"; shift 2 ;;
    --env-file) ENV_FILE="${2:-}"; shift 2 ;;
    --project) PROJECT="${2:-}"; shift 2 ;;
    --recovery-dir) RECOVERY_DIR_ARG="${2:-}"; shift 2 ;;
    --trust) TRUST="${2:-}"; shift 2 ;;
    --timeout-seconds) TIMEOUT_SECONDS="${2:-}"; shift 2 ;;
    -h|--help) usage ;;
    *) echo "ERROR: unknown argument '$1'" >&2; exit 2 ;;
  esac
done

case "${PROJECT}" in
  parkio-rd-*|parkio-recovery-*) ;;
  *) echo "ERROR: --project must be named parkio-rd-* or parkio-recovery-*" >&2; exit 2 ;;
esac
if [ -z "${STATE}" ] || [ ! -d "${STATE}" ]; then
  echo "ERROR: --state must be an existing directory (it holds the recovery credentials)" >&2
  exit 2
fi
if [ -z "${ENV_FILE}" ] || [ ! -f "${ENV_FILE}" ]; then
  echo "ERROR: --env-file must name the compose env file" >&2
  exit 2
fi
: "${PARKIO_IMAGE_TAG:?set PARKIO_IMAGE_TAG to the images built from the reviewed source}"

STATE="$(cd "${STATE}" && pwd)"
STATE_ENV="${STATE}/recovery-apps.env"
APPS=(kafka redis user-service parking-service media-service gamification-service notification-service
  moderation-service ai-validation-service analytics-service)

export COMPOSE_PROJECT_NAME="${PROJECT}" PARKIO_IMAGE_TAG
compose() {
  docker compose --project-name "${PROJECT}" --project-directory "${ROOT}/docker" --env-file "${ENV_FILE}" \
    -f "${ROOT}/docker/docker-compose.yml" -f "${ROOT}/docker/docker-compose.apps.yml" \
    -f "${ROOT}/docker/docker-compose.images.yml" -f "${ROOT}/docker/docker-compose.recovery-drill-isolated.yml" \
    "$@"
}

load_state() {
  if [ ! -f "${STATE_ENV}" ]; then
    echo "ERROR: ${STATE_ENV} is missing; run '$0 up' first" >&2
    exit 2
  fi
  set -a
  # shellcheck disable=SC1090
  . "${STATE_ENV}"
  set +a
}

require_ticket() {
  if [ -z "${TICKET}" ] || [ ! -f "${TICKET}" ] || [ -z "${STAMP}" ] || [ ! -d "${STAMP}" ]; then
    echo "ERROR: --ticket and --stamp are required" >&2
    exit 2
  fi
}

case "${CMD}" in
  up)
    require_ticket
    # The ticket names the targets; resolving them re-validates the ticket against the live engine.
    postgres_json="$(python3 "${INSPECT}" --ticket "${TICKET}" --stamp "${STAMP}" --resolve-postgres auth)"
    minio_json="$(python3 "${INSPECT}" --ticket "${TICKET}" --stamp "${STAMP}" --resolve-minio)" || {
      echo "ERROR: the ticket has no MinIO target; issue it with restore-isolated-fixture.sh up --with-minio" >&2
      exit 2
    }
    creds_json="$(python3 "${ROOT}/scripts/lib/restore-isolated-ticket.py" service-creds)"
    sql_file="${STATE}/.roles.sql"
    ( umask 077
      printf '%s\n%s\n%s\n' "${postgres_json}" "${minio_json}" "${creds_json}" | python3 -c '
import json, os, re, secrets, sys
lines = sys.stdin.read().splitlines()
postgres, minio = json.loads(lines[0]), json.loads(lines[1])
creds = json.loads("\n".join(lines[2:]))
state_env, sql_file = sys.argv[1], sys.argv[2]
services = ["auth", "user", "parking", "media", "gamification", "notification", "moderation",
            "ai-validation", "analytics"]
values = {
    "RECOVERY_NETWORK": minio["networkName"],
    "RECOVERY_PG": postgres["containerName"],
    "RECOVERY_PG_CONTAINER": postgres["containerName"],
    "RECOVERY_MINIO": minio["aliasHost"],
    "RECOVERY_BUCKET": minio["bucket"],
    "RECOVERY_SOURCE_BUCKET": minio["sourceBucket"],
    "RECOVERY_MINIO_USER": minio["user"],
    "RECOVERY_MINIO_PASSWORD": minio["password"],
}
if not values["RECOVERY_SOURCE_BUCKET"]:
    sys.exit("the ticket records no minio.sourceBucket; issue a new ticket from the stamp")
statements = []
for service in services:
    password = secrets.token_hex(24)
    values["RECOVERY_" + service.upper().replace("-", "") + "_PASSWORD"] = password
    statements.append("ALTER ROLE \"%s\" PASSWORD '\''%s'\'';" % (creds[service]["user"], password))
for key, value in values.items():
    if not re.fullmatch(r"[A-Za-z0-9._:/-]+", value):
        sys.exit(f"{key} has characters the env file cannot carry")
with open(state_env, "w", encoding="utf-8") as out:
    out.writelines(f"{key}={value}\n" for key, value in values.items())
with open(sql_file, "w", encoding="utf-8") as out:
    out.write("\n".join(statements) + "\n")
' "${STATE_ENV}" "${sql_file}" )
    load_state
    docker exec -i "${RECOVERY_PG_CONTAINER}" psql -X -q -v ON_ERROR_STOP=1 -U postgres -d postgres \
      < "${sql_file}" >/dev/null
    rm -f "${sql_file}"
    # RECOVERY_DIR and RECOVERY_TRUST_FILE are only mounted by the one-shot command; the overlay
    # still needs a value to render.
    export RECOVERY_DIR="${STATE}" RECOVERY_TRUST_FILE="${STATE_ENV}"
    compose up -d --wait --no-build --pull never "${APPS[@]}"
    echo "recovery apps up: project=${PROJECT} network=${RECOVERY_NETWORK} (internal, no published port)"
    ;;
  run)
    require_ticket
    load_state
    if [ -z "${RECOVERY_DIR_ARG}" ] || [ ! -f "${RECOVERY_DIR_ARG}/trusted-erasure-set.json" ]; then
      echo "ERROR: --recovery-dir must hold trusted-erasure-set.json (written by the isolated restore)" >&2
      exit 2
    fi
    if [ -z "${TRUST}" ] || [ ! -f "${TRUST}" ]; then
      echo "ERROR: --trust must name the evidence trust document the restore verified with" >&2
      exit 2
    fi
    case "${TIMEOUT_SECONDS}" in
      ''|*[!0-9]*) echo "ERROR: --timeout-seconds must be a whole number" >&2; exit 2 ;;
    esac
    RECOVERY_DIR="$(cd "${RECOVERY_DIR_ARG}" && pwd)"
    RECOVERY_TRUST_FILE="$(cd "$(dirname "${TRUST}")" && pwd)/$(basename "${TRUST}")"
    export RECOVERY_DIR RECOVERY_TRUST_FILE
    binding="$(python3 -c '
import json, sys
document = json.load(open(sys.argv[1], encoding="utf-8"))
print(document["recoveryAttemptId"], document["restoredDatasetId"])
' "${RECOVERY_DIR}/trusted-erasure-set.json")"
    attempt="${binding%% *}"
    dataset="${binding#* }"
    target="$(python3 "${INSPECT}" --ticket "${TICKET}" --stamp "${STAMP}" --resolve-postgres auth \
      | python3 -c 'import json, sys; print(json.load(sys.stdin).get("databaseIdentity", ""))')"
    if [ -z "${target}" ]; then
      echo "ERROR: the isolated ticket pins no auth databaseIdentity" >&2
      exit 2
    fi
    echo "recovery-replay: attempt=${attempt} dataset=${dataset} target=${target}"
    rc=0
    compose run --rm -T --no-deps --pull never --user "$(id -u):$(id -g)" auth-service \
      --evidence=/run/recovery/trusted-erasure-set.json \
      --trust=/run/recovery-trust/erasure-trust.json \
      --attempt="${attempt}" --dataset="${dataset}" --target-identity="${target}" \
      --verdict-out="/run/recovery/replay-verdict-${attempt}.json" \
      --timeout-seconds="${TIMEOUT_SECONDS}" || rc=$?
    echo "recovery-replay: exit ${rc} (verdict ${RECOVERY_DIR}/replay-verdict-${attempt}.json)"
    exit "${rc}"
    ;;
  down)
    load_state
    export RECOVERY_DIR="${STATE}" RECOVERY_TRUST_FILE="${STATE_ENV}"
    compose down -v --remove-orphans --timeout 20
    left="$(docker ps -aq --filter "label=com.docker.compose.project=${PROJECT}")"
    if [ -n "${left}" ]; then
      echo "ERROR: containers of ${PROJECT} remain after down" >&2
      exit 1
    fi
    rm -f "${STATE_ENV}"
    echo "recovery apps down: project=${PROJECT}"
    ;;
  *)
    usage
    ;;
esac
