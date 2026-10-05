#!/usr/bin/env bash
# Hosted-beta isolation (owner decision 2026-10-05, CL-F12 item 4): the default hosted-beta profile
# refuses the repository's production configuration, and the Civo production wrapper still accepts it.
#
#   parkio_configure_deployment_profile, hosted-beta:
#     refused: the production example (docker/.env.azure-hosted-beta.example, without its profile
#       key), each production hostname alone, in another case, with a trailing dot, quoted, or
#       exported in the process env;
#     accepted: the hosted-beta example, and beta hostnames under parkio.dev;
#     unchanged: the azure-hosted-beta and invite-production profiles with their examples.
#   deploy-hosted-beta.sh, rollback-hosted-beta.sh and validate-hosted-beta-compose.sh stop on a
#     production configuration before any docker call. It is the deploy preflight's valid fixture
#     moved from beta.parkio.dev to parkio.dev, so the deploy's preflight passes and the guard is
#     what stops it. scripts/test-canonical-production-file-set.sh runs the same entry points with
#     the beta fixture, which they accept.
#   scripts/parkio-prod-compose.sh renders the production example's model.
#
# Nothing is built, pulled or started: a docker shim records every call and forwards only
# `compose ... config` to the real docker.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
pass_n=0
fail_n=0
pass() { echo "PASS $*"; pass_n=$((pass_n + 1)); }
bad() { echo "FAIL $*"; fail_n=$((fail_n + 1)); }

work="$(mktemp -d)"
trap 'rm -rf "${work:?}"' EXIT
REAL_DOCKER="$(command -v docker || true)"
REFUSED_TEXT="is a production hostname; the hosted-beta profile refuses production configuration"
HOSTNAME_KEYS=(PARKIO_DOMAIN PARKIO_WEB_DOMAIN PARKIO_MEDIA_DOMAIN)

# The repository's production configuration and a beta one, both without a profile key, so the
# default profile (hosted-beta) applies.
prod_env="$work/production.env"
grep -v '^PARKIO_DEPLOYMENT_PROFILE=' docker/.env.azure-hosted-beta.example >"$prod_env"
beta_env="$work/beta.env"
grep -v '^PARKIO_DEPLOYMENT_PROFILE=' docker/.env.hosted-beta.example >"$beta_env"

with_value() { # with_value SOURCE KEY VALUE OUT: SOURCE with KEY set to VALUE
  grep -v "^$2=" "$1" >"$4" || true
  printf '%s=%s\n' "$2" "$3" >>"$4"
}

# configure PROFILE|- ENV_FILE [VAR=VALUE...]: parkio_configure_deployment_profile's exit code, with
# its stderr in $work/err. - leaves PARKIO_DEPLOYMENT_PROFILE unset.
configure() {
  local profile="$1" env_file="$2" rc=0
  shift 2
  local -a vars=()
  [ "$profile" = "-" ] || vars+=("PARKIO_DEPLOYMENT_PROFILE=$profile")
  env -u PARKIO_DEPLOYMENT_PROFILE -u PARKIO_DOMAIN -u PARKIO_WEB_DOMAIN -u PARKIO_MEDIA_DOMAIN \
    "${vars[@]}" "$@" bash -c 'set -euo pipefail
      source scripts/lib/deploy-common.sh
      parkio_configure_deployment_profile "$1" >/dev/null' _ "$env_file" 2>"$work/err" || rc=$?
  return "$rc"
}
expect_refused() { # expect_refused NAME TEXT... (after configure)
  local name="$1" rc="$2" text
  shift 2
  if [ "$rc" -ne 2 ]; then bad "$name: exit $rc, want 2"; return; fi
  for text in "$@"; do
    grep -qF -- "$text" "$work/err" || { bad "$name: '$text' not in the error"; return; }
  done
  pass "$name"
}
expect_accepted() { # expect_accepted NAME RC
  if [ "$2" -eq 0 ]; then pass "$1"; else bad "$1: exit $2: $(head -n 2 "$work/err")"; fi
}

echo "--- parkio_configure_deployment_profile ---"
rc=0; configure - "$prod_env" || rc=$?
expect_refused "default profile: the production example is refused, naming every production hostname" "$rc" \
  "PARKIO_DOMAIN=api.parkio.dev $REFUSED_TEXT" "PARKIO_WEB_DOMAIN=app.parkio.dev" "PARKIO_MEDIA_DOMAIN=media.parkio.dev" \
  "Production runs through scripts/parkio-prod-compose.sh"
rc=0; configure hosted-beta "$prod_env" || rc=$?
expect_refused "explicit hosted-beta profile: the production example is refused" "$rc" "$REFUSED_TEXT"
for key in "${HOSTNAME_KEYS[@]}"; do
  case "$key" in PARKIO_DOMAIN) host=api.parkio.dev ;; PARKIO_WEB_DOMAIN) host=app.parkio.dev ;; *) host=media.parkio.dev ;; esac
  with_value "$beta_env" "$key" "$host" "$work/one.env"
  rc=0; configure - "$work/one.env" || rc=$?
  expect_refused "beta example with only $key=$host is refused" "$rc" "$key=$host $REFUSED_TEXT"
done
with_value "$beta_env" PARKIO_DOMAIN "API.Parkio.DEV." "$work/case.env"
rc=0; configure - "$work/case.env" || rc=$?
expect_refused "another case and a trailing dot do not hide a production hostname" "$rc" "PARKIO_DOMAIN=API.Parkio.DEV. $REFUSED_TEXT"
with_value "$beta_env" PARKIO_WEB_DOMAIN '"app.parkio.dev"' "$work/quoted.env"
rc=0; configure - "$work/quoted.env" || rc=$?
expect_refused "a quoted production hostname is refused" "$rc" "PARKIO_WEB_DOMAIN=app.parkio.dev $REFUSED_TEXT"
rc=0; configure - "$beta_env" PARKIO_MEDIA_DOMAIN=media.parkio.dev || rc=$?
expect_refused "a production hostname exported in the process env is refused (Compose prefers it)" "$rc" \
  "PARKIO_MEDIA_DOMAIN=media.parkio.dev $REFUSED_TEXT"
rc=0; configure - "$beta_env" || rc=$?
expect_accepted "the hosted-beta example is accepted" "$rc"
grep -v '^PARKIO_DEPLOYMENT_PROFILE=' scripts/preflight-fixtures/valid.env >"$work/beta-parkio.env"
rc=0; configure - "$work/beta-parkio.env" || rc=$?
expect_accepted "beta hostnames under parkio.dev (api.beta.parkio.dev) are accepted" "$rc"
rc=0; configure azure-hosted-beta docker/.env.azure-hosted-beta.example || rc=$?
expect_accepted "the deprecated azure-hosted-beta profile still accepts the production example (Azure exception)" "$rc"
rc=0; configure invite-production docker/.env.invite-production.example || rc=$?
expect_accepted "the invite-production profile still accepts its production example" "$rc"

echo "--- entry points (docker shim) ---"
mkdir -p "$work/shim"
cat >"$work/shim/docker" <<'EOF'
#!/usr/bin/env bash
# Records every call; forwards only `compose ... config` to the real docker.
printf '%s\n' "$*" >>"$GUARD_TEST_DOCKER_LOG"
if [ "${1:-}" = "compose" ]; then
  for a in "$@"; do [ "$a" = "config" ] && exec "$GUARD_TEST_REAL_DOCKER" "$@"; done
fi
echo "guard test docker shim: refused: docker $*" >&2
exit 97
EOF
chmod +x "$work/shim/docker"
printf '{"imageTag":"sha-guardtest","gitSha":"%s","branch":"api","imageVersion":"guard-test","deploymentProfile":"hosted-beta"}\n' \
  "$(printf '0%.0s' $(seq 1 40))" >"$work/manifest.json"

# A production configuration that passes the deploy preflight: the valid fixture on parkio.dev.
prod_entry_env="$work/production-entry.env"
grep -v '^PARKIO_DEPLOYMENT_PROFILE=' scripts/preflight-fixtures/valid.env | sed 's/beta\.parkio\.dev/parkio.dev/g' >"$prod_entry_env"
grep -q '^PARKIO_DOMAIN=api.parkio.dev$' "$prod_entry_env" || bad "the production entry env does not use api.parkio.dev"

# entry NAME COMMAND...: runs a hosted-beta entry point with that production configuration
entry() {
  local name="$1" rc=0
  shift
  : >"$work/$name.docker"
  env -u PARKIO_DEPLOYMENT_PROFILE -u PARKIO_DOMAIN -u PARKIO_WEB_DOMAIN -u PARKIO_MEDIA_DOMAIN \
    PATH="$work/shim:$PATH" GUARD_TEST_DOCKER_LOG="$work/$name.docker" GUARD_TEST_REAL_DOCKER="$REAL_DOCKER" \
    PARKIO_ENV_FILE="$prod_entry_env" PARKIO_DEPLOY_ARTIFACT_DIR="$work/artifacts-$name" PARKIO_DEPLOY_OPERATOR=guard-test \
    "$@" >"$work/$name.out" 2>"$work/err" || rc=$?
  if [ "$rc" -ne 0 ] && grep -qF "$REFUSED_TEXT" "$work/err" && [ ! -s "$work/$name.docker" ]; then
    pass "$name stops on the production configuration before any docker call (exit $rc)"
  else
    bad "$name: exit $rc, docker calls: $(wc -l <"$work/$name.docker"), error: $(head -n 1 "$work/err")"
  fi
}
entry deploy-hosted-beta.sh ./scripts/deploy-hosted-beta.sh --dry-run --allow-dirty
entry rollback-hosted-beta.sh ./scripts/rollback-hosted-beta.sh --dry-run --manifest "$work/manifest.json"
entry validate-hosted-beta-compose.sh ./scripts/validate-hosted-beta-compose.sh

echo "--- Civo production wrapper ---"
if [ -z "$REAL_DOCKER" ]; then
  bad "docker is required to render the production wrapper's model"
else
  # The production example plus non-secret placeholders for what the render requires.
  wrapper_env="$work/wrapper.env"
  cp docker/.env.azure-hosted-beta.example "$wrapper_env"
  rendered=0
  for _ in $(seq 1 40); do
    : >"$work/wrapper.docker"
    if PATH="$work/shim:$PATH" GUARD_TEST_DOCKER_LOG="$work/wrapper.docker" GUARD_TEST_REAL_DOCKER="$REAL_DOCKER" \
      PARKIO_ENV_FILE="$wrapper_env" bash scripts/parkio-prod-compose.sh config --format json >"$work/wrapper.json" 2>"$work/err"; then
      rendered=1
      break
    fi
    missing="$(sed -n 's/.*required variable \([A-Z0-9_]*\) is missing.*/\1/p' "$work/err" | head -n 1)"
    [ -n "$missing" ] || break
    printf '%s=example.invalid\n' "$missing" >>"$wrapper_env"
  done
  domain="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["services"]["caddy"]["environment"]["PARKIO_DOMAIN"])' "$work/wrapper.json" 2>/dev/null || true)"
  if [ "$rendered" -eq 1 ] && [ "$domain" = "api.parkio.dev" ]; then
    pass "scripts/parkio-prod-compose.sh renders the production example (PARKIO_DOMAIN=$domain)"
  else
    bad "scripts/parkio-prod-compose.sh did not render the production example: $(head -n 2 "$work/err")"
  fi
fi

echo "=== hosted-beta production-config guard: $pass_n passed, $fail_n failed ==="
[ "$fail_n" -eq 0 ]
