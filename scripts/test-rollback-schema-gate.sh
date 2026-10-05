#!/usr/bin/env bash
# F-INV-3 (owner decision 2026-10-05): the rollback schema gate reads the deployed release's recorded
# manifest, never a checkout's current.json, and fails closed. The deploy and the rollback record
# that manifest before they start a release.
#
#   - parkio_deploy_state_dir: the invite-production runtime root; for other profiles, the user's
#     XDG state directory; PARKIO_DEPLOY_STATE_DIR overrides both.
#   - deploy-invite-production.sh, deploy-hosted-beta.sh and rollback-hosted-beta.sh record the
#     manifest before their parkio_compose_up.
#   - A live invite-production rollback against a staged release (PARKIO_RUNTIME_ROOT in a temp dir,
#     a docker shim that answers `image inspect`, renders `compose ... config` with the real docker,
#     and stops at `compose ... up`):
#       - without the record, or with the live schema ahead of the target, it is refused (exit 3)
#         before the release is activated or anything starts;
#       - with a compatible record, it passes the gate, activates the target and records the
#         target's manifest. The start itself then stops at the web map guard, because the
#         example's MapTiler key is a placeholder; the static check above places the record
#         before parkio_compose_up.
# The target manifest is realistic: written by parkio_write_manifest for the invite example env.
# scripts/test_rollback_schema_gate.py covers the comparison itself; scripts/test-hosted-beta-image-plan.sh
# covers the hosted-beta deploy and rollback.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
pass_n=0
fail_n=0
pass() { echo "PASS $*"; pass_n=$((pass_n + 1)); }
bad() { echo "FAIL $*"; fail_n=$((fail_n + 1)); }

work="$(mktemp -d)"
trap 'rm -rf "${work:?}"' EXIT
chmod 755 "$work"
REAL_DOCKER="$(command -v docker)" || { echo "FAIL docker is required to render the compose model"; exit 1; }
SHA=7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e
ENV_FILE="$work/invite.env"
cp docker/.env.invite-production.example "$ENV_FILE"

echo "--- where the record lives ---"
state_dir() { env -u PARKIO_DEPLOY_STATE_DIR "$@" bash -c 'source scripts/lib/deploy-common.sh; parkio_deploy_state_dir'; }
[ "$(state_dir PARKIO_DEPLOYMENT_PROFILE=invite-production PARKIO_RUNTIME_ROOT=/srv/rt)" = /srv/rt ] \
  && pass "invite-production records beside its runtime release link" || bad "invite-production state dir"
[ "$(state_dir -u PARKIO_RUNTIME_ROOT PARKIO_DEPLOYMENT_PROFILE=invite-production)" = /opt/parkio/invite-production ] \
  && pass "invite-production defaults to /opt/parkio/invite-production" || bad "invite-production default state dir"
[ "$(state_dir PARKIO_DEPLOYMENT_PROFILE=hosted-beta XDG_STATE_HOME=/x/state)" = /x/state/parkio/hosted-beta ] \
  && pass "hosted-beta records under the user's XDG state directory" || bad "hosted-beta state dir"
[ "$(state_dir PARKIO_DEPLOYMENT_PROFILE=hosted-beta PARKIO_DEPLOY_STATE_DIR=/y)" = /y ] \
  && pass "PARKIO_DEPLOY_STATE_DIR overrides it" || bad "PARKIO_DEPLOY_STATE_DIR override"

echo "--- the record is written before the release starts ---"
for script in scripts/deploy-invite-production.sh scripts/deploy-hosted-beta.sh scripts/rollback-hosted-beta.sh; do
  record="$(grep -n 'parkio_record_deployed_manifest ' "$script" | head -n 1 | cut -d: -f1)"
  up="$(grep -n '^parkio_compose_up ' "$script" | tail -n 1 | cut -d: -f1)"
  if [ -n "$record" ] && [ -n "$up" ] && [ "$record" -lt "$up" ]; then
    pass "$script records the manifest (line $record) before parkio_compose_up (line $up)"
  else
    bad "$script: record line '${record:-none}', parkio_compose_up line '${up:-none}'"
  fi
done

echo "--- live invite-production rollback against a staged release ---"
# A realistic target: parkio_write_manifest for the invite example, as a deploy writes it.
env -u PARKIO_DEPLOYMENT_PROFILE bash -c '
  set -euo pipefail
  source scripts/lib/deploy-common.sh
  PARKIO_DEPLOYMENT_PROFILE=invite-production
  parkio_configure_deployment_profile "$1" >/dev/null
  export PARKIO_IMAGE_TAG="sha-$2" PARKIO_GIT_SHA="$2" PARKIO_IMAGE_CREATED=2026-10-05T00:00:00Z PARKIO_IMAGE_VERSION=test
  parkio_write_manifest "$3" deploy schema-gate-test "$1" "sha-$2" "$2" api 2026-10-05T00:00:00Z test "" "" >/dev/null
' _ "$ENV_FILE" "$SHA" "$work/target.json"
jq -e '.migrationVersions["parking-service"] | length > 0' "$work/target.json" >/dev/null \
  || bad "the target manifest records no parking-service migrations"

# A runtime root holding a release staged from a minimal git tree: tracked docker/ files and the
# extra paths a release carries.
stage_release() { # stage_release RUNTIME_ROOT
  local runtime_root="$1" repo="$1.repo" rel
  mkdir -p "$repo" "$runtime_root"
  chmod 755 "$runtime_root"
  # shellcheck source=lib/runtime-release.sh
  ( source scripts/lib/runtime-release.sh
    while IFS= read -r -d '' rel; do
      mkdir -p "$repo/$(dirname "$rel")"; cp -p "$rel" "$repo/$rel"
    done < <(git ls-files -z -- docker)
    for rel in "${PARKIO_RUNTIME_RELEASE_EXTRA_TRACKED_PATHS[@]}"; do
      mkdir -p "$repo/$(dirname "$rel")"; cp -p "$rel" "$repo/$rel"
    done
    git -C "$repo" init -q
    git -C "$repo" add -A
    git -C "$repo" -c user.name=schema-gate-test -c user.email=schema-gate-test@example.invalid commit -qm release
    PARKIO_RUNTIME_ROOT="$runtime_root" parkio_stage_runtime_release "$repo" "$SHA" >/dev/null )
}
mkdir -p "$work/shim"
cat >"$work/shim/docker" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "$*" >>"$SCHEMA_TEST_DOCKER_LOG"
[ "${1:-}" = "image" ] && [ "${2:-}" = "inspect" ] && exit 0
if [ "${1:-}" = "compose" ]; then
  for a in "$@"; do
    [ "$a" = "config" ] && exec "$SCHEMA_TEST_REAL_DOCKER" "$@"
      done
fi
echo "schema gate test docker shim: refused: docker $*" >&2
exit 97
EOF
chmod +x "$work/shim/docker"
live() { # live NAME RUNTIME_ROOT TARGET_MANIFEST: a live rollback; exit code in $rc
  rc=0
  : >"$work/$1.docker"
  env -u PARKIO_DEPLOYMENT_PROFILE -u PARKIO_DEPLOY_STATE_DIR PATH="$work/shim:$PATH" \
    SCHEMA_TEST_DOCKER_LOG="$work/$1.docker" SCHEMA_TEST_REAL_DOCKER="$REAL_DOCKER" \
    PARKIO_RUNTIME_ROOT="$2" PARKIO_ENV_FILE="$ENV_FILE" PARKIO_DEPLOY_ARTIFACT_DIR="$work/artifacts-$1" \
    ./scripts/rollback-invite-production.sh --manifest "$3" --skip-smoke \
    >"$work/$1.out" 2>"$work/$1.err" || rc=$?
}
untouched() { # untouched NAME RUNTIME_ROOT: nothing activated or started
  [ ! -e "$2/current" ] && ! grep -v -- ' config' "$work/$1.docker" | grep -q '^compose'
}

stage_release "$work/rt-none"
live none "$work/rt-none" "$work/target.json"
if [ "$rc" -eq 3 ] && grep -qF "the deployed release's manifest is missing" "$work/none.err" && untouched none "$work/rt-none"; then
  pass "without the deployed release's record, the rollback is refused before activation and start"
else
  bad "no record: exit $rc, error: $(grep ERROR "$work/none.err" | head -n 1)"
fi

stage_release "$work/rt-ahead"
newest="$(jq -r '.migrationVersions["parking-service"] | max_by(capture("^V(?<v>[0-9]+)").v | tonumber)' "$work/target.json")"
# The deployed release applied every script; the target lacks the newest parking-service one.
jq '.gitSha = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"' "$work/target.json" >"$work/rt-ahead/deployed-manifest.json"
jq --arg newest "$newest" '.migrationVersions["parking-service"] -= [$newest]' "$work/target.json" >"$work/target-older.json"
live ahead "$work/rt-ahead" "$work/target-older.json"
if [ "$rc" -eq 3 ] && grep -qF "the live schema is ahead of the rollback target (parking-service: $newest)" "$work/ahead.err" \
  && untouched ahead "$work/rt-ahead"; then
  pass "with the live schema ahead of the target ($newest), the rollback is refused before activation and start"
else
  bad "schema ahead: exit $rc, error: $(grep ERROR "$work/ahead.err" | head -n 1)"
fi

stage_release "$work/rt-ok"
jq '.gitSha = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"' "$work/target.json" >"$work/rt-ok/deployed-manifest.json"
live ok "$work/rt-ok" "$work/target.json"
if grep -qF "rollback schema gate: compatible" "$work/ok.out" \
  && [ "$(readlink "$work/rt-ok/current")" = "$work/rt-ok/releases/$SHA" ] \
  && cmp -s "$work/target.json" "$work/rt-ok/deployed-manifest.json" \
  && ! grep -q '^compose.* up' "$work/ok.docker"; then
  pass "with a compatible record, the rollback passes the gate, activates the target and records its manifest (the start then stops at the web map guard, exit $rc)"
else
  bad "compatible: exit $rc, current: $(readlink "$work/rt-ok/current" || echo none), error: $(head -n 2 "$work/ok.err" | tr '\n' ' ')"
fi

echo "=== rollback schema gate integration: $pass_n passed, $fail_n failed ==="
[ "$fail_n" -eq 0 ]
