#!/usr/bin/env bash
# CL-F12: the supported paths render docker/compose.production.files (owner decisions 1b, 2a).
#
#   default hosted-beta profile: deploy-hosted-beta.sh, rollback-hosted-beta.sh (also how the DR
#   runbook starts the stack) and validate-hosted-beta-compose.sh
#     = canonical list, exactly
#   scripts/parkio-prod-compose.sh (Civo production wrapper)
#     = canonical list, then docker-compose.civo-alertmanager.yml, a Civo-host-specific addition
#   azure-hosted-beta profile (deprecated; documented exception)
#     = canonical list, with docker-compose.images.yml right after docker-compose.apps.yml
#
# Fails when a compose file is added to only one of these paths, when the hosted-beta entry
# points render different models, and when a render disagrees with production on the
# digest-pinned images or the auth registration settings. Models are compared by SHA-256:
# deploy and rollback of the hosted-beta profile render one model. The wrapper's model differs
# from it, and the test proves that the Civo overlay is the only difference. The Azure model
# differs by docker-compose.images.yml.
#
# The hosted-beta entry points run as dry runs with a docker shim. The shim renders through the
# real docker compose and refuses every other docker command, so nothing is built, pulled or
# started.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
fail() { echo "FAIL: $*" >&2; exit 1; }
pass() { echo "PASS: $*"; }

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

canonical=()
while IFS= read -r line || [ -n "$line" ]; do
  [[ -z "$line" || "$line" =~ ^[[:space:]]*# ]] && continue
  canonical+=("$line")
done < docker/compose.production.files
[ "${#canonical[@]}" -gt 0 ] || fail "docker/compose.production.files lists no files"

# The -f values of a "-f a -f b" word list, one per line.
file_args() {
  local -a words
  read -r -a words <<< "$1"
  local i
  for ((i = 0; i < ${#words[@]}; i++)); do
    [ "${words[i]}" = "-f" ] && printf '%s\n' "${words[i + 1]}"
  done
  return 0
}

# --- Default profile selection -------------------------------------------------
# An env file without PARKIO_DEPLOYMENT_PROFILE selects hosted-beta. The lookup used to fail on the
# absent key under pipefail and end a `set -euo pipefail` caller silently (exit 1), so the default
# profile's entry points stopped before rendering anything.
no_profile_env="$work/no-profile.env"
grep -v '^PARKIO_DEPLOYMENT_PROFILE=' docker/.env.hosted-beta.example > "$no_profile_env" || true
resolved_rc=0
resolved="$(env -u PARKIO_DEPLOYMENT_PROFILE bash -c 'set -euo pipefail
  source scripts/lib/deploy-common.sh
  parkio_configure_deployment_profile "$1" >/dev/null
  printf "%s\n%s" "$PARKIO_DEPLOYMENT_PROFILE" "$PARKIO_COMPOSE_FILES"' _ "$no_profile_env")" || resolved_rc=$?
[ "$resolved_rc" -eq 0 ] || fail "an env file without PARKIO_DEPLOYMENT_PROFILE ends a set -euo pipefail caller (exit $resolved_rc)"
[ "$(head -n 1 <<< "$resolved")" = "hosted-beta" ] \
  || fail "an env file without PARKIO_DEPLOYMENT_PROFILE selected '$(head -n 1 <<< "$resolved")', not hosted-beta"
pass "an env file without PARKIO_DEPLOYMENT_PROFILE selects hosted-beta under set -euo pipefail"

# --- hosted-beta profile ---------------------------------------------------------
mapfile -t hb_files < <(file_args "$(tail -n 1 <<< "$resolved")")
[ "${hb_files[*]}" = "${canonical[*]}" ] || fail "hosted-beta profile files drifted from docker/compose.production.files
  got:      ${hb_files[*]}
  expected: ${canonical[*]}"
pass "hosted-beta profile = docker/compose.production.files exactly (${#hb_files[@]} files)"

# --- azure-hosted-beta profile (deprecated; documented exception: images.yml) ----
deploy_args="$(bash -c 'set -e
  source scripts/lib/deploy-common.sh
  PARKIO_DEPLOYMENT_PROFILE=azure-hosted-beta
  parkio_configure_deployment_profile docker/.env.azure-hosted-beta.example >/dev/null
  printf "%s" "$PARKIO_COMPOSE_FILES"')"
mapfile -t deploy_files < <(file_args "$deploy_args")
expected_deploy=()
for f in "${canonical[@]}"; do
  expected_deploy+=("$f")
  [ "$f" = "docker/docker-compose.apps.yml" ] && expected_deploy+=("docker/docker-compose.images.yml")
done
[ "${deploy_files[*]}" = "${expected_deploy[*]}" ] || fail "deploy/rollback azure-hosted-beta files drifted from docker/compose.production.files
  got:      ${deploy_files[*]}
  expected: ${expected_deploy[*]}"
pass "deploy/rollback azure-hosted-beta profile = canonical list + images.yml after apps.yml (${#deploy_files[@]} files)"

# --- Civo production wrapper -------------------------------------------------------
mkdir -p "$work/argv-shim"
cat > "$work/argv-shim/docker" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "$@" > "$PARKIO_TEST_DOCKER_ARGV"
EOF
chmod +x "$work/argv-shim/docker"
PARKIO_TEST_DOCKER_ARGV="$work/argv" PATH="$work/argv-shim:$PATH" \
  PARKIO_ENV_FILE="$ROOT/docker/.env.azure-hosted-beta.example" \
  bash scripts/parkio-prod-compose.sh ps >/dev/null
mapfile -t argv < "$work/argv"
[ "${argv[0]:-}" = "compose" ] && [ "${argv[${#argv[@]} - 1]}" = "ps" ] \
  || fail "parkio-prod-compose.sh did not run 'docker compose ... ps'"
wrapper_files=()
wrapper_globals=() # the wrapper's compose arguments, without its env file and the subcommand
for ((i = 1; i < ${#argv[@]} - 1; i++)); do
  case "${argv[i]}" in
    --env-file) i=$((i + 1)) ;;
    -f) wrapper_files+=("${argv[i + 1]#"$ROOT/"}"); wrapper_globals+=(-f "${argv[i + 1]#"$ROOT/"}"); i=$((i + 1)) ;;
    *) wrapper_globals+=("${argv[i]}") ;;
  esac
done
expected_wrapper=("${canonical[@]}" docker/docker-compose.civo-alertmanager.yml)
[ "${wrapper_files[*]}" = "${expected_wrapper[*]}" ] || fail "parkio-prod-compose.sh files drifted from docker/compose.production.files
  got:      ${wrapper_files[*]}
  expected: ${expected_wrapper[*]}"
pass "parkio-prod-compose.sh = canonical list + Civo Alertmanager activation overlay (${#wrapper_files[@]} files)"

# --- hosted-beta entry points, end to end (dry runs) -------------------------------
real_docker="$(command -v docker)" || fail "docker is required to render the models"
command -v jq >/dev/null 2>&1 || fail "jq is required by the deploy scripts"
mkdir -p "$work/shim"
cat > "$work/shim/docker" <<'EOF'
#!/usr/bin/env bash
# Records every call. Forwards `compose ... config` (hashing JSON renders) and `image inspect` to
# the real docker, and refuses everything else.
{ printf '%s\x1f' "$@"; printf '\n'; } >> "$CFS_DOCKER_LOG"
if [ "${1:-}" = "compose" ]; then
  args=("$@")
  sub=""
  for ((k = 1; k < ${#args[@]}; k++)); do
    case "${args[k]}" in
      --env-file|-f|--file|-p|--project-name|--profile|--project-directory) k=$((k + 1)) ;;
      -*) ;;
      *) sub="${args[k]}"; break ;;
    esac
  done
  if [ "$sub" = "config" ]; then
    case " $* " in
      *" --format json "*)
        out="$(mktemp "$CFS_DOCKER_DIR/render.XXXXXX")"
        rc=0
        "$CFS_REAL_DOCKER" "$@" > "$out" || rc=$?
        cat "$out"
        if [ "$rc" -eq 0 ]; then sha256sum < "$out" | cut -d' ' -f1 >> "$CFS_DOCKER_LOG.json"; fi
        rm -f "$out"
        exit "$rc"
        ;;
    esac
    exec "$CFS_REAL_DOCKER" "$@"
  fi
elif [ "${1:-}" = "image" ] && [ "${2:-}" = "inspect" ]; then
  exec "$CFS_REAL_DOCKER" "$@"
fi
echo "canonical file set test: docker shim refused: docker $*" >&2
exit 97
EOF
chmod +x "$work/shim/docker"

# The synthetic preflight fixture passes the deploy preflight. It names no profile.
entry_env="$work/entry.env"
grep -v '^PARKIO_DEPLOYMENT_PROFILE=' scripts/preflight-fixtures/valid.env > "$entry_env" || true
run_entry() { # run_entry NAME COMMAND...
  local name="$1" rc=0
  shift
  CFS_DOCKER_LOG="$work/$name.calls" CFS_DOCKER_DIR="$work" CFS_REAL_DOCKER="$real_docker" \
    PATH="$work/shim:$PATH" PARKIO_ENV_FILE="$entry_env" PARKIO_DEPLOY_ARTIFACT_DIR="$work/artifacts" \
    PARKIO_DEPLOY_MIN_FREE_BYTES=1 PARKIO_DEPLOY_OPERATOR=canonical-file-set-test \
    env -u PARKIO_DEPLOYMENT_PROFILE "$@" > "$work/$name.log" 2>&1 || rc=$?
  if [ "$rc" -ne 0 ]; then
    tail -n 20 "$work/$name.log" >&2
    fail "$name dry run failed (exit $rc)"
  fi
  local calls=0 call
  while IFS= read -r call; do
    [[ "$call" == compose$'\x1f'* ]] || continue
    calls=$((calls + 1))
    local -a words used=()
    IFS=$'\x1f' read -r -a words <<< "$call"
    local j
    for ((j = 0; j < ${#words[@]}; j++)); do
      [ "${words[j]}" = "-f" ] && used+=("${words[j + 1]#"$ROOT/"}")
    done
    [ "${used[*]}" = "${canonical[*]}" ] || fail "$name rendered other files than docker/compose.production.files
  got:      ${used[*]}
  expected: ${canonical[*]}"
  done < "$work/$name.calls"
  [ "$calls" -gt 0 ] || fail "$name made no compose call"
  pass "$name uses docker/compose.production.files exactly in all $calls compose calls"
}
run_entry deploy ./scripts/deploy-hosted-beta.sh --dry-run --allow-dirty
mapfile -t deploy_manifests < <(find "$work/artifacts" -maxdepth 1 -name 'deploy-*.json')
[ "${#deploy_manifests[@]}" -eq 1 ] || fail "deploy dry run wrote ${#deploy_manifests[@]} manifests, not 1"
run_entry rollback ./scripts/rollback-hosted-beta.sh --dry-run --manifest "${deploy_manifests[0]}"
mapfile -t rollback_manifests < <(find "$work/artifacts" -maxdepth 1 -name 'rollback-to-*.json')
[ "${#rollback_manifests[@]}" -eq 1 ] || fail "rollback dry run wrote ${#rollback_manifests[@]} manifests, not 1"
run_entry validate ./scripts/validate-hosted-beta-compose.sh

# Deploy and rollback render their image plan from one JSON model each: one hash in the profile.
deploy_hash="$(sort -u "$work/deploy.calls.json")"
rollback_hash="$(sort -u "$work/rollback.calls.json")"
[ "$(wc -l <<< "$deploy_hash")" -eq 1 ] || fail "deploy rendered more than one JSON model"
[ "$deploy_hash" = "$rollback_hash" ] || fail "deploy and rollback render different models
  deploy:   $deploy_hash
  rollback: $rollback_hash"
pass "deploy and rollback render one model (sha256 ${deploy_hash:0:12})"

app_services="$(bash -c 'source scripts/lib/deploy-common.sh; printf "%s\n" "${PARKIO_APP_SERVICES[@]}"')"

# --- Rendered models: hashes, pins and registration settings -----------------------
synth="$work/env"
cp docker/.env.example "$synth"
printf 'PARKIO_IMAGE_TAG=sha-canonical-file-set-test\n' >> "$synth"
render() { # out-file, then compose arguments
  local out="$1" err="$work/err"; shift
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
as_args() { local f; for f in "$@"; do printf -- '-f\n%s\n' "$f"; done; }
mapfile -t hb_render < <(as_args "${hb_files[@]}")
mapfile -t hb_civo_render < <(as_args "${hb_files[@]}" docker/docker-compose.civo-alertmanager.yml)
mapfile -t azure_render < <(as_args "${deploy_files[@]}")
# The first round adds the placeholders every render needs, so the second round renders all four
# models from one synthetic env.
for _ in 1 2; do
  render "$work/wrapper.json" "${wrapper_globals[@]}"
  render "$work/hosted-beta.json" "${hb_render[@]}"
  render "$work/hosted-beta-civo.json" "${hb_civo_render[@]}"
  render "$work/azure.json" "${azure_render[@]}"
done

sha() { sha256sum "$1" | cut -d' ' -f1; }
[ "$(sha "$work/hosted-beta.json")" != "$(sha "$work/wrapper.json")" ] \
  || fail "the hosted-beta and wrapper models are identical; the Civo overlay has no effect"
[ "$(sha "$work/hosted-beta-civo.json")" = "$(sha "$work/wrapper.json")" ] \
  || fail "the wrapper model differs from the hosted-beta model by more than the Civo Alertmanager overlay"
pass "the wrapper model differs from the hosted-beta model only by the Civo Alertmanager overlay"
[ "$(sha "$work/azure.json")" != "$(sha "$work/hosted-beta.json")" ] \
  || fail "the azure-hosted-beta and hosted-beta models are identical; images.yml has no effect"
pass "the azure-hosted-beta model differs from the hosted-beta model (documented exception: images.yml)"

python3 - "$work/wrapper.json" "$work/hosted-beta.json" "$work/azure.json" \
  "${deploy_manifests[0]}" "${rollback_manifests[0]}" "$app_services" "${canonical[@]}" <<'PY'
import json, sys
wrapper = json.load(open(sys.argv[1], encoding="utf-8"))["services"]
renders = {"hosted-beta": json.load(open(sys.argv[2], encoding="utf-8"))["services"],
           "azure-hosted-beta": json.load(open(sys.argv[3], encoding="utf-8"))["services"]}
manifests = {"deploy": json.load(open(sys.argv[4], encoding="utf-8")),
             "rollback": json.load(open(sys.argv[5], encoding="utf-8"))}
app_services = sys.argv[6].split()
canonical = sys.argv[7:]

def env(service):
    value = service.get("environment") or {}
    if isinstance(value, list):
        value = dict(item.split("=", 1) for item in value if "=" in item)
    return value

problems = []
pinned = sorted(name for name, svc in wrapper.items() if "@sha256:" in str(svc.get("image", "")))
if not pinned:
    problems.append("the production render pins no image by digest")
auth_wrapper = env(wrapper.get("auth-service", {}))
registration = sorted(k for k in auth_wrapper if k.startswith("PARKIO_REGISTRATION_"))
if not registration:
    problems.append("production render sets no PARKIO_REGISTRATION_* on auth-service")
for label, services in renders.items():
    for name in pinned:
        got = (services.get(name) or {}).get("image")
        if got != wrapper[name]["image"]:
            problems.append(f"{label}: {name} image {got!r} != production pin")
    auth = env(services.get("auth-service", {}))
    for key in registration:
        if auth.get(key) != auth_wrapper[key]:
            problems.append(f"{label}: auth-service {key} {auth.get(key)!r} != production")

# The hosted-beta manifests: the canonical files, built images under the deploy's tag, and the
# production digest pins for everything else.
app_pins = {name: wrapper[name]["image"] for name in pinned if name in app_services}
for label, m in manifests.items():
    if m.get("deploymentProfile") != "hosted-beta":
        problems.append(f"{label} manifest profile {m.get('deploymentProfile')!r}, not hosted-beta")
    if m.get("composeFiles") != canonical:
        problems.append(f"{label} manifest composeFiles {m.get('composeFiles')} != docker/compose.production.files")
    images, pins, tag = m.get("images") or {}, m.get("pinnedImages") or {}, m.get("imageTag") or ""
    if not images:
        problems.append(f"{label} manifest records no built image")
    if sorted(set(images) | set(pins)) != sorted(app_services) or set(images) & set(pins):
        problems.append(f"{label} manifest images {sorted(images)} and pins {sorted(pins)} do not split the app services")
    off = sorted(svc for svc, ref in images.items() if ref != f"parkio/{svc}:{tag}")
    if off:
        problems.append(f"{label} manifest images not named parkio/<service>:{tag}: {', '.join(off)}")
    if pins != app_pins:
        problems.append(f"{label} manifest pins {pins} != production app pins {app_pins}")
if problems:
    print("FAIL: a render or manifest disagrees with production:", file=sys.stderr)
    for problem in problems:
        print("  " + problem, file=sys.stderr)
    sys.exit(1)
print(f"PASS: hosted-beta and azure-hosted-beta renders agree with production on {len(pinned)} digest-pinned "
      f"images ({', '.join(pinned)}) and {len(registration)} auth registration settings")
print(f"PASS: deploy and rollback manifests record the canonical files, {len(manifests['deploy']['images'])} "
      f"built images and {len(app_pins)} app pins")
PY
echo "=== canonical production file set: PASS ==="
