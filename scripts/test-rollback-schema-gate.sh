#!/usr/bin/env bash
# F-INV-3 (owner decision 2026-10-05): the rollback schema gate reads the deployed release's recorded
# manifest, never a checkout's current.json, and fails closed. The deploy and the rollback record
# that manifest before they start a release.
#
#   - parkio_deploy_state_dir: one directory per host that every deployer shares (#290 review B2):
#     the invite-production runtime root, /var/lib/parkio/<profile> for hosted-beta and
#     azure-hosted-beta whoever runs it, and the user's XDG state directory only for local-dev.
#     PARKIO_DEPLOY_STATE_DIR overrides them, and says so. A directory that cannot be written is
#     refused (exit 3).
#   - deploy-invite-production.sh, deploy-hosted-beta.sh and rollback-hosted-beta.sh record the
#     manifest before their parkio_compose_up; the rollback records it before it activates a
#     release or re-points an image (#290 review N4).
#   - azure-hosted-beta deploy manifests record their digest pins too (#290 review B1).
#   - A live invite-production rollback against a staged release (PARKIO_RUNTIME_ROOT in a temp dir,
#     a docker shim that answers `image inspect`, renders `compose ... config` with the real docker,
#     and stops at `compose ... up`; an `mv` shim that can fail one destination):
#       - without the record, or with the live schema ahead of the target, it is refused (exit 3)
#         before the release is activated or anything starts. A compatible current.json in the
#         artifact directory does not change that (#290 review T1);
#       - when the record cannot be written, it stops (exit 3) before activation, with the
#         previous record in place; when the activation fails after the record was written, the
#         previous record is restored (#290 review N4);
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
# Two deployers on one host, each with an own home and XDG state directory, read and write one record.
ops="$(state_dir PARKIO_DEPLOYMENT_PROFILE=hosted-beta HOME=/home/ops XDG_STATE_HOME=/home/ops/.local/state)"
runner="$(state_dir -u XDG_STATE_HOME PARKIO_DEPLOYMENT_PROFILE=hosted-beta HOME=/home/runner)"
[ "$ops" = /var/lib/parkio/hosted-beta ] && [ "$runner" = "$ops" ] \
  && pass "hosted-beta records in /var/lib/parkio/hosted-beta for every user, not in a user's home" \
  || bad "hosted-beta state dirs: ops '$ops', runner '$runner'"
[ "$(state_dir PARKIO_DEPLOYMENT_PROFILE=azure-hosted-beta HOME=/home/ops XDG_STATE_HOME=/x)" = /var/lib/parkio/azure-hosted-beta ] \
  && pass "azure-hosted-beta records in /var/lib/parkio/azure-hosted-beta" || bad "azure-hosted-beta state dir"
[ "$(state_dir PARKIO_DEPLOYMENT_PROFILE=local-dev XDG_STATE_HOME=/x/state)" = /x/state/parkio/local-dev ] \
  && pass "local-dev (a developer machine) records under the user's XDG state directory" || bad "local-dev state dir"
override_err="$(PARKIO_DEPLOYMENT_PROFILE=hosted-beta PARKIO_DEPLOY_STATE_DIR=/y bash -c \
  'source scripts/lib/deploy-common.sh; parkio_deploy_state_dir >/dev/null' 2>&1)"
[ "$(state_dir PARKIO_DEPLOYMENT_PROFILE=hosted-beta PARKIO_DEPLOY_STATE_DIR=/y 2>/dev/null)" = /y ] \
  && [[ "$override_err" == *"overridden by PARKIO_DEPLOY_STATE_DIR: /y"* ]] \
  && pass "PARKIO_DEPLOY_STATE_DIR overrides it, and says so" || bad "PARKIO_DEPLOY_STATE_DIR override: '$override_err'"
: >"$work/not-a-directory"
writable_rc=0
writable_err="$(PARKIO_DEPLOY_STATE_DIR="$work/not-a-directory/state" bash -c \
  'source scripts/lib/deploy-common.sh; parkio_assert_deploy_state_dir_writable' 2>&1)" || writable_rc=$?
[ "$writable_rc" -eq 3 ] && [[ "$writable_err" == *"is not writable by"* ]] \
  && pass "a deploy state directory that cannot be written is refused (exit 3)" \
  || bad "unwritable state dir: exit $writable_rc, '$writable_err'"

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
# #290 review N4: the rollback records before it activates a release or re-points an image.
record="$(grep -n '^if ! parkio_record_deployed_manifest "\$TARGET_RECORD"' scripts/rollback-hosted-beta.sh | cut -d: -f1)"
activate="$(grep -n '^  parkio_activate_release ' scripts/rollback-hosted-beta.sh | cut -d: -f1)"
retag="$(grep -n 'docker tag "\${REPOINT\[i\]}"' scripts/rollback-hosted-beta.sh | cut -d: -f1)"
if [ -n "$record" ] && [ -n "$activate" ] && [ -n "$retag" ] && [ "$record" -lt "$activate" ] && [ "$record" -lt "$retag" ]; then
  pass "rollback-hosted-beta.sh records (line $record) before it activates (line $activate) or re-points (line $retag)"
else
  bad "rollback order: record '${record:-none}', activation '${activate:-none}', re-pointing '${retag:-none}'"
fi

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
# An mv that fails when its destination ends with SCHEMA_TEST_MV_FAIL (fault injection for N4).
REAL_MV="$(command -v mv)"
cat >"$work/shim/mv" <<EOF
#!/usr/bin/env bash
if [ -n "\${SCHEMA_TEST_MV_FAIL:-}" ] && [[ "\${*: -1}" == *"\$SCHEMA_TEST_MV_FAIL" ]]; then
  echo "mv: injected failure for \${*: -1}" >&2
  exit 1
fi
exec "$REAL_MV" "\$@"
EOF
chmod +x "$work/shim/docker" "$work/shim/mv"
live() { # live NAME RUNTIME_ROOT TARGET_MANIFEST: a live rollback; exit code in $rc
  rc=0
  : >"$work/$1.docker"
  env -u PARKIO_DEPLOYMENT_PROFILE -u PARKIO_DEPLOY_STATE_DIR PATH="$work/shim:$PATH" \
    SCHEMA_TEST_MV_FAIL="${MV_FAIL:-}" \
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

# T1: a compatible current.json in the artifact directory, the record ahead: refused.
stage_release "$work/rt-t1"
cp "$work/rt-ahead/deployed-manifest.json" "$work/rt-t1/deployed-manifest.json"
mkdir -p "$work/artifacts-t1"
cp "$work/target-older.json" "$work/artifacts-t1/current.json"
live t1 "$work/rt-t1" "$work/target-older.json"
if [ "$rc" -eq 3 ] && grep -qF "the live schema is ahead of the rollback target (parking-service: $newest)" "$work/t1.err" \
  && untouched t1 "$work/rt-t1"; then
  pass "a compatible current.json in the artifact directory does not stand in for the record: refused before activation and start"
else
  bad "T1 (current.json compatible, record ahead): exit $rc, error: $(grep ERROR "$work/t1.err" | head -n 1)"
fi

# N4: the record cannot be written: nothing changes, and the previous record stays.
stage_release "$work/rt-nowrite"
jq '.gitSha = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"' "$work/target.json" >"$work/rt-nowrite/deployed-manifest.json"
cp "$work/rt-nowrite/deployed-manifest.json" "$work/nowrite-previous.json"
MV_FAIL=deployed-manifest.json live nowrite "$work/rt-nowrite" "$work/target.json"
if [ "$rc" -eq 3 ] && grep -qF "cannot record the target as the deployed release" "$work/nowrite.err" \
  && untouched nowrite "$work/rt-nowrite" && cmp -s "$work/nowrite-previous.json" "$work/rt-nowrite/deployed-manifest.json"; then
  pass "when the record cannot be written, the rollback stops (exit 3) before activation, with the previous record in place"
else
  bad "record write fails: exit $rc, current: $(readlink "$work/rt-nowrite/current" || echo none), error: $(grep ERROR "$work/nowrite.err" | head -n 1)"
fi

# N4: the activation fails after the record was written: the previous record is restored.
stage_release "$work/rt-noactivate"
jq '.gitSha = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"' "$work/target.json" >"$work/rt-noactivate/deployed-manifest.json"
cp "$work/rt-noactivate/deployed-manifest.json" "$work/noactivate-previous.json"
MV_FAIL=/current live noactivate "$work/rt-noactivate" "$work/target.json"
if [ "$rc" -ne 0 ] && grep -qF "restoring the deployed release's record" "$work/noactivate.err" \
  && untouched noactivate "$work/rt-noactivate" && cmp -s "$work/noactivate-previous.json" "$work/rt-noactivate/deployed-manifest.json"; then
  pass "when the activation fails after the record was written, the previous record is restored (exit $rc)"
else
  bad "activation fails: exit $rc, record: $(jq -r .gitSha "$work/rt-noactivate/deployed-manifest.json" 2>/dev/null), error: $(grep ERROR "$work/noactivate.err" | head -n 2 | tr '\n' ' ')"
fi

stage_release "$work/rt-ok"
jq '.gitSha = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"' "$work/target.json" >"$work/rt-ok/deployed-manifest.json"
live ok "$work/rt-ok" "$work/target.json"
# The start fails (web map guard) after the record was kept: it is not restored, because the
# target's containers may have started and its migrations may have run (#290 review N4).
if grep -qF "rollback schema gate: compatible" "$work/ok.out" \
  && [ "$(readlink "$work/rt-ok/current")" = "$work/rt-ok/releases/$SHA" ] \
  && cmp -s "$work/target.json" "$work/rt-ok/deployed-manifest.json" \
  && ! grep -qF "restoring the deployed release's record" "$work/ok.err" \
  && ! grep -q '^compose.* up' "$work/ok.docker"; then
  pass "with a compatible record, the rollback passes the gate, activates the target and records its manifest, and keeps it when the start then stops at the web map guard (exit $rc)"
else
  bad "compatible: exit $rc, current: $(readlink "$work/rt-ok/current" || echo none), error: $(head -n 2 "$work/ok.err" | tr '\n' ' ')"
fi

echo "--- azure-hosted-beta: its deploy records its digest pins, and its rollback holds them to the record's ---"
# B1: the deprecated azure-hosted-beta path renders the same production list, whose pins win over
# docker-compose.images.yml. Its manifest keeps every app service in `images` and records the pins.
AZURE_ENV="$work/azure.env"
cp docker/.env.azure-hosted-beta.example "$AZURE_ENV"
env -u PARKIO_DEPLOYMENT_PROFILE bash -c '
  set -euo pipefail
  source scripts/lib/deploy-common.sh
  PARKIO_DEPLOYMENT_PROFILE=azure-hosted-beta
  parkio_configure_deployment_profile "$1" >/dev/null
  export PARKIO_IMAGE_TAG="sha-$2" PARKIO_GIT_SHA="$2" PARKIO_IMAGE_CREATED=2026-10-05T00:00:00Z PARKIO_IMAGE_VERSION=test
  parkio_write_manifest "$3" deploy schema-gate-test "$1" "sha-$2" "$2" api 2026-10-05T00:00:00Z test "" "" >/dev/null
' _ "$AZURE_ENV" "$SHA" "$work/azure-target.json" 2>"$work/azure-write.err"
# The app services' digest pins in the files docker/compose.production.files lists, as service=image.
pins_expected="$(python3 - "$(bash -c 'source scripts/lib/deploy-common.sh; echo "${PARKIO_APP_SERVICES[*]}"')" <<'PY'
import re, sys
apps, pins = set(sys.argv[1].split()), {}
for entry in open("docker/compose.production.files", encoding="utf-8"):
    entry = entry.strip()
    if not entry or entry.startswith("#"):
        continue
    service = None
    for line in open(entry, encoding="utf-8"):
        key = re.fullmatch(r"  ([a-z][a-z0-9-]*):\s*", line.rstrip("\n"))
        if key:
            service = key.group(1)
        image = re.fullmatch(r"    image:\s*(\S+@sha256:[0-9a-f]{64})\s*", line.rstrip("\n"))
        if image and service in apps:
            pins[service] = image.group(1)
print("\n".join(f"{s}={i}" for s, i in sorted(pins.items())))
PY
)"
if [ -n "$pins_expected" ] \
  && [ "$(jq -r '.pinnedImages | to_entries | sort_by(.key)[] | "\(.key)=\(.value)"' "$work/azure-target.json")" = "$pins_expected" ] \
  && [ "$(jq '.images | length' "$work/azure-target.json")" -eq 11 ]; then
  pass "an azure-hosted-beta manifest records the production list's $(wc -l <<< "$pins_expected") digest pins and keeps all 11 images"
else
  bad "azure-hosted-beta manifest pins: $(jq -c '.pinnedImages' "$work/azure-target.json" 2>/dev/null), error: $(head -n 2 "$work/azure-write.err")"
fi
azure_live() { # azure_live NAME RECORD: a live azure-hosted-beta rollback to azure-target.json; exit code in $rc
  rc=0
  mkdir -p "$work/state-$1"
  cp "$2" "$work/state-$1/deployed-manifest.json"
  : >"$work/$1.docker"
  env -u PARKIO_DEPLOYMENT_PROFILE PATH="$work/shim:$PATH" PARKIO_DEPLOY_STATE_DIR="$work/state-$1" \
    SCHEMA_TEST_DOCKER_LOG="$work/$1.docker" SCHEMA_TEST_REAL_DOCKER="$REAL_DOCKER" \
    PARKIO_ENV_FILE="$AZURE_ENV" PARKIO_DEPLOY_ARTIFACT_DIR="$work/artifacts-$1" \
    ./scripts/rollback-hosted-beta.sh --manifest "$work/azure-target.json" --skip-smoke \
    >"$work/$1.out" 2>"$work/$1.err" || rc=$?
}
jq 'del(.pinnedImages)' "$work/azure-target.json" >"$work/azure-record-nopins.json"
azure_live azure-nopins "$work/azure-record-nopins.json"
if [ "$rc" -eq 3 ] && grep -qF "records no readable pinnedImages" "$work/azure-nopins.err" \
  && ! grep -v -- ' config' "$work/azure-nopins.docker" | grep -q '^compose'; then
  pass "an azure-hosted-beta rollback against a record without pinnedImages is refused (exit 3) before anything starts"
else
  bad "azure record without pins: exit $rc, error: $(grep ERROR "$work/azure-nopins.err" | head -n 1)"
fi
jq '.pinnedImages["auth-service"] |= sub("@sha256:[0-9a-f]+$"; "@sha256:" + ("0" * 64))' "$work/azure-target.json" >"$work/azure-record-otherpin.json"
azure_live azure-otherpin "$work/azure-record-otherpin.json"
if [ "$rc" -eq 3 ] && grep -qF "would change digest pins the deployed release runs (auth-service:" "$work/azure-otherpin.err" \
  && ! grep -v -- ' config' "$work/azure-otherpin.docker" | grep -q '^compose'; then
  pass "an azure-hosted-beta rollback whose pins differ from the record's is refused (exit 3) before anything starts"
else
  bad "azure pin differs: exit $rc, error: $(grep ERROR "$work/azure-otherpin.err" | head -n 1)"
fi

echo "=== rollback schema gate integration: $pass_n passed, $fail_n failed ==="
[ "$fail_n" -eq 0 ]
