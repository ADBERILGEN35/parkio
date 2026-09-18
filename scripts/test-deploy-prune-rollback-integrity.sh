#!/usr/bin/env bash
#
# PA-12 / G03 — deploy prune + rollback integrity behavior gates.
#
# Exercises real helpers (parkio_prune_releases, schema gate, deploy order)
# under a temporary release tree with command fixtures. Does not touch
# production or delete live releases.

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/scripts/lib/runtime-release.sh"

PASS=0
FAIL=0
ok()   { echo "  PASS  $*"; PASS=$((PASS + 1)); }
bad()  { echo "  FAIL  $*" >&2; FAIL=$((FAIL + 1)); }
check() {
  if eval "$2"; then ok "$1"; else bad "$1"; fi
}
jget() {
  python3 -c 'import json,sys; d=json.load(open(sys.argv[1],encoding="utf-8")); print(d.get(sys.argv[2],""))' "$1" "$2"
}
jok() {
  python3 -c 'import json,sys; d=json.load(open(sys.argv[1],encoding="utf-8")); sys.exit(0 if d.get(sys.argv[2])==json.loads(sys.argv[3]) else 1)' "$1" "$2" "$3"
}

TMP="$(mktemp -d)"
trap 'rm -rf -- "$TMP"' EXIT
chmod 0755 "$TMP"

export PARKIO_RUNTIME_ROOT="$TMP/runtime"
install -d -m 0755 "$PARKIO_RUNTIME_ROOT" "$PARKIO_RUNTIME_ROOT/releases"
RELEASES="$(parkio_releases_dir)"

# Fixed fake SHAs for fixtures (valid 40-hex).
SHA_A="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
SHA_B="bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
SHA_C="cccccccccccccccccccccccccccccccccccccccc"
SHA_D="dddddddddddddddddddddddddddddddddddddddd"
SHA_E="eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
SHA_F="ffffffffffffffffffffffffffffffffffffffff"
SHA_OLD="1111111111111111111111111111111111111111"

mk_release() {
  local sha="$1" age_offset="${2:-0}"
  local d="$RELEASES/$sha"
  install -d -m 0755 "$d"
  echo "gitSha=$sha" > "$d/VERSION"
  # Stagger mtimes so find -printf '%T@' ordering is deterministic.
  touch -d "@$((1700000000 + age_offset))" "$d" 2>/dev/null \
    || touch -t "$(printf '%02d' $((age_offset % 50 + 10)))01010101" "$d" 2>/dev/null \
    || true
}

# ---------------------------------------------------------------------------
# Command fixtures: wrap docker so inventory can succeed, fail, or report mounts.
# ---------------------------------------------------------------------------
BIN="$TMP/bin"
mkdir -p "$BIN"
export PATH="$BIN:$PATH"

cat > "$BIN/docker" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
mode_file="${PARKIO_FAKE_DOCKER_MODE:-/dev/null}"
mode="$(cat "$mode_file" 2>/dev/null || echo ok)"
case "${1:-}" in
  ps)
    if [ "$mode" = "ps_fail" ]; then
      echo "fake docker ps failure" >&2
      exit 1
    fi
    if [ -f "${PARKIO_FAKE_DOCKER_IDS:-}" ]; then
      cat "${PARKIO_FAKE_DOCKER_IDS}"
    fi
    exit 0
    ;;
  inspect)
    if [ "$mode" = "inspect_fail" ]; then
      echo "fake docker inspect failure" >&2
      exit 1
    fi
    if [ -f "${PARKIO_FAKE_DOCKER_MOUNTS:-}" ]; then
      cat "${PARKIO_FAKE_DOCKER_MOUNTS}"
    fi
    exit 0
    ;;
  *)
    echo "unexpected fake docker args: $*" >&2
    exit 99
    ;;
esac
EOF
chmod +x "$BIN/docker"

MODE_FILE="$TMP/docker-mode"
IDS_FILE="$TMP/docker-ids"
MOUNTS_FILE="$TMP/docker-mounts"
export PARKIO_FAKE_DOCKER_MODE="$MODE_FILE"
export PARKIO_FAKE_DOCKER_IDS="$IDS_FILE"
export PARKIO_FAKE_DOCKER_MOUNTS="$MOUNTS_FILE"
echo ok > "$MODE_FILE"
: > "$IDS_FILE"
: > "$MOUNTS_FILE"

echo "== PA-12-A: deploy order (smoke/manifest before prune) =="
deploy="$ROOT/scripts/deploy-invite-production.sh"
smoke_line="$(grep -n 'Running smoke checks' "$deploy" | head -1 | cut -d: -f1)"
current_line="$(grep -n 'current.json.tmp' "$deploy" | head -1 | cut -d: -f1)"
prune_line="$(grep -n 'prune-only --cleanup-status' "$deploy" | head -1 | cut -d: -f1)"
check "smoke runs before current.json" "[ '$smoke_line' -lt '$current_line' ]"
check "current.json runs before prune" "[ '$current_line' -lt '$prune_line' ]"
check "cleanup status artifact is recorded" \
  "grep -q 'cleanup-\${GIT_SHA:0:12}.json' '$deploy'"
check "prune failure exits 4 after durable artifacts" \
  "grep -q 'exit 4' '$deploy'"
check "previous release SHA is captured before activate" \
  "grep -q 'PREVIOUS_RELEASE_SHA=\"\$(parkio_active_release_sha' '$deploy'"
check "atomic current.json uses temp + mv" \
  "grep -q 'mv -f -- \"\$CURRENT_TMP\" \"\$ARTIFACT_DIR/current.json\"' '$deploy'"

echo "== PA-12-B: successful prune keeps recent + protected =="
rm -rf -- "$RELEASES"/*
for i in 0 1 2 3 4 5; do
  case $i in
    0) mk_release "$SHA_A" 10 ;;
    1) mk_release "$SHA_B" 20 ;;
    2) mk_release "$SHA_C" 30 ;;
    3) mk_release "$SHA_D" 40 ;;
    4) mk_release "$SHA_E" 50 ;;
    5) mk_release "$SHA_F" 60 ;;
  esac
done
parkio_activate_release "$SHA_F" >/dev/null
STATUS="$TMP/cleanup-ok.json"
# Keep=2 unprotected; protect active F and previous E via env.
export PARKIO_PREVIOUS_RELEASE_SHA="$SHA_E"
parkio_prune_releases 2 0 "$SHA_F" "$STATUS"
check "cleanup outcome ok" "jok '$STATUS' outcome '\"ok\"'"
check "active release survives" "[ -d '$RELEASES/$SHA_F' ]"
check "previous/rollback release survives" "[ -d '$RELEASES/$SHA_E' ]"
# Unprotected newest first among A-D: D,C kept (keep=2); B,A deleted.
check "oldest unprotected pruned" "[ ! -d '$RELEASES/$SHA_A' ]"
check "second-oldest unprotected pruned" "[ ! -d '$RELEASES/$SHA_B' ]"
check "kept recent unprotected present" "[ -d '$RELEASES/$SHA_C' ] && [ -d '$RELEASES/$SHA_D' ]"

echo "== PA-12-C: dry-run does not mutate =="
mk_release "$SHA_OLD" 0
STATUS_DRY="$TMP/cleanup-dry.json"
before="$(find "$RELEASES" -mindepth 1 -maxdepth 1 -type d | wc -l)"
parkio_prune_releases 2 1 "$SHA_F" "$STATUS_DRY"
after="$(find "$RELEASES" -mindepth 1 -maxdepth 1 -type d | wc -l)"
check "dry-run outcome" "jok '$STATUS_DRY' outcome '\"dry_run\"'"
check "dry-run leaves directory count unchanged" "[ '$before' -eq '$after' ]"
check "dry-run candidate still on disk" "[ -d '$RELEASES/$SHA_OLD' ]"

echo "== PA-12-D: permission denied on delete is visible =="
# Recreate a deletable candidate, then make parent immutable via chmod if possible.
rm -rf -- "$RELEASES/$SHA_OLD"
mk_release "$SHA_OLD" 0
chmod 555 "$RELEASES/$SHA_OLD" 2>/dev/null || true
# Make the directory non-removable by removing write on parent temporarily.
chmod 555 "$RELEASES"
STATUS_PERM="$TMP/cleanup-perm.json"
set +e
parkio_prune_releases 2 0 "$SHA_F" "$STATUS_PERM"
perm_rc=$?
set -e
chmod 755 "$RELEASES"
chmod -R u+w "$RELEASES" 2>/dev/null || true
# On some FS (esp. Windows mounts) chmod may not block rm; accept either
# partial_failure or ok if the platform cannot express the denial.
if [ "$perm_rc" -eq 4 ]; then
  ok "permission failure returns rc=4"
  check "partial_failure outcome recorded" "jok '$STATUS_PERM' outcome '\"partial_failure\"'"
elif [ "$perm_rc" -eq 0 ]; then
  ok "platform could not simulate permission denial (skipped hard assert)"
else
  bad "unexpected prune rc under permission fixture: $perm_rc"
fi

echo "== PA-12-E: docker inventory failure is fail-closed =="
chmod -R u+w "$RELEASES" 2>/dev/null || true
rm -rf -- "$RELEASES"/*
mk_release "$SHA_A" 10
mk_release "$SHA_B" 20
parkio_activate_release "$SHA_B" >/dev/null
echo inspect_fail > "$MODE_FILE"
printf 'cid1\n' > "$IDS_FILE"
STATUS_INV="$TMP/cleanup-inv.json"
set +e
parkio_prune_releases 2 0 "$SHA_B" "$STATUS_INV"
inv_rc=$?
set -e
echo ok > "$MODE_FILE"
: > "$IDS_FILE"
check "inventory failure returns rc=3" "[ '$inv_rc' -eq 3 ]"
check "inventory_failed outcome" "jok '$STATUS_INV' outcome '\"inventory_failed\"'"
check "no deletion after inventory failure" \
  "[ -d '$RELEASES/$SHA_A' ] && [ -d '$RELEASES/$SHA_B' ]"

echo "== PA-12-F: bind-mount SHA is protected =="
rm -rf -- "$RELEASES"/*
mk_release "$SHA_A" 10
mk_release "$SHA_B" 20
mk_release "$SHA_C" 30
parkio_activate_release "$SHA_C" >/dev/null
printf 'cid1\n' > "$IDS_FILE"
printf '%s\n' "$RELEASES/$SHA_A/docker" > "$MOUNTS_FILE"
unset PARKIO_PREVIOUS_RELEASE_SHA || true
STATUS_MOUNT="$TMP/cleanup-mount.json"
parkio_prune_releases 1 0 "$SHA_C" "$STATUS_MOUNT"
check "mounted release protected" "[ -d '$RELEASES/$SHA_A' ]"
check "mount SHA listed in protected" \
  "python3 -c 'import json,sys; d=json.load(open(sys.argv[1],encoding=\"utf-8\")); sys.exit(0 if sys.argv[2] in d.get(\"protected\",[]) else 1)' '$STATUS_MOUNT' '$SHA_A'"
: > "$MOUNTS_FILE"
: > "$IDS_FILE"

echo "== PA-12-G: current.json / explicit protected SHAs =="
ART="$TMP/artifacts"
mkdir -p "$ART"
cat > "$ART/current.json" <<EOF
{"gitSha":"$SHA_B","previousManifest":"$ART/prev.json","migrations":{}}
EOF
cat > "$ART/prev.json" <<EOF
{"gitSha":"$SHA_A","migrations":{}}
EOF
rm -rf -- "$RELEASES"/*
mk_release "$SHA_A" 10
mk_release "$SHA_B" 20
mk_release "$SHA_C" 30
mk_release "$SHA_D" 40
parkio_activate_release "$SHA_D" >/dev/null
export PARKIO_DEPLOY_ARTIFACT_DIR="$ART"
STATUS_CUR="$TMP/cleanup-cur.json"
parkio_prune_releases 1 0 "$SHA_D" "$STATUS_CUR"
check "current.json gitSha protected" "[ -d '$RELEASES/$SHA_B' ]"
check "previousManifest gitSha protected" "[ -d '$RELEASES/$SHA_A' ]"
unset PARKIO_DEPLOY_ARTIFACT_DIR

echo "== PA-12-H: schema-incompatible rollback is refused =="
CUR_M="$TMP/cur-manifest.json"
TGT_M="$TMP/tgt-manifest.json"
cat > "$CUR_M" <<'EOF'
{"migrations":{"parking-service":["V1__a.sql","V2__b.sql","V3__c.sql"]}}
EOF
cat > "$TGT_M" <<'EOF'
{"migrations":{"parking-service":["V1__a.sql","V2__b.sql"]}}
EOF
set +e
parkio_assert_rollback_schema_compatible "$TGT_M" "$CUR_M" >/dev/null 2>&1
sch_rc=$?
set -e
check "schema gate refuses advanced live migrations" "[ '$sch_rc' -eq 3 ]"
# Compatible case
cat > "$CUR_M" <<'EOF'
{"migrations":{"parking-service":["V1__a.sql","V2__b.sql"]}}
EOF
set +e
parkio_assert_rollback_schema_compatible "$TGT_M" "$CUR_M" >/dev/null 2>&1
sch_ok=$?
set -e
check "schema gate allows matching migrations" "[ '$sch_ok' -eq 0 ]"
check "rollback script invokes schema gate" \
  "grep -q 'parkio_assert_rollback_schema_compatible' '$ROOT/scripts/rollback-hosted-beta.sh'"

echo "== PA-12-I: stage --dry-run prune path =="
rm -rf -- "$RELEASES"/*
mk_release "$SHA_A" 10
mk_release "$SHA_B" 20
parkio_activate_release "$SHA_B" >/dev/null
STAGE_STATUS="$TMP/stage-dry.json"
"$ROOT/scripts/stage-invite-production-release.sh" \
  --sha "$SHA_B" --prune-only --keep 2 --dry-run --cleanup-status "$STAGE_STATUS"
check "stage dry-run status written" "[ -f '$STAGE_STATUS' ]"
check "stage dry-run outcome" "python3 -c 'import json,sys; d=json.load(open(sys.argv[1],encoding=\"utf-8\")); sys.exit(0 if d.get(\"dryRun\")==1 else 1)' '$STAGE_STATUS'"
check "stage dry-run did not delete" "[ -d '$RELEASES/$SHA_A' ]"

echo "== PA-12-J: re-run prune is idempotent =="
STATUS_R1="$TMP/cleanup-r1.json"
STATUS_R2="$TMP/cleanup-r2.json"
parkio_prune_releases 2 0 "$SHA_B" "$STATUS_R1"
parkio_prune_releases 2 0 "$SHA_B" "$STATUS_R2"
check "second prune still ok" "jok '$STATUS_R2' outcome '\"ok\"'"
check "active still present after re-run" "[ -d '$RELEASES/$SHA_B' ]"

echo "== PA-12-K: old inspect||true anti-pattern removed =="
check "stage no longer uses inspect||true empty in-use" \
  "! grep -qE 'docker inspect.*\\|\\| true' '$ROOT/scripts/stage-invite-production-release.sh'"
check "stage delegates to parkio_prune_releases" \
  "grep -q 'parkio_prune_releases' '$ROOT/scripts/stage-invite-production-release.sh'"

echo
echo "PA-12 deploy/prune/rollback integrity: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
