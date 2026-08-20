#!/usr/bin/env bash
#
# PROD-DEPLOY-01A-R8 regression gates.
#
# Covers the three defects that took down the first real invite-production
# bring-up. These run everywhere (no self-hosted runner required); the live
# container proof is scripts/test-invite-production-release-containers.sh.
#
#   R8-A  the runner keeps UMask=0077
#   R8-B  runtime bind mounts never resolve under _work / GITHUB_WORKSPACE
#   R8-C  ephemeral cleanup cannot remove active runtime config
#   R8-D  staged config is readable by non-root container UIDs (mode proof)
#   R8-E  the parking application role is not required to CREATE EXTENSION
#   R8-F  the PostGIS bootstrap is idempotent
#   R8-G  the PostGIS bootstrap cannot be pointed at hosted-beta

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/scripts/lib/runtime-release.sh"

PASS=0
FAIL=0
ok()   { echo "  PASS  $*"; PASS=$((PASS + 1)); }
bad()  { echo "  FAIL  $*" >&2; FAIL=$((FAIL + 1)); }
check() { if eval "$2"; then ok "$1"; else bad "$1"; fi; }

TMP="$(mktemp -d)"
trap 'rm -rf -- "$TMP"' EXIT
# Model the provisioned runtime root, which install-invite-production-runtime-root.sh
# guarantees is 0755 all the way down. mktemp gives 0700, which would make the
# traversal assertion fail on the harness rather than on the code under test.
chmod 0755 "$TMP"

SHA="$(git -C "$ROOT" rev-parse HEAD)"

echo "== R8-A: runner hardening keeps UMask=0077 =="
installer="$ROOT/scripts/azure/install-invite-production-runner.sh"
check "runner systemd override still sets UMask=0077" \
  "grep -q \"'UMask=0077'\" '$installer'"
check "runner installer does not chmod 777" \
  "! grep -qE 'chmod +(-R +)?777' '$installer'"
check "runtime-root provisioning does not chmod 777" \
  "! grep -qE 'chmod +(-R +)?777' '$ROOT/scripts/azure/install-invite-production-runtime-root.sh'"
check "runtime-root provisioning creates a runner-owned acceptance area" \
  "grep -q 'RUNTIME_ROOT/acceptance' '$ROOT/scripts/azure/install-invite-production-runtime-root.sh'"
check "runtime-root provisioning re-asserts runner ownership" \
  "grep -q 'chown \"\$RUNNER_USER\"' '$ROOT/scripts/azure/install-invite-production-runtime-root.sh'"
check "runtime-root provisioning keeps certs restricted" \
  "grep -q 'chmod 0750 \"\$restricted\"' '$ROOT/scripts/azure/install-invite-production-runtime-root.sh'"

echo "== R8-B: runtime release lives outside the Actions workspace =="
check "release root default is under /opt/parkio" \
  "[ \"\$(PARKIO_RUNTIME_ROOT= parkio_runtime_root)\" = /opt/parkio/invite-production ]"
if ( GITHUB_WORKSPACE=/opt/actions-runner/x/_work/parkio/parkio \
     parkio_assert_release_is_stable /opt/actions-runner/x/_work/parkio/parkio/source-1-1/docker >/dev/null 2>&1 ); then
  bad "workspace-resident release path was accepted"
else
  ok "workspace-resident release path is rejected"
fi
check "a stable release path is accepted" \
  "parkio_assert_release_is_stable /opt/parkio/invite-production/releases/$SHA >/dev/null"
check "deploy points Compose at the staged release" \
  "grep -q 'PARKIO_COMPOSE_BASE_DIR=\"\$RELEASE_DIR\"' '$ROOT/scripts/deploy-invite-production.sh'"
check "deploy builds from the checkout, not the release" \
  "grep -q 'parkio_compose_build \"\$ENV_FILE\" build' '$ROOT/scripts/deploy-invite-production.sh'"

echo "== R8-D: staging produces container-readable config =="
export PARKIO_RUNTIME_ROOT="$TMP/runtime"
install -d -m 0755 "$PARKIO_RUNTIME_ROOT" "$PARKIO_RUNTIME_ROOT/releases"
# Stage under a deliberately hostile umask: the real runner runs with 0077.
( umask 0077; parkio_stage_runtime_release "$ROOT" "$SHA" >/dev/null )
RELEASE="$(parkio_release_dir "$SHA")"
check "staged release exists" "[ -d '$RELEASE' ]"
check "prometheus config was staged" "[ -f '$RELEASE/docker/prometheus/prometheus.yml' ]"
check "loki config was staged" "[ -f '$RELEASE/docker/loki/loki.yml' ]"
check "tempo config was staged" "[ -f '$RELEASE/docker/tempo/tempo.yml' ]"
check "alertmanager render script was staged" "[ -f '$RELEASE/docker/alertmanager/render-config.sh' ]"
check "staged config is world-readable despite umask 0077" \
  "parkio_assert_release_readable '$SHA' >/dev/null"
check "alertmanager render script stays executable" \
  "[ -x '$RELEASE/docker/alertmanager/render-config.sh' ]"
check "no env material was staged" \
  "! find '$RELEASE' -name '.env' -o -name '.env.*' | grep -q ."
check "compose build context (..) exists in the release" "[ -d '$RELEASE' ]"

echo "== R8-H: a staged release is immutable and never re-created in place =="
# Re-staging must not delete and rebuild the tree: containers already running
# against this release bind-mount straight into it, so a rebuild would recreate
# DEFECT-2 during a re-deploy or a retention pass.
marker="$RELEASE/docker/prometheus/prometheus.yml"
inode_before="$(stat -c '%i' "$marker")"
( umask 0077; parkio_stage_runtime_release "$ROOT" "$SHA" >/dev/null )
inode_after="$(stat -c '%i' "$marker")"
check "re-staging leaves the existing release untouched" \
  "[ '$inode_before' = \"\$(stat -c '%i' '$marker')\" ]"
check "re-staging keeps the release readable" \
  "parkio_assert_release_readable '$SHA' >/dev/null"
check "deploy prunes without re-staging" \
  "grep -q -- '--prune-only' '$ROOT/scripts/deploy-invite-production.sh'"
check "prune-only is a supported staging mode" \
  "grep -q -- '--prune-only) PRUNE=1; PRUNE_ONLY=1' '$ROOT/scripts/stage-invite-production-release.sh'"

echo "== R8-C: cleanup cannot remove active runtime config =="
check "activation produces a stable symlink" \
  "parkio_activate_release '$SHA' >/dev/null && [ -L \"\$(parkio_current_link)\" ]"
check "active release resolves to the staged SHA" \
  "[ \"\$(parkio_active_release_sha)\" = '$SHA' ]"
cleanup_script="$ROOT/scripts/cleanup-invite-production-job.sh"
check "cleanup asserts no running container mounts the workspace" \
  "grep -q 'bind-mount paths from the ephemeral workspace' '$cleanup_script'"
check "cleanup only ever deletes per-job paths" \
  "! grep -qE 'rm -rf .*(/opt/parkio|releases)' '$cleanup_script'"
# Simulate the R7 failure: cleanup deletes the checkout; the release must survive.
fake_ws="$TMP/_work/parkio/parkio/source-1-1"
install -d -m 0700 "$fake_ws/docker"
rm -rf -- "$fake_ws"
check "release survives deletion of the ephemeral checkout" \
  "[ -f '$RELEASE/docker/prometheus/prometheus.yml' ]"

echo "== R8-E: parking application role never issues CREATE EXTENSION =="
managed="$ROOT/docker/docker-compose.managed-db.yml"
check "parking baselines Flyway past V1 on the managed profile" \
  "grep -q 'SPRING_FLYWAY_BASELINE_ON_MIGRATE: \"true\"' '$managed'"
check "parking baseline version is 1" \
  "grep -q 'SPRING_FLYWAY_BASELINE_VERSION: \"1\"' '$managed'"
v1="$ROOT/services/parking-service/src/main/resources/db/migration/V1__enable_postgis.sql"
check "V1 is unmodified (checksum-compatible with existing environments)" \
  "[ \"\$(git -C '$ROOT' diff --name-only HEAD -- '$v1' | wc -l)\" -eq 0 ]"

echo "== R8-F/R8-G: PostGIS bootstrap is idempotent and target-locked =="
boot="$ROOT/scripts/azure/bootstrap-invite-production-databases.sh"
check "bootstrap creates PostGIS idempotently" \
  "grep -q 'CREATE EXTENSION IF NOT EXISTS postgis' '$boot'"
check "bootstrap runs as the administrator identity" \
  "grep -q 'POSTGRES_ADMIN_USER=\"parkioops\"' '$boot'"
check "bootstrap targets only the invite-production resource group" \
  "grep -q 'RESOURCE_GROUP=\"rg-parkio-invite-production-we\"' '$boot'"
check "bootstrap never names hosted-beta" \
  "! grep -qi 'hosted-beta' '$boot'"
check "bootstrap fails closed on non-private PostgreSQL addresses" \
  "grep -q 'resolved to non-private address' '$boot'"
check "bootstrap scopes PostGIS to parkio_parking" \
  "grep -q 'dbname=parkio_parking' '$boot'"

echo "== R8-K: scripts invoked directly must be committed executable =="
# core.fileMode=false on the WSL development checkout silently records new
# scripts as 100644. The deploy and the workflow invoke these by path, not via
# `bash`, so a non-executable mode is a deploy-blocking defect that only shows up
# on the runner. Assert the mode git actually recorded, not the local file bit.
for direct in \
  scripts/stage-invite-production-release.sh \
  scripts/migrate-invite-production-workspace-mounts.sh \
  scripts/azure/install-invite-production-runtime-root.sh \
  scripts/azure/install-invite-production-runner.sh \
  scripts/cleanup-invite-production-job.sh \
  scripts/deploy-invite-production.sh ; do
  mode="$(git -C "$ROOT" ls-files -s -- "$direct" | awk '{print $1}')"
  check "$direct is committed executable (got ${mode:-missing})" "[ \"$mode\" = 100755 ]"
done

echo "== R8-I: the live gate cannot repeat the run-32340585156 harness defects =="
live="$ROOT/scripts/test-invite-production-release-containers.sh"
check "live gate proves dockerd can see the staging path before asserting" \
  "grep -q 'dockerd sees the staging path' '$live'"
check "live gate refuses to fall back to /tmp on the runner" \
  "grep -q 'Refusing to fall back to /tmp' '$live'"
check "live gate verifies file CONTENT, not mere readability" \
  "grep -q 'sha256sum /probe' '$live'"
check "live gate probes nobody, 10001 and 472" \
  "grep -q '10001|grafana/loki' '$live' && grep -q '472|grafana/grafana' '$live' && grep -q 'nobody|prom/prometheus' '$live'"
check "live gate derives the image tag the way production does" \
  "grep -q parkio_image_tag_for_sha '$live'"
check "live gate uses the canonical compose file list" \
  "grep -q parkio_configure_deployment_profile '$live'"
check "live gate hard-codes no -f compose list" \
  "! grep -qE \"^ *-f docker/docker-compose\" '$live'"
check "live gate asserts the exact candidate image tag" \
  "grep -q 'model pins the exact candidate image tag' '$live'"
check "live gate asserts zero running _work mounts" \
  "grep -q 'no running container bind-mounts the runner _work tree' '$live'"
check "production staging refuses PrivateTmp roots" \
  "grep -q 'PrivateTmp=yes makes this path invisible to dockerd' '$ROOT/scripts/stage-invite-production-release.sh'"

echo "== R8-J: the workspace-mount migration is tightly scoped =="
mig="$ROOT/scripts/migrate-invite-production-workspace-mounts.sh"
check "migration exists" "[ -x '$mig' ]"
check "migration scopes to the two known exporters only" \
  "grep -q 'MIGRATE_SERVICES=(node-exporter blackbox-exporter)' '$mig'"
check "migration recreates with --no-deps" \
  "grep -q 'up -d --no-deps --force-recreate' '$mig'"
check "migration never runs compose down" \
  "! sed 's/#.*//' '$mig' | grep -qE 'compose[^|]*down|down -v'"
check "migration refuses out-of-scope containers" \
  "grep -q 'Refusing to act outside the audited migration scope' '$mig'"
check "migration is invite-production only" \
  "grep -q 'this migration is invite-production only' '$mig'"
check "migration requires a staged stable release" \
  "grep -q 'no stable runtime release staged for' '$mig'"
wf="$ROOT/.github/workflows/invite-production-deploy.yml"
check "workspace-migration is an explicit operator-triggered action" \
  "grep -q \"inputs.action == 'workspace-migration'\" '$wf'"
check "workspace-migration is environment-protected" \
  "awk '/^  workspace-migration:/,/^  deploy:/' '$wf' | grep -q 'environment: invite-production'"
check "workspace-migration stages the stable release before rebinding" \
  "awk '/^  workspace-migration:/,/^  deploy:/' '$wf' | grep -q 'stage-invite-production-release.sh'"
check "workspace-migration cleans up per-job secrets" \
  "awk '/^  workspace-migration:/,/^  deploy:/' '$wf' | grep -q 'cleanup-invite-production-job.sh'"
check "migration proves zero _work mounts afterwards" \
  "grep -q 'still bind-mount the runner workspace' '$mig'"

echo
echo "PROD-DEPLOY-01A-R8 regression gates: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
