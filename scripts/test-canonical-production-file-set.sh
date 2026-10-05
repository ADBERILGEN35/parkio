#!/usr/bin/env bash
# CL-F12: the hosted-beta production paths share docker/compose.production.files.
#
#   deploy-hosted-beta.sh / rollback-hosted-beta.sh (deploy-common azure-hosted-beta profile)
#     = canonical list, with docker-compose.images.yml right after docker-compose.apps.yml
#   scripts/parkio-prod-compose.sh (Civo production wrapper)
#     = canonical list, then docker-compose.civo-alertmanager.yml
#
# Fails when a compose file is added to only one of these paths, and when the rendered
# models disagree on the digest-pinned images or the auth registration settings.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
fail() { echo "FAIL: $*" >&2; exit 1; }
pass() { echo "PASS: $*"; }

canonical=()
while IFS= read -r line || [ -n "$line" ]; do
  [[ -z "$line" || "$line" =~ ^[[:space:]]*# ]] && continue
  canonical+=("$line")
done < docker/compose.production.files
[ "${#canonical[@]}" -gt 0 ] || fail "docker/compose.production.files lists no files"

# --- Default profile selection -------------------------------------------------
# An env file without PARKIO_DEPLOYMENT_PROFILE selects hosted-beta. The lookup used to fail on the
# absent key under pipefail and end a `set -euo pipefail` caller silently (exit 1), so the default
# profile's entry points stopped before rendering anything.
no_profile_env="$(mktemp)"
grep -v '^PARKIO_DEPLOYMENT_PROFILE=' docker/.env.hosted-beta.example > "$no_profile_env" || true
resolved_rc=0
resolved="$(env -u PARKIO_DEPLOYMENT_PROFILE bash -c 'set -euo pipefail
  source scripts/lib/deploy-common.sh
  parkio_configure_deployment_profile "$1" >/dev/null
  printf "%s" "$PARKIO_DEPLOYMENT_PROFILE"' _ "$no_profile_env")" || resolved_rc=$?
rm -f "$no_profile_env"
[ "$resolved_rc" -eq 0 ] || fail "an env file without PARKIO_DEPLOYMENT_PROFILE ends a set -euo pipefail caller (exit $resolved_rc)"
[ "$resolved" = "hosted-beta" ] || fail "an env file without PARKIO_DEPLOYMENT_PROFILE selected '$resolved', not hosted-beta"
pass "an env file without PARKIO_DEPLOYMENT_PROFILE selects hosted-beta under set -euo pipefail"

# --- deploy/rollback path -----------------------------------------------------
deploy_args="$(bash -c 'set -e
  source scripts/lib/deploy-common.sh
  PARKIO_DEPLOYMENT_PROFILE=azure-hosted-beta
  parkio_configure_deployment_profile docker/.env.azure-hosted-beta.example >/dev/null
  printf "%s" "$PARKIO_COMPOSE_FILES"')"
deploy_files=()
read -r -a deploy_words <<< "$deploy_args"
for ((i = 0; i < ${#deploy_words[@]}; i++)); do
  [ "${deploy_words[i]}" = "-f" ] && deploy_files+=("${deploy_words[i + 1]}")
done
expected_deploy=()
for f in "${canonical[@]}"; do
  expected_deploy+=("$f")
  [ "$f" = "docker/docker-compose.apps.yml" ] && expected_deploy+=("docker/docker-compose.images.yml")
done
[ "${deploy_files[*]}" = "${expected_deploy[*]}" ] || fail "deploy/rollback azure-hosted-beta files drifted from docker/compose.production.files
  got:      ${deploy_files[*]}
  expected: ${expected_deploy[*]}"
pass "deploy/rollback azure-hosted-beta profile = canonical list + images.yml after apps.yml (${#deploy_files[@]} files)"

# --- Civo production wrapper ----------------------------------------------------
shim="$(mktemp -d)"
trap 'rm -rf "$shim"' EXIT
cat > "$shim/docker" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "$@" > "$PARKIO_TEST_DOCKER_ARGV"
EOF
chmod +x "$shim/docker"
PARKIO_TEST_DOCKER_ARGV="$shim/argv" PATH="$shim:$PATH" \
  PARKIO_ENV_FILE="$ROOT/docker/.env.azure-hosted-beta.example" \
  bash scripts/parkio-prod-compose.sh ps >/dev/null
wrapper_files=()
mapfile -t argv < "$shim/argv"
for ((i = 0; i < ${#argv[@]}; i++)); do
  [ "${argv[i]}" = "-f" ] && wrapper_files+=("${argv[i + 1]#"$ROOT/"}")
done
expected_wrapper=("${canonical[@]}" docker/docker-compose.civo-alertmanager.yml)
[ "${wrapper_files[*]}" = "${expected_wrapper[*]}" ] || fail "parkio-prod-compose.sh files drifted from docker/compose.production.files
  got:      ${wrapper_files[*]}
  expected: ${expected_wrapper[*]}"
pass "parkio-prod-compose.sh = canonical list + Civo Alertmanager activation overlay (${#wrapper_files[@]} files)"

# --- Rendered models: pins and registration settings agree ---------------------
if ! docker compose version >/dev/null 2>&1; then
  fail "docker compose is required to render and compare the two models"
fi
synth="$shim/env"
cp docker/.env.example "$synth"
printf 'PARKIO_IMAGE_TAG=sha-canonical-file-set-test\n' >> "$synth"
render() { # out-file, then -f arguments
  local out="$1" err="$shim/err"; shift
  for _ in $(seq 1 40); do
    if docker compose --env-file "$synth" "$@" config --format json >"$out" 2>"$err"; then
      return 0
    fi
    local name
    name="$(sed -n 's/.*required variable \([A-Z0-9_]*\) is missing.*/\1/p' "$err" | head -n 1)"
    [ -n "$name" ] || { cat "$err" >&2; fail "compose render failed before a missing-variable message"; }
    printf '%s=example.invalid\n' "$name" >> "$synth"
  done
  fail "compose render still missing required variables"
}
deploy_render=()
for f in "${deploy_files[@]}"; do deploy_render+=(-f "$f"); done
wrapper_render=()
for f in "${wrapper_files[@]}"; do wrapper_render+=(-f "$f"); done
render "$shim/deploy.json" "${deploy_render[@]}"
render "$shim/wrapper.json" "${wrapper_render[@]}"
python3 - "$shim/deploy.json" "$shim/wrapper.json" <<'PY'
import json, sys
deploy = json.load(open(sys.argv[1], encoding="utf-8"))["services"]
wrapper = json.load(open(sys.argv[2], encoding="utf-8"))["services"]

def env(service):
    value = service.get("environment") or {}
    if isinstance(value, list):
        value = dict(item.split("=", 1) for item in value if "=" in item)
    return value

problems = []
pinned = sorted(name for name, svc in wrapper.items() if "@sha256:" in str(svc.get("image", "")))
if not pinned:
    problems.append("the production render pins no image by digest")
for name in pinned:
    got = (deploy.get(name) or {}).get("image")
    if got != wrapper[name]["image"]:
        problems.append(f"{name}: deploy path image {got!r} != production pin")
auth_deploy, auth_wrapper = env(deploy.get("auth-service", {})), env(wrapper.get("auth-service", {}))
registration = sorted(k for k in auth_wrapper if k.startswith("PARKIO_REGISTRATION_"))
if not registration:
    problems.append("production render sets no PARKIO_REGISTRATION_* on auth-service")
for key in registration:
    if auth_deploy.get(key) != auth_wrapper[key]:
        problems.append(f"auth-service {key}: deploy path {auth_deploy.get(key)!r} != production")
if problems:
    print("FAIL: deploy/rollback render disagrees with the production render:", file=sys.stderr)
    for problem in problems:
        print("  " + problem, file=sys.stderr)
    sys.exit(1)
print("PASS: rendered models agree on " + str(len(pinned)) + " digest-pinned images ("
      + ", ".join(pinned) + ") and " + str(len(registration)) + " auth registration settings")
PY
echo "=== canonical production file set: PASS ==="
