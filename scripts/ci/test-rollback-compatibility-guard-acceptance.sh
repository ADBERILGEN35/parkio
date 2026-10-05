#!/usr/bin/env bash
# Tests scripts/ci/rollback-compatibility-guard-acceptance.sh on realistic synthetic manifests, written
# by this checkout's own manifest writer (scripts/ci/write-synthetic-invite-deploy-manifest.sh):
#   compatible path: public-staged and dark deploys whose list this checkout renders -> PASS, and the
#     rollback dry run targets the deploy;
#   refusal path: a deploy that rendered another list -> PASS only for the guard's own refusal, which
#     names the files and states that a rollback is NOT currently possible;
#   anything else fails: --expect compatible on an incompatible list, another refusal (profile
#     mismatch), a rollback that accepts an incompatible list, refuses for another reason, or writes
#     a manifest while refusing, and a deploy whose edge mode cannot be told.
# Nothing is built, pulled or started.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
pass_n=0
fail_n=0
pass() { echo "PASS $*"; pass_n=$((pass_n + 1)); }
bad() { echo "FAIL $*"; fail_n=$((fail_n + 1)); }

work="$(mktemp -d)"
trap 'rm -rf "${work:?}"' EXIT
TEMPLATE=docker/.env.invite-production.example
ACCEPTANCE=scripts/ci/rollback-compatibility-guard-acceptance.sh

# A synthetic deploy manifest written with TEMPLATE set to an edge mode and ACME setting.
synthetic() { # synthetic NAME EDGE_MODE ACME_AUTHORIZED
  grep -v -E '^(PARKIO_INVITE_EDGE_MODE|PARKIO_INVITE_ACME_AUTHORIZED)=' "$TEMPLATE" >"$work/$1.env" || true
  printf 'PARKIO_INVITE_EDGE_MODE=%s\nPARKIO_INVITE_ACME_AUTHORIZED=%s\n' "$2" "$3" >>"$work/$1.env"
  env -u PARKIO_DEPLOYMENT_PROFILE bash scripts/ci/write-synthetic-invite-deploy-manifest.sh \
    --env-file "$work/$1.env" --out "$work/$1.json" >/dev/null
}
edit() { # edit SOURCE OUT PYTHON_EXPRESSION_ON_m
  python3 - "$1" "$2" "$3" <<'PY'
import json, sys
m = json.load(open(sys.argv[1], encoding="utf-8"))
exec(sys.argv[3])
json.dump(m, open(sys.argv[2], "w", encoding="utf-8"), indent=2)
PY
}
# accept NAME MANIFEST [ARGS...]: runs the acceptance; exit code in $rc, output in $work/NAME.log
accept() {
  local name="$1" manifest="$2"
  shift 2
  rc=0
  bash "$ACCEPTANCE" --manifest "$manifest" --env-template "$TEMPLATE" --work-dir "$work/run-$name" \
    --label "$name" --summary "$work/$name.summary" "$@" >"$work/$name.log" 2>&1 || rc=$?
}
has() { grep -qF -- "$2" "$work/$1.log"; }

synthetic staged public false
synthetic dark dark false
synthetic acme public true

echo "--- compatible path ---"
accept staged "$work/staged.json" --expect compatible
if [ "$rc" -eq 0 ] && has staged "PASS (compatible" && grep -qF "not** operational rollback acceptance" "$work/staged.summary"; then
  pass "a public-staged deploy with this checkout's list: the dry run succeeds and targets it; the summary says it is not operational acceptance"
else bad "public-staged compatible: exit $rc: $(tail -n 2 "$work/staged.log")"; fi
accept dark "$work/dark.json" --expect compatible
if [ "$rc" -eq 0 ] && has dark "PASS (compatible"; then pass "a dark deploy: the edge mode is taken from its composeFiles, and it is compatible"
else bad "dark compatible: exit $rc: $(tail -n 2 "$work/dark.log")"; fi
accept acme "$work/acme.json"
if [ "$rc" -eq 0 ] && has acme "PASS (compatible" && has acme "ACME authorized: true"; then
  pass "a public deploy with ACME authorized (the real latest deploy's shape) is compatible under auto"
else bad "ACME-authorized compatible: exit $rc: $(tail -n 2 "$work/acme.log")"; fi

echo "--- refusal path ---"
edit "$work/staged.json" "$work/changed.json" 'm["composeFiles"] = [f for f in m["composeFiles"] if f != "docker/docker-compose.managed-db.yml"] + ["docker/docker-compose.retired.yml"]'
accept changed "$work/changed.json"
statement="rollback to changed is NOT currently possible: compose file list changed (added docker/docker-compose.managed-db.yml; removed docker/docker-compose.retired.yml); a post-change deploy is required."
if [ "$rc" -eq 0 ] && has changed "PASS (the guard refused correctly)" && has changed "$statement" \
  && has changed "Operational rollback acceptance remains BLOCKED" && grep -qF "**BLOCKED**" "$work/changed.summary"; then
  pass "a deploy that rendered another list: the guard's refusal names both files, and the result states that a rollback is NOT currently possible"
else bad "refusal path: exit $rc: $(tail -n 3 "$work/changed.log")"; fi
if ! find "$work/run-changed/artifacts" -name 'rollback-to-*.json' 2>/dev/null | grep -q .; then
  pass "the refused dry run wrote no rollback manifest"
else bad "the refused dry run wrote a rollback manifest"; fi
edit "$work/staged.json" "$work/reordered.json" 'f = m["composeFiles"]; f[0], f[1] = f[1], f[0]'
accept reordered "$work/reordered.json"
if [ "$rc" -eq 0 ] && has reordered "compose file list changed (same files, another order)"; then
  pass "the same files in another order are a changed list, refused correctly"
else bad "reordered: exit $rc: $(tail -n 2 "$work/reordered.log")"; fi

echo "--- anything else fails ---"
accept expect-compatible "$work/changed.json" --expect compatible
if [ "$rc" -eq 1 ] && has expect-compatible "expected expect-compatible to be compatible"; then pass "--expect compatible fails on an incompatible list"
else bad "--expect compatible on an incompatible list: exit $rc"; fi
edit "$work/changed.json" "$work/other-profile.json" 'm["deploymentProfile"] = "hosted-beta"'
accept other-profile "$work/other-profile.json"
if [ "$rc" -eq 1 ] && has other-profile "did not refuse with exit 3"; then pass "another refusal (profile mismatch, exit 2) fails the check"
else bad "profile mismatch: exit $rc: $(tail -n 2 "$work/other-profile.log")"; fi
edit "$work/staged.json" "$work/no-edge.json" 'm["composeFiles"] = [f for f in m["composeFiles"] if "invite-" not in f]'
accept no-edge "$work/no-edge.json"
if [ "$rc" -eq 1 ] && has no-edge "cannot read the target deploy's edge mode"; then pass "a deploy whose edge mode cannot be told fails the check"
else bad "unknown edge mode: exit $rc"; fi

mkdir -p "$work/stubs"
cat >"$work/stubs/accepts-anything" <<'EOF'
#!/usr/bin/env bash
# A rollback without the guard: writes a rollback manifest for any target.
manifest="$2"; mkdir -p "$PARKIO_DEPLOY_ARTIFACT_DIR"
jq '.action = "rollback"' "$manifest" >"$PARKIO_DEPLOY_ARTIFACT_DIR/rollback-to-stub.json"
echo "DRY-RUN: would roll back"
EOF
cat >"$work/stubs/other-refusal" <<'EOF'
#!/usr/bin/env bash
echo "ERROR: something else went wrong" >&2
exit 3
EOF
cat >"$work/stubs/refuses-but-writes" <<'EOF'
#!/usr/bin/env bash
manifest="$2"; mkdir -p "$PARKIO_DEPLOY_ARTIFACT_DIR"
jq '.action = "rollback"' "$manifest" >"$PARKIO_DEPLOY_ARTIFACT_DIR/rollback-to-stub.json"
echo "ERROR: compose file list changed (added docker/docker-compose.managed-db.yml; removed docker/docker-compose.retired.yml)." >&2
exit 3
EOF
chmod +x "$work/stubs"/*
for stub in accepts-anything other-refusal refuses-but-writes; do
  rc=0
  PARKIO_ACCEPTANCE_ROLLBACK="$work/stubs/$stub" bash "$ACCEPTANCE" --manifest "$work/changed.json" \
    --env-template "$TEMPLATE" --work-dir "$work/run-$stub" --label "$stub" >"$work/$stub.log" 2>&1 || rc=$?
  if [ "$rc" -eq 1 ]; then pass "a rollback that $stub on an incompatible list fails the check"
  else bad "stub $stub: exit $rc: $(tail -n 2 "$work/$stub.log")"; fi
done
rc=0
PARKIO_ACCEPTANCE_ROLLBACK="$work/stubs/other-refusal" bash "$ACCEPTANCE" --manifest "$work/staged.json" \
  --env-template "$TEMPLATE" --work-dir "$work/run-compatible-fails" --label compatible-fails >"$work/compatible-fails.log" 2>&1 || rc=$?
if [ "$rc" -eq 1 ] && grep -qF "the rollback dry run failed" "$work/compatible-fails.log"; then
  pass "a compatible list whose dry run fails fails the check"
else bad "compatible list with a failing dry run: exit $rc"; fi

echo "=== rollback compatibility-guard acceptance tests: $pass_n passed, $fail_n failed ==="
[ "$fail_n" -eq 0 ]
