#!/usr/bin/env bash
# Self-test of r1-real-e2e-account-check.sh against a real postgres:16-alpine (the production image) with the auth
# schema V1-V23 from the release PIN applied (the live auth schema; V24-V27 come with D5), pgcrypto installed, synthetic
# accounts (user@, moderator@ and admin@ on the test domain plus an unrelated account), refresh and password-reset tokens
# and a user_profiles table. S1 ready -> exit 0 with the expected facts, no hash/id/token in the output, report mode 600,
# database content unchanged; S2 a letter-case duplicate, S3 pgcrypto absent, S4 status SUSPENDED, S5 a trigger on
# auth_users, S6 no account, S7 a bad container -> STOP (3) or query failure (1). Needs Docker; removes its container.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"; TOOL="$HERE/r1-real-e2e-account-check.sh"; REPO="$(git -C "$HERE" rev-parse --show-toplevel)"
PIN=f888a81b113c0c486ea0949067779443857e644d
C="r1st-pg-$$"; W="$(mktemp -d)"
trap 'docker rm -f "$C" >/dev/null 2>&1 || true; rm -rf "${W:?}"' EXIT
docker run -d --name "$C" -e POSTGRES_USER=parkio_auth -e POSTGRES_DB=parkio_auth -e POSTGRES_PASSWORD=labonly postgres:16-alpine >/dev/null
for i in $(seq 1 60); do docker exec "$C" pg_isready -U parkio_auth -d parkio_auth >/dev/null 2>&1 && break; sleep 1; done
sleep 2; docker exec "$C" pg_isready -U parkio_auth -d parkio_auth >/dev/null
sq() { docker exec -i "$C" psql -X -q -v ON_ERROR_STOP=1 -U parkio_auth -d parkio_auth "$@"; }
sq <<'SQL'
CREATE TABLE flyway_schema_history (installed_rank INT PRIMARY KEY, version VARCHAR(50), description VARCHAR(200) NOT NULL,
  type VARCHAR(20) NOT NULL, script VARCHAR(1000) NOT NULL, checksum INT, installed_by VARCHAR(100) NOT NULL,
  installed_on TIMESTAMP NOT NULL DEFAULT now(), execution_time INT NOT NULL, success BOOLEAN NOT NULL);
SQL
D=services/auth-service/src/main/resources/db/migration; rank=0
for f in $(git -C "$REPO" ls-tree --name-only "$PIN" "$D/" | sort -V); do
  v="$(basename "$f")"; n="${v#V}"; n="${n%%__*}"; [ "$n" -le 23 ] || continue; rank=$((rank + 1))
  { echo "SET client_min_messages = warning;"; git -C "$REPO" show "$PIN:$f"; } | sq >/dev/null
  sq -c "INSERT INTO flyway_schema_history VALUES ($rank, '$n', 'm', 'SQL', '$v', 0, 'lab', now(), 1, true)" >/dev/null
done
sq <<'SQL' >/dev/null
CREATE EXTENSION pgcrypto;
CREATE TABLE user_profiles (id UUID PRIMARY KEY, auth_user_id UUID NOT NULL, email TEXT NOT NULL, status TEXT NOT NULL);
INSERT INTO auth_users (id, email, password_hash, status, created_at, updated_at, email_verified, email_verified_at, session_epoch)
VALUES ('11111111-1111-1111-1111-111111111111', 'user@real-e2e.parkio.local', crypt('lab-user-pw', gen_salt('bf', 10)), 'ACTIVE', now() - interval '30 days', now() - interval '4 days', TRUE, now(), 3),
       ('22222222-2222-2222-2222-222222222222', 'moderator@real-e2e.parkio.local', crypt('lab-mod-pw', gen_salt('bf', 10)), 'ACTIVE', now(), now(), TRUE, now(), 0),
       ('33333333-3333-3333-3333-333333333333', 'admin@real-e2e.parkio.local', crypt('lab-admin-pw', gen_salt('bf', 10)), 'ACTIVE', now(), now(), TRUE, now(), 0),
       ('44444444-4444-4444-4444-444444444444', 'someone@example.com', crypt('lab-real-pw', gen_salt('bf', 10)), 'ACTIVE', now(), now(), TRUE, now(), 0);
INSERT INTO auth_user_roles (user_id, role_id) SELECT u.id, r.id FROM auth_users u JOIN roles r ON
  (u.email = 'user@real-e2e.parkio.local' AND r.name = 'USER') OR (u.email = 'moderator@real-e2e.parkio.local' AND r.name IN ('USER', 'MODERATOR'))
  OR (u.email = 'admin@real-e2e.parkio.local' AND r.name IN ('USER', 'ADMIN')) OR (u.email = 'someone@example.com' AND r.name = 'USER');
INSERT INTO refresh_tokens (id, user_id, token_hash, expires_at, revoked, created_at, token_family_id, family_started_at)
VALUES (gen_random_uuid(), '11111111-1111-1111-1111-111111111111', 'tokhash-u1', now() + interval '7 days', FALSE, now(), gen_random_uuid(), now()),
       (gen_random_uuid(), '11111111-1111-1111-1111-111111111111', 'tokhash-u2', now() + interval '7 days', FALSE, now(), gen_random_uuid(), now()),
       (gen_random_uuid(), '11111111-1111-1111-1111-111111111111', 'tokhash-u3', now() + interval '7 days', TRUE, now(), gen_random_uuid(), now()),
       (gen_random_uuid(), '22222222-2222-2222-2222-222222222222', 'tokhash-m1', now() + interval '7 days', FALSE, now(), gen_random_uuid(), now());
INSERT INTO password_reset_tokens (id, user_id, token_hash, expires_at, created_at)
VALUES (gen_random_uuid(), '11111111-1111-1111-1111-111111111111', 'resethash-u1', now() + interval '1 hour', now());
INSERT INTO user_profiles VALUES (gen_random_uuid(), '11111111-1111-1111-1111-111111111111', 'user@real-e2e.parkio.local', 'ACTIVE');
SQL
export R1_AUTH_CONTAINER="$C" R1_USER_CONTAINER="$C" R1_OUT="$W/out"
fail=0
check() { if grep -qF -- "$2" "$W/$1"; then echo "ok   $1: $2"; else echo "FAIL $1: $2"; fail=1; fi; }
rc_is() { [ "$(cat "$W/$1.rc")" = "$2" ] && echo "ok   $1 exit $2" || { echo "FAIL $1 exit $(cat "$W/$1.rc"), want $2"; fail=1; }; }
runit() { bash "$TOOL" > "$W/$1" 2>&1 && echo 0 > "$W/$1.rc" || echo $? > "$W/$1.rc"; }
clean() {
  if grep -qE '\$2[abxy]\$[0-9]{2}\$[./A-Za-z0-9]{10}|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}|tokhash|resethash|lab-[a-z]+-pw|labonly' "$W/$1"; then
    echo "FAIL $1: a hash, id, token or secret in the output"; fail=1
  else echo "ok   $1: no hash, id, token or secret in the output"; fi
}
snap() { sq -At -c "select md5(string_agg(x, '' order by x)) from (select t::text x from auth_users t union all select t::text from refresh_tokens t union all select t::text from password_reset_tokens t union all select t::text from auth_user_roles t union all select t::text from user_profiles t) s"; }

before="$(snap)"
runit s1.out; sed -e "s#$W#<W>#g" -e "s#$(hostname)#<host>#g" "$W/s1.out"; rc_is s1.out 0
check s1.out "server 16."
check s1.out "pgcrypto in the auth database: 1."
check s1.out "ok    psql 16 or later in the container"
check s1.out "latest applied V23__auth_user_preferred_locale.sql; failed migrations 0"
check s1.out "ok    columns present (16)"
check s1.out "ok    auth_users.email is unique"
check s1.out "ok    refresh_tokens.revoked_reason allows ADMIN_REVOKED"
check s1.out "ok    no trigger on auth_users, refresh_tokens or password_reset_tokens"
check s1.out "rows with exactly this address: 1; with any letter case: 1"
check s1.out "status ACTIVE; email verified t; roles USER;"
check s1.out "session epoch 3; row version 0; refresh tokens: 2 active, 2 not revoked, 3 in total"
check s1.out "open password-reset tokens: 1; password hash: bcrypt \$2a\$ cost 10"
check s1.out "admin@real-e2e.parkio.local: status ACTIVE; roles ADMIN,USER;"
check s1.out "moderator@real-e2e.parkio.local: status ACTIVE; roles MODERATOR,USER;"
check s1.out "active refresh tokens 1"
check s1.out "accounts in the auth database: 4"
check s1.out "profiles for this address: 1; status ACTIVE"
check s1.out "R1: READY for the scoped recovery of user@real-e2e.parkio.local (R2) - nothing was changed"
clean s1.out
f="$(ls "$W"/out/recovery/R1-*.txt | head -1)"
[ "$(stat -c %a "$f")" = 600 ] && [ "$(stat -c %a "$W/out/recovery")" = 700 ] && echo "ok   report 600 in a 700 directory" || { echo "FAIL report mode"; fail=1; }
cmp -s <(grep -v '^saved: ' "$W/s1.out") "$f" && echo "ok   report = printed text" || { echo "FAIL report differs from the output"; fail=1; }
[ "$(snap)" = "$before" ] && echo "ok   database content unchanged by R1" || { echo "FAIL database content changed"; fail=1; }

sq -c "INSERT INTO auth_users (id, email, password_hash, status, created_at, updated_at, email_verified, session_epoch) VALUES ('55555555-5555-5555-5555-555555555555', 'USER@real-e2e.parkio.local', 'x', 'ACTIVE', now(), now(), TRUE, 0)" >/dev/null
runit s2.out; rc_is s2.out 3; check s2.out "rows with exactly this address: 1; with any letter case: 2"; check s2.out "MISS  exactly one account, no letter-case duplicate"; clean s2.out
sq -c "DELETE FROM auth_users WHERE id = '55555555-5555-5555-5555-555555555555'" >/dev/null
sq -c "DROP EXTENSION pgcrypto" >/dev/null
runit s3.out; rc_is s3.out 3; check s3.out "pgcrypto in the auth database: absent"; check s3.out "MISS  pgcrypto installed in the auth database"
sq -c "CREATE EXTENSION pgcrypto" >/dev/null
sq -c "UPDATE auth_users SET status = 'SUSPENDED' WHERE email = 'user@real-e2e.parkio.local'" >/dev/null
runit s4.out; rc_is s4.out 3; check s4.out "MISS  status ACTIVE and email verified (login needs both; the recovery does not change them) (status SUSPENDED, verified t)"
sq -c "UPDATE auth_users SET status = 'ACTIVE' WHERE email = 'user@real-e2e.parkio.local'" >/dev/null
sq -c "CREATE FUNCTION r1st_noop() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RETURN NEW; END'; CREATE TRIGGER r1st_audit BEFORE UPDATE ON auth_users FOR EACH ROW EXECUTE FUNCTION r1st_noop()" >/dev/null
runit s5.out; rc_is s5.out 3; check s5.out "MISS  no trigger on auth_users, refresh_tokens or password_reset_tokens (auth_users:r1st_audit)"
sq -c "DROP TRIGGER r1st_audit ON auth_users; DROP FUNCTION r1st_noop()" >/dev/null
sq -c "UPDATE auth_users SET email = 'gone@real-e2e.parkio.local' WHERE email = 'user@real-e2e.parkio.local'" >/dev/null
runit s6.out; rc_is s6.out 3; check s6.out "rows with exactly this address: 0; with any letter case: 0"; check s6.out "gone@real-e2e.parkio.local: status ACTIVE"
sq -c "UPDATE auth_users SET email = 'user@real-e2e.parkio.local' WHERE email = 'gone@real-e2e.parkio.local'" >/dev/null
R1_AUTH_CONTAINER="no-such-container-$$" runit s7.out; rc_is s7.out 1; check s7.out "ERROR: container no-such-container-"; check s7.out "R1: query failed - nothing can be concluded; nothing was changed"
runit s8.out; rc_is s8.out 0
[ "$(snap)" = "$before" ] && echo "ok   s8: back to the S1 state, R1 READY again" || { echo "FAIL s8 state"; fail=1; }
[ "$fail" -eq 0 ] && echo "SELFTEST PASS" || echo "SELFTEST FAIL"
exit "$fail"
