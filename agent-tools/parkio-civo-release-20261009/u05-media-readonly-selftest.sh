#!/usr/bin/env bash
# Local self-test of u05-media-readonly.py against real containers on the local Docker engine:
#   - the production MinIO release (quay/minio RELEASE.2024-09-13T20-26-02Z) with read-only root, tmpfs /tmp and cap-drop ALL,
#     and a root password full of URL-special characters;
#   - postgres:16-alpine holding flyway_schema_history and the two erasure tables;
#   - a stand-in media container (the MinIO image, sleeping) with the media-service environment and revision label;
#   - a stand-in "proxy" host.
# All are on one test network under a unique Compose project label. Everything the test creates carries the label
# parkio.task=u05-selftest and is removed at the end.
# M1 root identity, unversioned bucket, default region, direct path, the 18 final checksums -> 5 PASS + 2 RECORDED, no secret in
#    the output or the record, record 700/600, nothing left in MinIO's /tmp, the database unchanged;
# M2 the earlier V14 checksum -> flyway FINDING naming the earlier version; M3 a non-root media user whose policy lacks the
#    version actions -> permissions FINDING naming both; M4 its policy with s3:* -> PASS; M5 a bucket with object lock (and so
#    versioning) -> bucket-mode FINDING; M6 the endpoint on the proxy host -> network FINDING; M7 region eu-west-1 -> region
#    FINDING; M8 PARKIO_MEDIA_STORAGE_REGION set but empty -> region FINDING; M9 no running postgres-media -> STOP, no record.
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"; TOOL="$HERE/u05-media-readonly.py"
MINIO_IMG="${U05T_MINIO_IMAGE:-minio/minio:RELEASE.2024-09-13T20-26-02Z}"
PG_IMG="${U05T_PG_IMAGE:-postgres:16-alpine}"
R="$(openssl rand -hex 3)"; P="u05t$R"; NET="$P-backend"; LBL="parkio.task=u05-selftest"
ROOTPW="p@ss/w:rd?#%&x \"q\" \$(syn)-$R"; PGPW="pg-synthetic-$R"; USERSECRET="media-user-secret-$R"
W="$(mktemp -d)"
cleanup() {
  docker ps -aq --filter "label=$LBL" --filter "label=com.docker.compose.project=$P" | xargs -r docker rm -f >/dev/null 2>&1
  docker network rm "$NET" >/dev/null 2>&1
  rm -rf "${W:?}"
}
trap cleanup EXIT
fail=0
ok() { echo "ok   $*"; }
no() { echo "FAIL $*"; fail=1; }
has() { if grep -qF -- "$2" "$W/$1"; then ok "$1: $2"; else no "$1: $2"; fi; }
hasE() { if grep -qE -- "$2" "$W/$1"; then ok "$1: /$2/"; else no "$1: /$2/"; fi; }
lbl() { echo --label "$LBL" --label "com.docker.compose.project=$P" --label "com.docker.compose.service=$1"; }
mcx() {  # mc commands in the test MinIO, through an alias made the tool's way
  docker exec "$P-minio" bash -c 'd=$(mktemp -d /tmp/.t.XXXXXX); export MC_CONFIG_DIR=$d
    printf "%s\n%s\n" "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" | mc alias set t http://127.0.0.1:9000 >/dev/null 2>&1
    eval "$1"; rc=$?; rm -rf "$d"; exit $rc' _ "$1" >/dev/null 2>&1
}
pgx() { docker exec "$P-pg" psql -X -q -v ON_ERROR_STOP=1 -U parkio_media -d parkio_media -c "$1" >/dev/null; }
media() {  # endpoint bucket access-key secret-key [region; may be empty]
  docker rm -f "$P-media" >/dev/null 2>&1
  local reg=()
  if [ "$#" -ge 5 ]; then reg=(-e "PARKIO_MEDIA_STORAGE_REGION=$5"); fi
  # shellcheck disable=SC2046
  docker run -d --name "$P-media" $(lbl media-service) --label org.opencontainers.image.revision=504b9cb453aca31954800cb4f73a074f3b8f5ef6 \
    --network "$NET" --entrypoint sleep -e "PARKIO_MEDIA_STORAGE_ENDPOINT=$1" -e "PARKIO_MEDIA_BUCKET=$2" \
    -e "PARKIO_MEDIA_STORAGE_ACCESS_KEY=$3" -e "PARKIO_MEDIA_STORAGE_SECRET_KEY=$4" "${reg[@]}" "$MINIO_IMG" infinity >/dev/null
}
tool() { U05_TEST=1 U05_PROJECT="$P" U05_OUT="$W/out" python3 -I "$TOOL" > "$W/$1" 2>&1; echo "tool exit=$?" >> "$W/$1"; }
clean() {
  if grep -r -q -F -e "$ROOTPW" -e "$PGPW" -e "$USERSECRET" "$W/$1" "$W/out" 2>/dev/null; then no "$1: a secret reached the output or the record"
  else ok "$1: no secret in the output or the record"; fi
}

docker network create --label "$LBL" "$NET" >/dev/null || { echo "SELFTEST SETUP: docker network"; exit 2; }
# shellcheck disable=SC2046
docker run -d --name "$P-minio" $(lbl minio) --network "$NET" --network-alias minio --read-only --tmpfs /tmp:size=16m \
  --tmpfs /data:size=64m --cap-drop ALL --security-opt no-new-privileges:true -e MINIO_ROOT_USER=parkio \
  -e "MINIO_ROOT_PASSWORD=$ROOTPW" "$MINIO_IMG" server /data >/dev/null || { echo "SELFTEST SETUP: minio"; exit 2; }
# shellcheck disable=SC2046
docker run -d --name "$P-pg" $(lbl postgres-media) --network "$NET" -e POSTGRES_USER=parkio_media -e POSTGRES_DB=parkio_media \
  -e "POSTGRES_PASSWORD=$PGPW" "$PG_IMG" >/dev/null || { echo "SELFTEST SETUP: postgres"; exit 2; }
# shellcheck disable=SC2046
docker run -d --name "$P-proxy" --label "$LBL" --label "com.docker.compose.project=$P" --network "$NET" --network-alias proxy \
  --entrypoint sleep "$MINIO_IMG" infinity >/dev/null
for _ in $(seq 1 30); do docker exec "$P-pg" pg_isready -q -U parkio_media -d parkio_media 2>/dev/null && break; sleep 1; done
for _ in $(seq 1 30); do docker exec "$P-minio" curl -fsS http://127.0.0.1:9000/minio/health/live >/dev/null 2>&1 && break; sleep 1; done
sleep 2
pgx "create table flyway_schema_history (installed_rank int primary key, version varchar(50), description varchar(200) not null default '',
  type varchar(20) not null default 'SQL', script varchar(1000) not null, checksum int, installed_by varchar(100) not null default 'x',
  installed_on timestamp not null default now(), execution_time int not null default 0, success boolean not null);
  create table media_erasure_jobs (ack_event_id uuid primary key, attempts int not null default 0);
  create table media_object_writes (id serial primary key, state varchar(16) not null);
  insert into media_erasure_jobs values ('00000000-0000-0000-0000-000000000001', 3);
  insert into media_object_writes (state) values ('APPLIED'), ('APPLIED'), ('PENDING');"
ins="$(python3 -I - "$TOOL" <<'PY'
import ast, sys
tree = ast.parse(open(sys.argv[1]).read())
exp = next(ast.literal_eval(n.value) for n in tree.body if isinstance(n, ast.Assign) and getattr(n.targets[0], "id", "") == "EXPECTED")
print("insert into flyway_schema_history (installed_rank, version, script, checksum, success) values "
      + ", ".join(f"({v}, '{v}', 'V{v}__m.sql', {c}, true)" for v, c in sorted(exp.items())) + ";")
PY
)"
pgx "$ins"
mcx 'mc mb t/parkio-media && mc mb --with-lock t/locked-media' || no "setup: buckets"
before="$(docker exec "$P-pg" psql -X -A -t -U parkio_media -d parkio_media -c "select md5(string_agg(f::text, ',' order by installed_rank)) from flyway_schema_history f")"

media http://minio:9000 parkio-media parkio "$ROOTPW"
tool m1.out; sed -e "s#$W#<W>#g" "$W/m1.out"
has m1.out "tool exit=0"; has m1.out "U05 MEDIA READ-ONLY RECORDED - 5 PASS, 0 FINDING, 0 UNKNOWN"
hasE m1.out "identity +RECORDED +media revision 504b9cb453ac$"
hasE m1.out "bucket-mode +PASS +bucket parkio-media: versioning Unversioned, object lock Disabled"
hasE m1.out "permissions +PASS +media-service signs as MinIO's root user"
hasE m1.out "region +PASS +media-service signs for us-east-1 \(default of application.yml \(variable unset\)\); MinIO reports the bucket location us-east-1"
hasE m1.out "network +PASS +endpoint http://minio:9000: minio resolves to [0-9.]+, the MinIO container's own address on $NET; no proxy setting"
hasE m1.out "flyway +PASS +V14 final \(checksum 2068638586\); 18 of 18 expected versions match"
hasE m1.out "erasure-state +RECORDED +media_erasure_jobs 1 row\(s\), highest attempts 3; media_object_writes APPLIED 2, PENDING 1"
E="$(ls -1d "$W"/out/u05/* | tail -1)"
[ "$(stat -c %a "$W/out/u05")" = 700 ] && [ "$(stat -c %a "$E")" = 700 ] && [ "$(stat -c %a "$E/media-readonly.json")" = 600 ] \
  && ok "m1: record dirs 700, file 600" || no "m1 record modes"
python3 -I - "$E/media-readonly.json" <<'PY' && ok "m1: record fields" || no "m1 record fields"
import json, sys
r = json.load(open(sys.argv[1]))["checks"]
assert r["flyway"]["v14State"] == "final" and len(r["flyway"]["history"]) == 18 and r["permissions"]["identity"] == "root"
assert r["bucket-mode"]["versioning"] == "Unversioned" and r["bucket-mode"]["objectLock"] == "Disabled"
assert r["network"]["resolved"] and r["region"]["bucketLocation"] == "us-east-1"
PY
[ "$(docker exec "$P-minio" sh -c 'ls -A /tmp | wc -l')" = 0 ] && ok "m1: nothing left in MinIO's /tmp" || no "m1: MinIO /tmp not empty"
[ "$(docker exec "$P-pg" psql -X -A -t -U parkio_media -d parkio_media -c "select md5(string_agg(f::text, ',' order by installed_rank)) from flyway_schema_history f")" = "$before" ] \
  && ok "m1: the database is unchanged" || no "m1: database changed"
clean m1.out

pgx "update flyway_schema_history set checksum = -1766445011 where version = '14'"
sleep 1; tool m2.out
hasE m2.out "flyway +FINDING +V14 EARLIER \(pre-review\) version \(checksum -1766445011\); 17 of 18 expected versions match; differ: \[14\]"
has m2.out "tool exit=0"; clean m2.out
pgx "update flyway_schema_history set checksum = 2068638586 where version = '14'"

mcx "cat > /tmp/p1.json <<'EOF'
{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Action\":[\"s3:GetObject\",\"s3:PutObject\",\"s3:DeleteObject\",\"s3:ListBucket\"],\"Resource\":[\"arn:aws:s3:::parkio-media\",\"arn:aws:s3:::parkio-media/*\"]}]}
EOF
mc admin policy create t media-basic /tmp/p1.json; rm -f /tmp/p1.json
mc admin user add t mediauser '$USERSECRET' && mc admin policy attach t media-basic --user mediauser" || no "setup: user"
media http://minio:9000 parkio-media mediauser "$USERSECRET"
sleep 1; tool m3.out
hasE m3.out "permissions +FINDING +media-service signs as a non-root MinIO user with policies media-basic; missing: s3:ListBucketVersions, s3:DeleteObjectVersion"
has m3.out "tool exit=0"; clean m3.out
mcx "cat > /tmp/p2.json <<'EOF'
{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Action\":[\"s3:*\"],\"Resource\":[\"arn:aws:s3:::parkio-media\",\"arn:aws:s3:::parkio-media/*\"]}]}
EOF
mc admin policy create t media-full /tmp/p2.json; rm -f /tmp/p2.json
mc admin policy detach t media-basic --user mediauser; mc admin policy attach t media-full --user mediauser" || no "setup: policy"
sleep 1; tool m4.out
hasE m4.out "permissions +PASS +media-service signs as a non-root MinIO user with policies media-full; it allows s3:ListBucketVersions and s3:DeleteObjectVersion"
clean m4.out

media http://minio:9000 locked-media parkio "$ROOTPW"
sleep 1; tool m5.out
hasE m5.out "bucket-mode +FINDING +bucket locked-media: versioning Enabled, object lock Enabled; deletes fail closed while a retention period holds"
clean m5.out
media http://proxy:9000 parkio-media parkio "$ROOTPW"
sleep 1; tool m6.out
hasE m6.out "network +FINDING +endpoint http://proxy:9000: proxy resolves to [0-9.]+, not to the MinIO container on a shared network"
clean m6.out
media http://minio:9000 parkio-media parkio "$ROOTPW" eu-west-1
sleep 1; tool m7.out
hasE m7.out "region +FINDING +media-service signs for eu-west-1 \(PARKIO_MEDIA_STORAGE_REGION\); MinIO reports the bucket location us-east-1: a mismatch causes region redirects"
clean m7.out
media http://minio:9000 parkio-media parkio "$ROOTPW" ""
sleep 1; tool m8.out
hasE m8.out "region +FINDING +PARKIO_MEDIA_STORAGE_REGION is set but empty"
clean m8.out
n="$(find "$W/out" -name media-readonly.json | wc -l)"
docker stop "$P-pg" >/dev/null
sleep 1; tool m9.out
has m9.out "STOP: 0 running 'postgres-media' container(s) in project $P (want exactly 1)"; has m9.out "tool exit=1"
[ "$(find "$W/out" -name media-readonly.json | wc -l)" = "$n" ] && ok "m9: no record" || no "m9 wrote a record"
grep -q -E 'mc --json alias set u05 http://127.0.0.1:9000' "$TOOL" && ! grep -q -E 'MC_HOST_|alias set [^ ]+ [^ ]+ ["$]' "$TOOL" \
  && ok "static: the alias credentials come only from stdin" || no "static: alias credentials"
[ "$fail" -eq 0 ] && echo "SELFTEST PASS" || echo "SELFTEST FAIL"
exit "$fail"
