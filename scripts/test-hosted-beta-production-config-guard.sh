#!/usr/bin/env bash
# Hosted-beta isolation (owner decision 2026-10-05, CL-F12 item 4): the default hosted-beta profile
# refuses the repository's production configuration, and the Civo production wrapper still accepts it.
# The guard judges the hostnames Compose resolves: it reads them from the rendered model (#288 review B1).
#
#   parkio_configure_deployment_profile, hosted-beta:
#     refused:
#       - the production example (docker/.env.azure-hosted-beta.example, without its profile key);
#       - each production hostname alone, or exported in the process env;
#       - twelve env-file spellings of PARKIO_DOMAIN that Compose resolves to api.parkio.dev: case
#         and a trailing dot, quotes, whitespace, an inline comment, `export`, an indented key,
#         spaces around `=`, a quoted value with a comment, and a port;
#       - a model that cannot be rendered, and a model whose caddy hostnames cannot be read (a fake
#         render: no caddy service, a missing or a blank hostname);
#     accepted: the hosted-beta example, and beta hostnames under parkio.dev;
#     unchanged: the azure-hosted-beta and invite-production profiles with their examples.
#   deploy-hosted-beta.sh, rollback-hosted-beta.sh and validate-hosted-beta-compose.sh stop on a
#     production configuration before any state-changing docker call. Only the read-only `compose
#     ... config` render runs. The configuration is either:
#       - the deploy preflight's valid fixture moved to parkio.dev, so the deploy's preflight passes
#         and the guard is what stops it;
#       - or the production example with inline comments on its hostnames (the review's end-to-end
#         case).
#     scripts/test-canonical-production-file-set.sh runs the same entry points with the beta fixture.
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
[ -n "$REAL_DOCKER" ] || { echo "FAIL docker is required: the guard renders the compose model"; exit 1; }
REFUSED_TEXT="is a production hostname; the hosted-beta profile refuses production configuration"
HOSTNAME_KEYS=(PARKIO_DOMAIN PARKIO_WEB_DOMAIN PARKIO_MEDIA_DOMAIN)

# The repository's production configuration and a beta one, both without a profile key, so the
# default profile (hosted-beta) applies.
prod_env="$work/production.env"
grep -v '^PARKIO_DEPLOYMENT_PROFILE=' docker/.env.azure-hosted-beta.example >"$prod_env"
beta_env="$work/beta.env"
grep -v '^PARKIO_DEPLOYMENT_PROFILE=' docker/.env.hosted-beta.example >"$beta_env"

with_line() { # with_line SOURCE KEY LINE OUT: SOURCE without its KEY= line, plus LINE verbatim
  grep -v "^$2=" "$1" >"$4" || true
  printf '%s\n' "$3" >>"$4"
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
expect_refused() { # expect_refused NAME RC TEXT... (after configure)
  local name="$1" rc="$2" text
  shift 2
  if [ "$rc" -ne 2 ]; then bad "$name: exit $rc, want 2: $(head -n 2 "$work/err" | tr '\n' ' ')"; return; fi
  for text in "$@"; do
    grep -qF -- "$text" "$work/err" || { bad "$name: '$text' not in the error: $(head -n 2 "$work/err" | tr '\n' ' ')"; return; }
  done
  pass "$name"
}
expect_accepted() { # expect_accepted NAME RC
  if [ "$2" -eq 0 ]; then pass "$1"; else bad "$1: exit $2: $(head -n 2 "$work/err")"; fi
}

echo "--- parkio_configure_deployment_profile ---"
rc=0; configure - "$prod_env" || rc=$?
expect_refused "default profile: the production example is refused, naming every production hostname" "$rc" \
  "caddy PARKIO_DOMAIN=api.parkio.dev $REFUSED_TEXT" "caddy PARKIO_WEB_DOMAIN=app.parkio.dev" \
  "caddy PARKIO_MEDIA_DOMAIN=media.parkio.dev" "web PARKIO_WEB_CSP_CONNECT_SRC contains https://api.parkio.dev" \
  "Production runs through scripts/parkio-prod-compose.sh"
rc=0; configure hosted-beta "$prod_env" || rc=$?
expect_refused "explicit hosted-beta profile: the production example is refused" "$rc" "$REFUSED_TEXT"
for key in "${HOSTNAME_KEYS[@]}"; do
  case "$key" in PARKIO_DOMAIN) host=api.parkio.dev ;; PARKIO_WEB_DOMAIN) host=app.parkio.dev ;; *) host=media.parkio.dev ;; esac
  with_line "$beta_env" "$key" "$key=$host" "$work/one.env"
  rc=0; configure - "$work/one.env" || rc=$?
  expect_refused "beta example with only $key=$host is refused" "$rc" "caddy $key=$host $REFUSED_TEXT"
done
rc=0; configure - "$beta_env" PARKIO_MEDIA_DOMAIN=media.parkio.dev || rc=$?
expect_refused "a production hostname exported in the process env is refused (Compose prefers it)" "$rc" \
  "caddy PARKIO_MEDIA_DOMAIN=media.parkio.dev $REFUSED_TEXT"

echo "--- env-file spellings Compose resolves to api.parkio.dev (#288 review B1) ---"
variants=(
  'PARKIO_DOMAIN=api.parkio.dev'
  'PARKIO_DOMAIN=API.Parkio.Dev.'
  'PARKIO_DOMAIN="api.parkio.dev"'
  "PARKIO_DOMAIN='api.parkio.dev'"
  'PARKIO_DOMAIN= api.parkio.dev'
  'PARKIO_DOMAIN=api.parkio.dev '
  'PARKIO_DOMAIN=api.parkio.dev # production'
  'export PARKIO_DOMAIN=api.parkio.dev'
  '  PARKIO_DOMAIN=api.parkio.dev'
  'PARKIO_DOMAIN = api.parkio.dev'
  'PARKIO_DOMAIN="api.parkio.dev" # c'
  'PARKIO_DOMAIN=api.parkio.dev:443'
)
for line in "${variants[@]}"; do
  with_line "$beta_env" PARKIO_DOMAIN "$line" "$work/variant.env"
  rc=0; configure - "$work/variant.env" || rc=$?
  expect_refused "env line [$line] is refused" "$rc" "caddy PARKIO_DOMAIN=" "$REFUSED_TEXT"
done

echo "--- accepted, unchanged, fail-closed ---"
rc=0; configure - "$beta_env" || rc=$?
expect_accepted "the hosted-beta example is accepted" "$rc"
grep -v '^PARKIO_DEPLOYMENT_PROFILE=' scripts/preflight-fixtures/valid.env >"$work/beta-parkio.env"
rc=0; configure - "$work/beta-parkio.env" || rc=$?
expect_accepted "beta hostnames under parkio.dev (api.beta.parkio.dev) are accepted" "$rc"
rc=0; configure azure-hosted-beta docker/.env.azure-hosted-beta.example || rc=$?
expect_accepted "the deprecated azure-hosted-beta profile still accepts the production example (Azure exception)" "$rc"
rc=0; configure invite-production docker/.env.invite-production.example || rc=$?
expect_accepted "the invite-production profile still accepts its production example" "$rc"
grep -v '^PARKIO_DOMAIN=' "$beta_env" >"$work/no-domain.env"
rc=0; configure - "$work/no-domain.env" || rc=$?
expect_refused "a model that cannot be rendered (no PARKIO_DOMAIN) is refused" "$rc" \
  "cannot render the hosted-beta model to read the hostnames Compose resolves"

echo "--- a model whose caddy hostnames cannot be read (fake render, #288 review N4) ---"
mkdir -p "$work/fake-render"
cat >"$work/fake-render/docker" <<'EOF'
#!/usr/bin/env bash
# Answers `compose ... config` with the model in $GUARD_TEST_FAKE_MODEL; refuses everything else.
for a in "$@"; do [ "$a" = "config" ] && { cat "$GUARD_TEST_FAKE_MODEL"; exit 0; }; done
echo "fake render: refused: docker $*" >&2
exit 97
EOF
chmod +x "$work/fake-render/docker"
fake_model() { # fake_model NAME CADDY_ENVIRONMENT_JSON|- (- = no caddy service)
  python3 - "$work/model-$1.json" "$2" <<'PY'
import json, sys
services = {"web": {"image": "parkio/web",
                    "environment": {"PARKIO_WEB_CSP_CONNECT_SRC": "'self' https://api.beta.example.com"}}}
if sys.argv[2] != "-":
    services["caddy"] = {"image": "caddy:2", "environment": json.loads(sys.argv[2])}
json.dump({"name": "parkio", "services": services}, open(sys.argv[1], "w"))
PY
}
fake_configure() { # fake_configure NAME: the default profile against model NAME; exit code in $rc
  rc=0
  configure - "$beta_env" PATH="$work/fake-render:$PATH" GUARD_TEST_FAKE_MODEL="$work/model-$1.json" || rc=$?
}
fake_model control '{"PARKIO_DOMAIN": "api.beta.example.com", "PARKIO_WEB_DOMAIN": "app.beta.example.com", "PARKIO_MEDIA_DOMAIN": "media.beta.example.com"}'
fake_configure control
expect_accepted "control: the fake render with beta hostnames is accepted" "$rc"
fake_model no-caddy -
fake_configure no-caddy
expect_refused "a model without caddy is refused as unreadable" "$rc" \
  "gives no hostname for caddy PARKIO_DOMAIN, caddy PARKIO_WEB_DOMAIN, caddy PARKIO_MEDIA_DOMAIN"
fake_model no-web-domain '{"PARKIO_DOMAIN": "api.beta.example.com", "PARKIO_MEDIA_DOMAIN": "media.beta.example.com"}'
fake_configure no-web-domain
expect_refused "caddy without PARKIO_WEB_DOMAIN is refused as unreadable" "$rc" "gives no hostname for caddy PARKIO_WEB_DOMAIN;"
fake_model blank-media '{"PARKIO_DOMAIN": "api.beta.example.com", "PARKIO_WEB_DOMAIN": "app.beta.example.com", "PARKIO_MEDIA_DOMAIN": " . "}'
fake_configure blank-media
expect_refused "a blank caddy PARKIO_MEDIA_DOMAIN is refused as unreadable" "$rc" "gives no hostname for caddy PARKIO_MEDIA_DOMAIN;"
fake_model list-form '["PARKIO_DOMAIN=api.parkio.dev", "PARKIO_WEB_DOMAIN=app.beta.example.com", "PARKIO_MEDIA_DOMAIN=media.beta.example.com"]'
fake_configure list-form
expect_refused "a caddy environment in list form is read too (production refused)" "$rc" "caddy PARKIO_DOMAIN=api.parkio.dev $REFUSED_TEXT"

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
# The review's end-to-end case: the production example with inline comments on its hostnames.
commented_env="$work/production-commented.env"
sed -E 's/^(PARKIO_(DOMAIN|WEB_DOMAIN|MEDIA_DOMAIN)=.*)$/\1 # production/' "$prod_env" >"$commented_env"
grep -q '^PARKIO_DOMAIN=api.parkio.dev # production$' "$commented_env" || bad "the commented env lacks the inline comment"

# entry NAME ENV_FILE COMMAND...: runs a hosted-beta entry point with ENV_FILE
entry() {
  local name="$1" env_file="$2" rc=0 changing
  shift 2
  : >"$work/$name.docker"
  env -u PARKIO_DEPLOYMENT_PROFILE -u PARKIO_DOMAIN -u PARKIO_WEB_DOMAIN -u PARKIO_MEDIA_DOMAIN \
    PATH="$work/shim:$PATH" GUARD_TEST_DOCKER_LOG="$work/$name.docker" GUARD_TEST_REAL_DOCKER="$REAL_DOCKER" \
    PARKIO_ENV_FILE="$env_file" PARKIO_DEPLOY_ARTIFACT_DIR="$work/artifacts-$name" PARKIO_DEPLOY_OPERATOR=guard-test \
    "$@" >"$work/$name.out" 2>"$work/err" || rc=$?
  # Every docker call but the read-only `compose ... config` render could change state.
  changing="$(grep -v -E '^compose( .*)? config( |$)' "$work/$name.docker" || true)"
  if [ "$rc" -eq 2 ] && grep -qF "$REFUSED_TEXT" "$work/err" && [ -z "$changing" ] \
    && [ ! -d "$work/artifacts-$name" ]; then
    pass "$name stops on the production configuration before any state-changing docker call (exit $rc)"
  else
    bad "$name: exit $rc, state-changing docker calls: [$changing], error: $(head -n 1 "$work/err")"
  fi
}
entry deploy-hosted-beta.sh "$prod_entry_env" ./scripts/deploy-hosted-beta.sh --dry-run --allow-dirty
entry rollback-hosted-beta.sh "$prod_entry_env" ./scripts/rollback-hosted-beta.sh --dry-run --manifest "$work/manifest.json"
entry validate-hosted-beta-compose.sh "$prod_entry_env" ./scripts/validate-hosted-beta-compose.sh
entry "rollback-hosted-beta.sh (production example, inline comments)" "$commented_env" \
  ./scripts/rollback-hosted-beta.sh --dry-run --manifest "$work/manifest.json"
entry "validate-hosted-beta-compose.sh (production example, inline comments)" "$commented_env" \
  ./scripts/validate-hosted-beta-compose.sh

echo "--- Civo production wrapper ---"
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

echo "=== hosted-beta production-config guard: $pass_n passed, $fail_n failed ==="
[ "$fail_n" -eq 0 ]
