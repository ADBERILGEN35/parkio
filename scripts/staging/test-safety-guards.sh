#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=lib/safety-guards.sh
source "${SCRIPT_DIR}/lib/safety-guards.sh"

fail=0
expect_fail() {
  local desc="$1"; shift
  if "$@" >/dev/null 2>&1; then echo "FAIL expected rejection: ${desc}"; fail=1
  else echo "OK rejected: ${desc}"; fi
}

export PARKIO_STAGING_ISOLATION_MARKER=wp062-test-marker-ok
export COMPOSE_PROJECT_NAME=parkio-wp062-test

expect_fail production-type bash -c 'PARKIO_ENVIRONMENT_TYPE=PRODUCTION assert_staging_safety'
expect_fail production-name bash -c 'PARKIO_ENVIRONMENT_TYPE=CI_EPHEMERAL ENVIRONMENT=production assert_not_production'
expect_fail mixed-case bash -c 'PARKIO_ENVIRONMENT_TYPE=CI_EPHEMERAL ENVIRONMENT=Production assert_not_production'
expect_fail unknown-env bash -c 'PARKIO_ENVIRONMENT_TYPE=DEVELOPMENT assert_allowed_environment_type'
expect_fail short-marker bash -c 'PARKIO_STAGING_ISOLATION_MARKER=short assert_marker_safe'
expect_fail unsafe-db bash -c 'assert_safe_database_name parkio_parking'
expect_fail same-source-target bash -c 'assert_restore_target_not_source mydb mydb'
expect_fail destructive-opt-in bash -c 'PARKIO_STAGING_ALLOW_DESTRUCTIVE=no assert_destructive_opt_in'
expect_fail compose-project bash -c 'COMPOSE_PROJECT_NAME=parkio-dev assert_compose_project_isolated'
expect_fail path-traversal bash -c 'assert_safe_evidence_path build/operational-evidence/../etc/passwd'

PARKIO_ENVIRONMENT_TYPE=CI_EPHEMERAL assert_staging_safety && echo "OK accepted CI_EPHEMERAL"
assert_safe_database_name parkio_parking_drill_test && echo "OK safe db suffix"

exit "${fail}"