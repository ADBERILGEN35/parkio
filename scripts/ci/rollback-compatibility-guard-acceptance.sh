#!/usr/bin/env bash
# Rollback compatibility-guard acceptance (CL-F12 F-INV-2; owner decision 2026-10-05: "Assert correct
# refusal").
#
#   rollback-compatibility-guard-acceptance.sh --manifest TARGET --env-template ENV --work-dir DIR
#       [--expect compatible|auto] [--label TEXT] [--summary FILE]
#
# Dry-runs the invite-production rollback against TARGET, a deploy manifest, with ENV set to the edge
# mode and ACME setting that TARGET's composeFiles imply. It then checks that the rollback's
# compatibility guard (parkio_assert_rollback_compose_files) answers correctly:
#   - When this checkout renders TARGET's compose file list, the dry run must succeed. Its rollback
#     manifest must target TARGET's gitSha, imageTag, images and deploymentProfile.
#   - When the lists differ, the dry run must refuse with exit 3 and "compose file list changed
#     (...)", naming every added and removed file, before it writes anything. The check then passes,
#     and states that a rollback to TARGET is NOT currently possible.
# Any other outcome fails (exit 1). With --expect compatible, the lists must match (the positive path).
#
# This is not operational rollback acceptance. Nothing is rolled back, and a green result does not
# mean that a rollback to the latest deployed release currently works.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
MANIFEST=""
TEMPLATE=""
WORK=""
EXPECT="auto"
LABEL="the latest deploy"
SUMMARY=""
usage() {
  echo "usage: $0 --manifest TARGET --env-template ENV --work-dir DIR [--expect compatible|auto] [--label TEXT] [--summary FILE]" >&2
  exit 2
}
while [ "$#" -gt 0 ]; do
  case "$1" in
    --manifest) MANIFEST="${2:-}"; shift 2 ;;
    --env-template) TEMPLATE="${2:-}"; shift 2 ;;
    --work-dir) WORK="${2:-}"; shift 2 ;;
    --expect) EXPECT="${2:-}"; shift 2 ;;
    --label) LABEL="${2:-}"; shift 2 ;;
    --summary) SUMMARY="${2:-}"; shift 2 ;;
    *) usage ;;
  esac
done
{ [ -f "$MANIFEST" ] && [ -f "$TEMPLATE" ] && [ -n "$WORK" ]; } || usage
case "$EXPECT" in compatible|auto) ;; *) usage ;; esac
abs() { (cd "$(dirname "$1")" && printf '%s/%s\n' "$(pwd)" "$(basename "$1")"); }
MANIFEST="$(abs "$MANIFEST")"
TEMPLATE="$(abs "$TEMPLATE")"
mkdir -p "$WORK"
WORK="$(cd "$WORK" && pwd)"
cd "$ROOT"

fail() {
  echo "rollback compatibility-guard acceptance: FAIL: $*" >&2
  if [ -n "$SUMMARY" ]; then
    printf '### Rollback compatibility-guard acceptance (%s): FAIL\n\n%s\n\n' "$LABEL" "$*" >>"$SUMMARY"
  fi
  exit 1
}

# 1. The edge mode and ACME setting the target deploy ran with, from its composeFiles.
edge="$(python3 - "$MANIFEST" <<'PY'
import json, sys
files = json.load(open(sys.argv[1], encoding="utf-8")).get("composeFiles")
if not isinstance(files, list):
    sys.exit("the target manifest records no composeFiles list")
dark = "docker/docker-compose.invite-dark.yml" in files
public = "docker/docker-compose.invite-public.yml" in files
staged = "docker/docker-compose.invite-public-staged.yml" in files
if dark and not public and not staged:
    print("dark false")
elif public and staged and not dark:
    print("public false")
elif public and not staged and not dark:
    print("public true")
else:
    sys.exit("cannot tell the target deploy's edge mode from its composeFiles")
PY
)" || fail "cannot read the target deploy's edge mode from $MANIFEST"
read -r edge_mode acme_authorized <<<"$edge"
env_file="$WORK/rollback.env"
grep -v -E '^(PARKIO_INVITE_EDGE_MODE|PARKIO_INVITE_ACME_AUTHORIZED)=' "$TEMPLATE" >"$env_file" || true
printf 'PARKIO_INVITE_EDGE_MODE=%s\nPARKIO_INVITE_ACME_AUTHORIZED=%s\n' "$edge_mode" "$acme_authorized" >>"$env_file"
echo "target deploy edge mode: $edge_mode, ACME authorized: $acme_authorized"

# 2. The list this checkout renders for that env, against the target's.
current="$(env -u PARKIO_DEPLOYMENT_PROFILE -u PARKIO_INVITE_EDGE_MODE -u PARKIO_INVITE_ACME_AUTHORIZED bash -c '
  set -euo pipefail
  source scripts/lib/deploy-common.sh
  PARKIO_DEPLOYMENT_PROFILE=invite-production
  parkio_configure_deployment_profile "$1" >/dev/null
  parkio_compose_files_json' _ "$env_file")" || fail "cannot configure the invite-production profile for $env_file"
read -r compatible changes < <(python3 - "$MANIFEST" "$current" <<'PY'
import json, sys
target, current = json.load(open(sys.argv[1], encoding="utf-8"))["composeFiles"], json.loads(sys.argv[2])
added = [f for f in current if f not in target]
removed = [f for f in target if f not in current]
changes = [f"added {f}" for f in added] + [f"removed {f}" for f in removed] or ["same files, another order"]
print("yes" if target == current else "no", "; ".join(changes))
PY
)
[ "$EXPECT" = "compatible" ] && [ "$compatible" != "yes" ] \
  && fail "expected $LABEL to be compatible, but its compose file list differs ($changes)"

# 3. The rollback's own answer, as a dry run.
rc=0
env -u PARKIO_DEPLOYMENT_PROFILE PARKIO_ENV_FILE="$env_file" PARKIO_DEPLOY_ARTIFACT_DIR="$WORK/artifacts" \
  "${PARKIO_ACCEPTANCE_ROLLBACK:-./scripts/rollback-invite-production.sh}" --manifest "$MANIFEST" --dry-run \
  >"$WORK/rollback.out" 2>"$WORK/rollback.err" || rc=$?
written=()
if [ -d "$WORK/artifacts" ]; then
  mapfile -t written < <(find "$WORK/artifacts" -maxdepth 1 -name 'rollback-to-*.json')
fi
detail() { printf 'exit %s; stderr: %s' "$rc" "$(head -n 3 "$WORK/rollback.err" | tr '\n' ' ')"; }

if [ "$compatible" = "yes" ]; then
  [ "$rc" -eq 0 ] || fail "$LABEL has a compatible compose file list, but the rollback dry run failed ($(detail))"
  [ "${#written[@]}" -eq 1 ] || fail "the compatible rollback dry run wrote ${#written[@]} rollback manifests, not 1"
  python3 - "$MANIFEST" "${written[0]}" <<'PY' || fail "the rollback dry run does not target $LABEL"
import json, sys
target, rollback = (json.load(open(p, encoding="utf-8")) for p in sys.argv[1:3])
assert rollback["action"] == "rollback", rollback["action"]
for key in ("gitSha", "imageTag", "images", "deploymentProfile", "composeFiles"):
    assert rollback[key] == target[key], f"{key} differs from the target deploy's manifest"
PY
  images="$(jq '.images | length' "$MANIFEST")"
  message="compatible: $LABEL renders this checkout's compose file list; the rollback dry run succeeds and targets $(jq -r .imageTag "$MANIFEST") for $images services."
  echo "rollback compatibility-guard acceptance: PASS ($message)"
  echo "This performs no rollback; it is not operational rollback acceptance."
  if [ -n "$SUMMARY" ]; then
    printf '### Rollback compatibility-guard acceptance (%s): PASS\n\n- %s\n- This check performs no rollback. It is **not** operational rollback acceptance.\n\n' \
      "$LABEL" "$message" >>"$SUMMARY"
  fi
  exit 0
fi

# Incompatible: only the guard's own refusal, before anything was written or started, passes.
expected="compose file list changed ($changes)"
[ "$rc" -eq 3 ] || fail "$LABEL has an incompatible compose file list ($changes), but the rollback dry run did not refuse with exit 3 ($(detail))"
grep -qF -- "$expected" "$WORK/rollback.err" \
  || fail "the rollback refused, but not with the expected file-list refusal '$expected' ($(detail))"
[ "${#written[@]}" -eq 0 ] || fail "the refused rollback dry run still wrote a rollback manifest"
! grep -q '^DRY-RUN:' "$WORK/rollback.out" || fail "the refused rollback dry run reached its dry-run actions"
statement="rollback to $LABEL is NOT currently possible: $expected; a post-change deploy is required."
echo "rollback compatibility-guard acceptance: PASS (the guard refused correctly)"
echo "$statement"
echo "Operational rollback acceptance remains BLOCKED until an authorized compatible release exists."
if [ -n "$SUMMARY" ]; then
  printf '### Rollback compatibility-guard acceptance (%s): PASS, refusal correct\n\n> [!WARNING]\n> **%s**\n>\n> Operational rollback acceptance remains **BLOCKED** until an authorized compatible release exists. This check performs no rollback.\n\n' \
    "$LABEL" "$statement" >>"$SUMMARY"
fi
exit 0
