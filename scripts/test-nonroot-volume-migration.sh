#!/usr/bin/env bash
# Tests for scripts/nonroot-volume-migration.sh on disposable Docker volumes.
#
# Volumes carry the Compose labels of a random project (parkio-nonroot-test-*), so the tool
# resolves them the way it resolves a real project. The test touches nothing else and removes
# only the volumes of that project and the one container it starts. Needs Docker.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TOOL="$ROOT/scripts/nonroot-volume-migration.sh"
HELPER_IMAGE="busybox@sha256:fd7dc98638c8e305f4dc34e979f1c0fdfdcaeb0fbf8fcff77ae834b6da3d7e6e"
PROJECT="parkio-nonroot-test-$(date +%s)-$RANDOM"
CONSUMER="$PROJECT-consumer"
WORK="$(mktemp -d)"
EVIDENCE="$WORK/evidence"

pass=0
fail=0
ok() { echo "PASS: $1"; pass=$((pass + 1)); }
bad() { echo "FAIL: $1" >&2; fail=$((fail + 1)); }

cleanup() {
  docker rm -f "$CONSUMER" >/dev/null 2>&1 || true
  for volume in $(docker volume ls -q --filter "label=com.docker.compose.project=$PROJECT"); do
    case "$volume" in "$PROJECT"_*) docker volume rm "$volume" >/dev/null || true ;; esac
  done
  rm -rf -- "${WORK:?}"
}
trap cleanup EXIT

export PARKIO_NONROOT_VOLUME_MAP="$WORK/map.tsv"
printf '%s\t%s\t%s\t%s\t%s\n' \
  svc-data data 999:1000 ready "two volumes, mixed owners" \
  svc-data extra 999:1000 ready "second volume of svc-data" \
  svc-blocked blocked 100:101 blocked "cannot run non-root yet" \
  svc-newline newline 999:1000 ready "a path with a newline" \
  svc-absent absent 70:70 ready "never created" > "$PARKIO_NONROOT_VOLUME_MAP"

create_volume() { # key, populate script
  docker volume create --label "com.docker.compose.project=$PROJECT" \
    --label "com.docker.compose.volume=$1" "${PROJECT}_$1" >/dev/null
  docker run --rm --network none -v "${PROJECT}_$1:/v" --entrypoint sh "$HELPER_IMAGE" -c "$2"
}
owners() {
  docker run --rm --network none -v "$1:/v:ro" --entrypoint sh "$HELPER_IMAGE" -c \
    'find /v -xdev -exec stat -c "%u:%g" {} + | sort | uniq -c | sed "s/^ *//" | paste -sd, -'
}
stat_in() { # volume, path below /v -> "uid:gid mode" of the path itself
  docker run --rm --network none -v "$1:/v:ro" --entrypoint stat "$HELPER_IMAGE" -c '%u:%g %a' "/v/$2"
}
run_tool() { # sets out and rc
  if out="$("$TOOL" "$@" 2>&1)"; then rc=0; else rc=$?; fi
}

create_volume data '
  mkdir -p /v/a/b && echo x > /v/a/b/f && echo y > /v/top && echo z > "/v/with space"
  chmod 0700 /v/a && chmod 4755 /v/top
  ln -s /etc/passwd /v/link-out && ln -s a/b/f /v/link-in
  chown 999:1000 /v/a/b/f'
create_volume extra 'echo e > /v/e'
create_volume blocked 'echo b > /v/b'
create_volume newline 'echo n > "/v/line
break"'
DATA="${PROJECT}_data"
before_owners="$(owners "$DATA")"

echo "=== plan ==="
run_tool plan --project "$PROJECT"
if [ "$rc" = 0 ] \
  && grep -q "service=svc-data volume=$DATA target=999:1000 readiness=ready status=NEEDS_CHOWN owners=\"7 0:0,1 999:1000\"" <<<"$out" \
  && grep -q "service=svc-absent volume=$PROJECT/absent target=70:70 readiness=ready status=ABSENT" <<<"$out"; then
  ok "plan reports owners per volume and marks the missing one ABSENT"
else
  bad "plan output unexpected (rc=$rc): $out"
fi
[ "$(owners "$DATA")" = "$before_owners" ] && ok "plan changes nothing" || bad "plan changed ownership"

echo "=== refusals ==="
run_tool apply --project "$PROJECT" --service svc-data --evidence-dir "$EVIDENCE"
[ "$rc" = 3 ] && grep -q 'pass --confirm-project' <<<"$out" && ok "apply refuses without --confirm-project" || bad "no-confirm: rc=$rc $out"
run_tool apply --project "$PROJECT" --service svc-data --confirm-project parkio --evidence-dir "$EVIDENCE"
[ "$rc" = 3 ] && grep -q 'does not match' <<<"$out" && ok "apply refuses a different --confirm-project" || bad "wrong confirm: rc=$rc $out"
run_tool apply --project "$PROJECT" --service svc-blocked --confirm-project "$PROJECT" --evidence-dir "$EVIDENCE"
[ "$rc" = 3 ] && grep -q 'svc-blocked is blocked' <<<"$out" && ok "apply refuses a blocked row" || bad "blocked: rc=$rc $out"
run_tool apply --project "$PROJECT" --service svc-absent --confirm-project "$PROJECT" --evidence-dir "$EVIDENCE"
[ "$rc" = 3 ] && grep -q "no volume labelled $PROJECT/absent" <<<"$out" && ok "apply refuses a missing volume" || bad "absent: rc=$rc $out"
run_tool apply --project "$PROJECT" --service svc-newline --confirm-project "$PROJECT" --evidence-dir "$EVIDENCE"
[ "$rc" = 3 ] && grep -q 'path(s) containing a newline' <<<"$out" && ok "apply refuses a path with a newline" || bad "newline: rc=$rc $out"
run_tool apply --project "$PROJECT" --service svc-unknown --confirm-project "$PROJECT" --evidence-dir "$EVIDENCE"
[ "$rc" = 2 ] && ok "apply rejects a service without a map row" || bad "unknown service: rc=$rc $out"

docker run -d --name "$CONSUMER" --network none -v "$DATA:/data" "$HELPER_IMAGE" sleep 300 >/dev/null
run_tool apply --project "$PROJECT" --service svc-data --confirm-project "$PROJECT" --evidence-dir "$EVIDENCE"
[ "$rc" = 3 ] && grep -q 'mounted by running container' <<<"$out" && ok "apply refuses a volume a running container mounts" || bad "consumer: rc=$rc $out"
docker rm -f "$CONSUMER" >/dev/null
docker run -d --name "$CONSUMER" --network none -v "${PROJECT}_extra:/data" "$HELPER_IMAGE" sleep 300 >/dev/null
run_tool apply --project "$PROJECT" --service svc-data --confirm-project "$PROJECT" --evidence-dir "$EVIDENCE"
[ "$rc" = 3 ] && [ "$(owners "$DATA")" = "$before_owners" ] \
  && ok "a refusal on a service's second volume leaves its first volume untouched" \
  || bad "partial apply: rc=$rc owners=$(owners "$DATA") $out"
docker rm -f "$CONSUMER" >/dev/null
[ "$(owners "$DATA")" = "$before_owners" ] && ok "refused runs change nothing" || bad "a refused run changed ownership"

echo "=== apply ==="
run_tool apply --project "$PROJECT" --service svc-data --confirm-project "$PROJECT" --evidence-dir "$EVIDENCE"
if [ "$rc" = 0 ] && [ "$(owners "$DATA")" = "8 999:1000" ] && [ "$(owners "${PROJECT}_extra")" = "2 999:1000" ]; then
  ok "apply gives every path of both volumes the target owner"
else
  bad "apply: rc=$rc $out"
fi
[ "$(stat_in "$DATA" link-out | cut -d' ' -f1)" = "999:1000" ] \
  && ok "apply changes the symlink itself; the read-only helper root would refuse its target" \
  || bad "symlink owner: $(stat_in "$DATA" link-out)"
[ -s "$EVIDENCE/$DATA.before.manifest" ] && (cd "$EVIDENCE" && sha256sum -c --quiet "$DATA.before.manifest.sha256") \
  && ok "apply writes the before manifest and its checksum" || bad "manifest missing or checksum wrong"
[ "$(stat_in "$DATA" top)" = "999:1000 755" ] && ok "chown clears the set-uid bit (restore puts it back)" \
  || bad "top after apply: $(stat_in "$DATA" top)"
run_tool plan --project "$PROJECT" --service svc-data
grep -q "volume=$DATA target=999:1000 readiness=ready status=COMPLIANT" <<<"$out" && ok "plan reports COMPLIANT after apply" || bad "plan after apply: $out"

echo "=== restore ==="
cp -- "$EVIDENCE/$DATA.before.manifest" "$WORK/manifest.saved"
printf '0 0 777 -rwxrwxrwx /v/top\n' >> "$EVIDENCE/$DATA.before.manifest"
run_tool restore --project "$PROJECT" --service svc-data --confirm-project "$PROJECT" --evidence-dir "$EVIDENCE"
[ "$rc" = 3 ] && grep -q 'does not match its .sha256' <<<"$out" && ok "restore refuses a manifest that changed after apply" || bad "tampered: rc=$rc $out"
cp -- "$WORK/manifest.saved" "$EVIDENCE/$DATA.before.manifest"

# While migrated, the service writes a new file and deletes one the manifest lists.
docker run --rm --network none -v "$DATA:/v" --entrypoint sh "$HELPER_IMAGE" -c \
  'echo new > /v/created-later && chown 999:1000 /v/created-later && rm "/v/with space"'
run_tool restore --project "$PROJECT" --service svc-data --confirm-project "$PROJECT" --evidence-dir "$EVIDENCE"
if [ "$rc" = 0 ] && grep -q "volume=$DATA restored missing=1 new=1 new_owner=0:0" <<<"$out" \
  && [ "$(owners "$DATA")" = "$before_owners" ]; then
  ok "restore brings back every listed owner and mode, skips the deleted path and gives the new one the root's owner"
else
  bad "restore: rc=$rc $out"
fi
[ "$(stat_in "$DATA" top)" = "0:0 4755" ] && [ "$(stat_in "$DATA" a/b/f)" = "999:1000 644" ] \
  && [ "$(stat_in "$DATA" created-later)" = "0:0 644" ] \
  && ok "restore puts back the set-uid bit and the mixed owners" \
  || bad "after restore: top=$(stat_in "$DATA" top) f=$(stat_in "$DATA" a/b/f) new=$(stat_in "$DATA" created-later)"
run_tool restore --project "$PROJECT" --service svc-data --confirm-project "$PROJECT" --evidence-dir "$EVIDENCE"
[ "$rc" = 0 ] && grep -q "restored missing=1 new=1" <<<"$out" && ok "restore can run again with the same result" || bad "second restore: rc=$rc $out"

run_tool apply --project "$PROJECT" --service svc-data --confirm-project "$PROJECT" --evidence-dir "$EVIDENCE"
[ "$rc" = 3 ] && grep -q 'holds the manifest of an earlier apply' <<<"$out" \
  && ok "a second apply into the same evidence directory is refused" || bad "second apply: rc=$rc $out"

# A path whose type changed since apply cannot be restored as recorded; restore must say so.
run_tool apply --project "$PROJECT" --service svc-data --confirm-project "$PROJECT" --evidence-dir "$EVIDENCE/second"
docker run --rm --network none -v "$DATA:/v" --entrypoint sh "$HELPER_IMAGE" -c 'rm /v/a/b/f && mkdir /v/a/b/f'
run_tool restore --project "$PROJECT" --service svc-data --confirm-project "$PROJECT" --evidence-dir "$EVIDENCE/second"
[ "$rc" = 1 ] && grep -q 'RESTORE MISMATCH mismatched=1 new_wrong_owner=0' <<<"$out" \
  && ok "restore reports a path it could not restore as recorded" || bad "type change: rc=$rc $out"

echo
echo "nonroot-volume-migration: $pass passed, $fail failed"
[ "$fail" -eq 0 ]
