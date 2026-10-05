#!/usr/bin/env bash
# F-INV-2 (owner decision 2026-10-05): an invite-production rollback renders exactly the compose file
# list its target deploy recorded, or refuses before anything is written, activated or started.
#
# Target manifests are realistic: written by this checkout's own manifest writer
# (scripts/ci/write-synthetic-invite-deploy-manifest.sh), then edited per case.
#   dry runs: match (accepted); list changed by an added file, a removed file, or the order; and
#     composeFiles absent or malformed (each refused with exit 3, no rollback manifest written).
#   --no-hosted-beta-overlay, which renders the local-dev model, is refused with exit 3 for an
#     invite-production manifest, both by rollback-invite-production.sh and by rollback-hosted-beta.sh.
#   live rollback against a staged runtime release (PARKIO_RUNTIME_ROOT in a temp dir, and a docker
#     shim that answers `image inspect`, renders `compose ... config` with the real docker and refuses
#     everything else): a release without one of the listed files is refused with exit 3 before it
#     is activated and before any other docker call; a complete release passes the check and is
#     activated. Each live case starts from a deployed release's record that is compatible with
#     the target, so the schema gate (F-INV-3) passes and the file check decides.
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
SHA=5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e
ENV_FILE="$work/invite.env"
cp docker/.env.invite-production.example "$ENV_FILE"
env -u PARKIO_DEPLOYMENT_PROFILE bash scripts/ci/write-synthetic-invite-deploy-manifest.sh \
  --env-file "$ENV_FILE" --out "$work/target.json" --git-sha "$SHA" >/dev/null
edit() { # edit OUT PYTHON_STATEMENT_ON_m
  python3 - "$work/target.json" "$1" "$2" <<'PY'
import json, sys
m = json.load(open(sys.argv[1], encoding="utf-8"))
exec(sys.argv[3])
json.dump(m, open(sys.argv[2], "w", encoding="utf-8"), indent=2)
PY
}

# dry NAME MANIFEST: a rollback dry run; exit code in $rc
dry() {
  rc=0
  env -u PARKIO_DEPLOYMENT_PROFILE PARKIO_ENV_FILE="$ENV_FILE" PARKIO_DEPLOY_ARTIFACT_DIR="$work/artifacts-$1" \
    ./scripts/rollback-invite-production.sh --manifest "$2" --dry-run >"$work/$1.out" 2>"$work/$1.err" || rc=$?
}
refused() { # refused NAME TEXT: exit 3, TEXT in the error, nothing written or done
  if [ "$rc" -eq 3 ] && grep -qF -- "$2" "$work/$1.err" \
    && ! find "$work/artifacts-$1" -name 'rollback-to-*.json' 2>/dev/null | grep -q . \
    && ! grep -q '^DRY-RUN:' "$work/$1.out"; then
    pass "$1: refused before anything is written ($2)"
  else
    bad "$1: exit $rc, error: $(head -n 2 "$work/$1.err" | tr '\n' ' ')"
  fi
}

echo "--- dry runs ---"
dry match "$work/target.json"
if [ "$rc" -eq 0 ] && grep -qF "rollback compose files: exactly the target deploy's list" "$work/match.out"; then
  pass "match: the target deploy's list is rendered, and the dry run succeeds"
else bad "match: exit $rc: $(head -n 2 "$work/match.err")"; fi

edit "$work/added.json" 'm["composeFiles"].remove("docker/docker-compose.managed-db.yml")'
dry added "$work/added.json"
refused added "compose file list changed (added docker/docker-compose.managed-db.yml)"
edit "$work/removed.json" 'm["composeFiles"].insert(5, "docker/docker-compose.retired.yml")'
dry removed "$work/removed.json"
refused removed "compose file list changed (removed docker/docker-compose.retired.yml)"
edit "$work/reordered.json" 'f = m["composeFiles"]; f[3], f[4] = f[4], f[3]'
dry reordered "$work/reordered.json"
refused reordered "compose file list changed (same files, another order)"
edit "$work/absent.json" 'del m["composeFiles"]'
dry absent "$work/absent.json"
refused absent "the target manifest records no composeFiles"
i=0
for broken in '"docker/docker-compose.yml"' '[]' '["docker/docker-compose.yml", 7]' \
  '["docker/../etc/compose.yml"]' '["/opt/parkio/docker/docker-compose.yml"]' \
  '["docker/docker-compose.yml", "docker/docker-compose.yml"]' '["docker/docker-compose.yml.bak"]'; do
  i=$((i + 1))
  edit "$work/malformed-$i.json" "m['composeFiles'] = json.loads('''$broken''')"
  dry "malformed-$i" "$work/malformed-$i.json"
  refused "malformed-$i" "the target manifest's composeFiles is malformed"
done

echo "--- no way around the guard through --no-hosted-beta-overlay (#289 review B1) ---"
rc=0
env -u PARKIO_DEPLOYMENT_PROFILE PARKIO_ENV_FILE="$ENV_FILE" PARKIO_DEPLOY_ARTIFACT_DIR="$work/artifacts-flag-wrapper" \
  ./scripts/rollback-invite-production.sh --manifest "$work/added.json" --dry-run --no-hosted-beta-overlay \
  >"$work/flag-wrapper.out" 2>"$work/flag-wrapper.err" || rc=$?
refused flag-wrapper "rollback-invite-production.sh refuses --no-hosted-beta-overlay"
rc=0
env -u PARKIO_DEPLOYMENT_PROFILE PARKIO_ENV_FILE="$ENV_FILE" PARKIO_DEPLOY_ARTIFACT_DIR="$work/artifacts-flag-direct" \
  ./scripts/rollback-hosted-beta.sh --manifest "$work/added.json" --dry-run --no-hosted-beta-overlay \
  >"$work/flag-direct.out" 2>"$work/flag-direct.err" || rc=$?
refused flag-direct "--no-hosted-beta-overlay renders the local-dev model, so it cannot roll back an"

echo "--- live rollback against a staged release ---"
# A minimal git checkout holding what a release stages: tracked docker/ files and the extra paths.
stage_release() { # stage_release RUNTIME_ROOT [FILE_TO_LEAVE_OUT]
  local runtime_root="$1" leave_out="${2:-}" repo="$1.repo" rel
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
    [ -z "$leave_out" ] || rm -f "$repo/$leave_out"
    git -C "$repo" init -q
    git -C "$repo" add -A
    git -C "$repo" -c user.name=rollback-test -c user.email=rollback-test@example.invalid commit -qm release
    PARKIO_RUNTIME_ROOT="$runtime_root" parkio_stage_runtime_release "$repo" "$SHA" >/dev/null )
}
REAL_DOCKER="$(command -v docker)" || { echo "FAIL docker is required to render the compose model"; exit 1; }
mkdir -p "$work/shim"
cat >"$work/shim/docker" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "$*" >>"$ROLLBACK_TEST_DOCKER_LOG"
[ "${1:-}" = "image" ] && [ "${2:-}" = "inspect" ] && exit 0
if [ "${1:-}" = "compose" ]; then
  for a in "$@"; do [ "$a" = "config" ] && exec "$ROLLBACK_TEST_REAL_DOCKER" "$@"; done
fi
echo "rollback test docker shim: refused: docker $*" >&2
exit 97
EOF
chmod +x "$work/shim/docker"
live() { # live NAME RUNTIME_ROOT: a live rollback; exit code in $rc
  rc=0
  : >"$work/$1.docker"
  env -u PARKIO_DEPLOYMENT_PROFILE PATH="$work/shim:$PATH" ROLLBACK_TEST_DOCKER_LOG="$work/$1.docker" \
    ROLLBACK_TEST_REAL_DOCKER="$REAL_DOCKER" \
    PARKIO_RUNTIME_ROOT="$2" PARKIO_ENV_FILE="$ENV_FILE" PARKIO_DEPLOY_ARTIFACT_DIR="$work/artifacts-$1" \
    ./scripts/rollback-invite-production.sh --manifest "$work/target.json" --skip-smoke \
    >"$work/$1.out" 2>"$work/$1.err" || rc=$?
}

# The deployed release's record (F-INV-3): another commit with the target's migrations.
seed_record() { # seed_record RUNTIME_ROOT
  jq '.gitSha = "4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d"' "$work/target.json" >"$1/deployed-manifest.json"
}

stage_release "$work/runtime-missing" docker/docker-compose.managed-db.yml
seed_record "$work/runtime-missing"
cp "$work/runtime-missing/deployed-manifest.json" "$work/missing-record.json"
live missing "$work/runtime-missing"
if [ "$rc" -eq 3 ] && grep -qF "compose file missing from the target release" "$work/missing.err" \
  && grep -qF "docker/docker-compose.managed-db.yml" "$work/missing.err" \
  && cmp -s "$work/missing-record.json" "$work/runtime-missing/deployed-manifest.json" \
  && [ ! -e "$work/runtime-missing/current" ] && ! grep -v -- ' config' "$work/missing.docker" | grep -q '^compose'; then
  pass "file missing: a release without docker/docker-compose.managed-db.yml is refused before activation and before the record changes, and nothing is started"
else
  bad "file missing: exit $rc, current: $(readlink "$work/runtime-missing/current" || echo none), error: $(grep ERROR "$work/missing.err" | head -n 2 | tr '\n' ' ')"
fi

stage_release "$work/runtime-complete"
seed_record "$work/runtime-complete"
live complete "$work/runtime-complete"
if ! grep -qF "compose file missing" "$work/complete.err" && grep -qF "rollback schema gate: compatible" "$work/complete.out" \
  && [ "$(readlink "$work/runtime-complete/current")" = "$work/runtime-complete/releases/$SHA" ] \
  && cmp -s "$work/target.json" "$work/runtime-complete/deployed-manifest.json"; then
  pass "a complete release passes the schema gate and the check, is recorded and activated (the shim then stops the start, exit $rc)"
else
  bad "complete release: exit $rc, current: $(readlink "$work/runtime-complete/current" || echo none), error: $(head -n 2 "$work/complete.err" | tr '\n' ' ')"
fi

echo "=== invite rollback compose file list: $pass_n passed, $fail_n failed ==="
[ "$fail_n" -eq 0 ]
