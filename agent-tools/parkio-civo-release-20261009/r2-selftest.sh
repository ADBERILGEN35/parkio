#!/usr/bin/env bash
# Self-test of r2-real-e2e-user-rotate.sh (with r1-real-e2e-account-check.sh for its guard) against a real
# postgres:16-alpine (the production image) started with the most verbose statement and parameter logging
# (log_statement=all, log_min_duration_statement=0, log_parameter_max_length=-1), the auth schema V1-V23 from the release
# PIN, pgcrypto, the user@ account (epoch 2, version 1) with two active, one expired-not-revoked and one revoked refresh
# token, other accounts with their own tokens, roles, reset tokens and profiles.
# S1 rotation -> exit 0: the new hash verifies with the entered password ($2a$10$), epoch and version +1, updated_at new,
#    the three not-revoked tokens revoked as ADMIN_REVOKED with revoked_at and version +1, the revoked one untouched;
#    every other row (other accounts, their tokens, roles, reset tokens, profiles) unchanged; status, verification and
#    roles of user@ unchanged; no temporary object left; report 600; neither the password nor its SHA-256 in the output,
#    the report or the server log; R1 afterwards READY with 0 active tokens.
# S2 entries differ, S3 nine rule violations -> exit 2, nothing changed. S4 SUSPENDED, S5 letter-case duplicate,
# S6 corrupted transport (SHA-256 mismatch) -> exit 3, nothing changed. S7 no R1 READY report -> exit 1.
# S8 a password with quote, $, backticks, ;, | and & -> stored byte for byte, nothing executed.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"; R1="$HERE/r1-real-e2e-account-check.sh"; TOOL="$HERE/r2-real-e2e-user-rotate.sh"
REPO="$(git -C "$HERE" rev-parse --show-toplevel)"; PIN=f888a81b113c0c486ea0949067779443857e644d
C="r2st-pg-$$"; W="$(mktemp -d)"
trap 'docker rm -f "$C" >/dev/null 2>&1 || true; rm -rf "${W:?}"' EXIT
docker run -d --name "$C" -e POSTGRES_USER=parkio_auth -e POSTGRES_DB=parkio_auth -e POSTGRES_PASSWORD=labonly postgres:16-alpine \
  -c log_statement=all -c log_min_duration_statement=0 -c log_parameter_max_length=-1 >/dev/null
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
INSERT INTO auth_users (id, email, password_hash, status, version, created_at, updated_at, email_verified, email_verified_at, session_epoch)
VALUES ('11111111-1111-1111-1111-111111111111', 'user@real-e2e.parkio.local', crypt('Old-lab-pw-123', gen_salt('bf', 10)), 'ACTIVE', 1, now() - interval '30 days', now() - interval '4 days', TRUE, now(), 2),
       ('44444444-4444-4444-4444-444444444444', 'someone@example.com', crypt('Lab-real-pw-123', gen_salt('bf', 10)), 'ACTIVE', 0, now(), now() - interval '1 day', TRUE, now(), 0),
       ('55555555-5555-5555-5555-555555555555', 'other@example.com', crypt('Lab-other-pw-1', gen_salt('bf', 10)), 'ACTIVE', 4, now(), now() - interval '2 days', TRUE, now(), 7),
       ('66666666-6666-6666-6666-666666666666', 'admin@example.com', crypt('Lab-admin-pw-1', gen_salt('bf', 10)), 'ACTIVE', 2, now(), now() - interval '3 days', TRUE, now(), 1);
INSERT INTO auth_user_roles (user_id, role_id) SELECT u.id, r.id FROM auth_users u JOIN roles r ON r.name = 'USER';
INSERT INTO auth_user_roles (user_id, role_id) SELECT u.id, r.id FROM auth_users u JOIN roles r ON r.name = 'ADMIN' WHERE u.email = 'admin@example.com';
INSERT INTO refresh_tokens (id, user_id, token_hash, expires_at, revoked, created_at, token_family_id, family_started_at, revoked_reason, revoked_at)
VALUES ('a0000000-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111', 'tokhash-u1', now() + interval '7 days', FALSE, now(), gen_random_uuid(), now(), NULL, NULL),
       ('a0000000-0000-0000-0000-000000000002', '11111111-1111-1111-1111-111111111111', 'tokhash-u2', now() + interval '7 days', FALSE, now(), gen_random_uuid(), now(), NULL, NULL),
       ('a0000000-0000-0000-0000-000000000003', '11111111-1111-1111-1111-111111111111', 'tokhash-u3', now() - interval '1 day', FALSE, now() - interval '9 days', gen_random_uuid(), now(), NULL, NULL),
       ('a0000000-0000-0000-0000-000000000004', '11111111-1111-1111-1111-111111111111', 'tokhash-u4', now() + interval '7 days', TRUE, now(), gen_random_uuid(), now(), 'LOGOUT', now() - interval '2 days'),
       ('b0000000-0000-0000-0000-000000000001', '44444444-4444-4444-4444-444444444444', 'tokhash-s1', now() + interval '7 days', FALSE, now(), gen_random_uuid(), now(), NULL, NULL),
       ('b0000000-0000-0000-0000-000000000002', '55555555-5555-5555-5555-555555555555', 'tokhash-o1', now() + interval '7 days', FALSE, now(), gen_random_uuid(), now(), NULL, NULL);
INSERT INTO password_reset_tokens (id, user_id, token_hash, expires_at, created_at)
VALUES (gen_random_uuid(), '44444444-4444-4444-4444-444444444444', 'resethash-s1', now() + interval '1 hour', now());
INSERT INTO user_profiles SELECT gen_random_uuid(), id, email, 'ACTIVE' FROM auth_users;
SQL
export R1_AUTH_CONTAINER="$C" R1_USER_CONTAINER="$C" R1_OUT="$W/out" R2_AUTH_CONTAINER="$C" R2_OUT="$W/out"
NEWPW='Kx7mQ2vLp9TzR4wN8yB3cD6fG1hJ5sAe'
fail=0
ok() { echo "ok   $*"; }
no() { echo "FAIL $*"; fail=1; }
check() { if grep -qF -- "$2" "$W/$1"; then ok "$1: $2"; else no "$1: $2"; fi; }
rc_is() { [ "$(cat "$W/$1.rc")" = "$2" ] && ok "$1 exit $2" || no "$1 exit $(cat "$W/$1.rc"), want $2"; }
r2() {  # name pw1 pw2 [env...]
  local name="$1" a="$2" b="$3"; shift 3
  printf '%s\n%s\n' "$a" "$b" | env "$@" bash "$TOOL" > "$W/$name" 2>&1 && echo 0 > "$W/$name.rc" || echo $? > "$W/$name.rc"
}
others() {  # fingerprint of everything R2 must not touch
  sq -At -c "select md5(string_agg(x, '' order by x)) from (
    select t::text x from auth_users t where t.email <> 'user@real-e2e.parkio.local'
    union all select t::text from refresh_tokens t where t.user_id <> '11111111-1111-1111-1111-111111111111'
    union all select t::text from refresh_tokens t where t.id = 'a0000000-0000-0000-0000-000000000004'
    union all select t::text from auth_user_roles t union all select t::text from password_reset_tokens t
    union all select t::text from user_profiles t union all select extname from pg_extension) s"
}
everything() { sq -At -c "select md5(string_agg(x, '' order by x)) from (select t::text x from auth_users t union all select t::text from refresh_tokens t union all select t::text from auth_user_roles t union all select t::text from password_reset_tokens t union all select t::text from user_profiles t) s"; }
clean() {  # name: no password, SHA-256, hash, id or token in the output
  local sha; sha="$(printf '%s' "$NEWPW" | sha256sum | cut -d' ' -f1)"
  if grep -qF -e "$NEWPW" -e "$sha" -e 'Old-lab-pw' -e 'tokhash' "$W/$1" \
     || grep -qE '\$2[abxy]\$[0-9]{2}\$[./A-Za-z0-9]{10}|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}' "$W/$1"; then
    no "$1: a password, SHA-256, hash, id or token in the output"
  else ok "$1: no password, SHA-256, hash, id or token in the output"; fi
}

bash "$R1" > "$W/r1-before.out" 2>&1 || { cat "$W/r1-before.out"; no "R1 not READY in the lab"; }
OTHERS0="$(others)"; ALL0="$(everything)"

r2 s2.out "$NEWPW" "${NEWPW}x"; rc_is s2.out 2; check s2.out "STOP: the two entries are empty or differ - nothing was sent"
for pair in "short:Short1aA:shorter than 12 characters" "nodigit:NoDigitsHereAtAll:has no digit" "nolower:ALLUPPER1234567:has no lower-case letter" \
            "noupper:alllower1234567:has no upper-case letter" 'quote:Has"Quote12345:contains a double quote or a backslash' \
            "space:Has Space12345a:contains a space, a control character or a non-ASCII character" \
            "unicode:Ünicode1234abcd:contains a space, a control character or a non-ASCII character" \
            "deny:Password1234:is a deny-listed word" "long:Aa1$(printf 'x%.0s' $(seq 1 70)):longer than 72 bytes"; do
  name="${pair%%:*}"; rest="${pair#*:}"; pw="${rest%%:*}"; why="${rest#*:}"
  r2 "s3-$name.out" "$pw" "$pw"; rc_is "s3-$name.out" 2; check "s3-$name.out" "$why"
done
[ "$(everything)" = "$ALL0" ] && ok "S2/S3: nothing changed" || no "S2/S3 changed the database"

sq -c "UPDATE auth_users SET status = 'SUSPENDED' WHERE email = 'user@real-e2e.parkio.local'" >/dev/null; ALLS="$(everything)"
r2 s4.out "$NEWPW" "$NEWPW"; rc_is s4.out 3; check s4.out "R2: the account is SUSPENDED with e-mail verified t, not ACTIVE and verified; nothing was changed"
[ "$(everything)" = "$ALLS" ] && ok "S4: nothing changed" || no "S4 changed the database"; clean s4.out
sq -c "UPDATE auth_users SET status = 'ACTIVE' WHERE email = 'user@real-e2e.parkio.local'" >/dev/null
sq -c "INSERT INTO auth_users (id, email, password_hash, status, created_at, updated_at, email_verified, session_epoch) VALUES ('77777777-7777-7777-7777-777777777777', 'User@real-e2e.parkio.local', 'x', 'ACTIVE', now(), now(), TRUE, 0)" >/dev/null; ALLS="$(everything)"
r2 s5.out "$NEWPW" "$NEWPW"; rc_is s5.out 3; check s5.out "R2: 1 row(s) with this address and 2 with any letter case (want 1 and 1); nothing was changed"
[ "$(everything)" = "$ALLS" ] && ok "S5: nothing changed" || no "S5 changed the database"
sq -c "DELETE FROM auth_users WHERE id = '77777777-7777-7777-7777-777777777777'" >/dev/null
r2 s6.out "$NEWPW" "$NEWPW" R2_TEST_CORRUPT_SHA=1; rc_is s6.out 3; check s6.out "R2: the password did not arrive byte for byte; nothing was changed"
[ "$(everything)" = "$ALL0" ] && ok "S6: nothing changed" || no "S6 changed the database"; clean s6.out
r2 s7.out "$NEWPW" "$NEWPW" R2_OUT="$W/empty"; rc_is s7.out 1; check s7.out "STOP: no R1 READY report in $W/empty/recovery; run R1 first - nothing was sent"
[ "$(everything)" = "$ALL0" ] && ok "S7: nothing changed" || no "S7 changed the database"

r2 s1.out "$NEWPW" "$NEWPW"; docker logs "$C" > "$W/log-s1" 2>&1; sed -e "s#$W#<W>#g" -e "s#$(hostname)#<host>#g" "$W/s1.out"; rc_is s1.out 0
check s1.out "R1 READY report R1-"
check s1.out "two matching entries; rules passed"
check s1.out "revoked_tokens: 3"
check s1.out "not_revoked_left: 0"
check s1.out "session_epoch: 2 -> 3"
check s1.out "row_version: 1 -> 2"
check s1.out "password_hash: bcrypt \$2a\$ cost 10; matches the entered password"
check s1.out "other_accounts: 3 unchanged"
check s1.out "other_accounts_tokens: 2 unchanged"
check s1.out "R2 COMMITTED AND VERIFIED"
clean s1.out
st="$(sq -At -F '|' -c "SET log_statement = 'none'" -c "SET log_min_duration_statement = -1" -c "select password_hash = crypt('$NEWPW', password_hash), password_hash like '\$2a\$10\$%', session_epoch, version, status, email_verified, updated_at > now() - interval '5 minutes' from auth_users where email = 'user@real-e2e.parkio.local'")"
[ "$st" = "t|t|3|2|ACTIVE|t|t" ] && ok "S1: new \$2a\$10\$ hash verifies, epoch 3, version 2, ACTIVE, verified, updated now" || no "S1 account state: $st"
tk="$(sq -At -F '|' -c "select id, revoked, coalesce(revoked_reason, '-'), revoked_at > now() - interval '5 minutes', version from refresh_tokens where user_id = '11111111-1111-1111-1111-111111111111' order by id" | sed 's/a0000000-0000-0000-0000-00000000000//' | tr '\n' ' ')"
[ "$tk" = "1|t|ADMIN_REVOKED|t|1 2|t|ADMIN_REVOKED|t|1 3|t|ADMIN_REVOKED|t|1 4|t|LOGOUT|f|0 " ] && ok "S1: the three not-revoked tokens ADMIN_REVOKED with revoked_at and version +1; the revoked one untouched" || no "S1 tokens: $tk"
[ "$(others)" = "$OTHERS0" ] && ok "S1: other accounts, their tokens, roles, reset tokens, profiles and extensions unchanged" || no "S1 changed another row"
[ "$(sq -At -c "select count(*) from pg_class c join pg_namespace n on n.oid = c.relnamespace where n.nspname like 'pg_temp%' and c.relname like 'r2_%'") $(sq -At -c "select count(*) from pg_proc p join pg_namespace n on n.oid = p.pronamespace where n.nspname like 'pg_temp%'")" = "0 0" ] && ok "S1: no temporary table or function left" || no "S1 left temporary objects"
f="$(ls -t "$W"/out/recovery/R2-*.txt | head -1)"; [ "$(stat -c %a "$f")" = 600 ] && ok "S1: report 600" || no "S1 report mode"
grep -qF "$NEWPW" "$f" && no "S1 report holds the password" || ok "S1: the report holds no password"
sha="$(printf '%s' "$NEWPW" | sha256sum | cut -d' ' -f1)"
[ "$(grep -c -F -e "$NEWPW" -e "$sha" "$W/log-s1")" = 0 ] && ok "server log after S1 (log_statement=all, log_min_duration_statement=0, all parameters): no password, no SHA-256" || no "the password or its SHA-256 is in the server log"
pids="$({ grep "statement: SET log_statement = 'none'" "$W/log-s1" || true; } | sed -E 's/.*\[([0-9]+)\].*/\1/' | sort -u)"
extra=0; for pid in $pids; do
  n="$({ grep -F "[$pid]" "$W/log-s1" || true; } | { grep 'statement:' || true; } | { grep -v -E "statement: SET (log_min_duration_statement = -1|log_statement = 'none');" || true; } | wc -l)"
  extra=$((extra + n)); done
[ -n "$pids" ] && [ "$extra" -eq 0 ] && ok "server log: each R2 session ($(echo $pids | wc -w)) logged only its first two SETs - no BEGIN, COPY, function or COMMIT" || no "an R2 statement after the logging switch was logged ($extra line(s))"
sleep 1; bash "$R1" > "$W/r1-after.out" 2>&1 && ok "R1 after R2: READY" || no "R1 after R2 not READY"
check r1-after.out "session epoch 3; row version 2; refresh tokens: 0 active, 0 not revoked, 4 in total"

PW8='Rx7$(touch${IFS}/tmp/r2pwned);ls|wc&`id`Zz9'"'"'q'
r2 s8.out "$PW8" "$PW8"; docker logs "$C" > "$W/log-s8" 2>&1; rc_is s8.out 0; check s8.out "R2 COMMITTED AND VERIFIED"
pw8sql="${PW8//\'/\'\'}"
[ "$(sq -At -c "SET log_statement = 'none'" -c "SET log_min_duration_statement = -1" -c "select password_hash = crypt('$pw8sql', password_hash) from auth_users where email = 'user@real-e2e.parkio.local'")" = t ] && ok "S8: special characters stored byte for byte" || no "S8 hash does not verify"
docker exec "$C" test -e /tmp/r2pwned && no "S8 executed part of the password" || ok "S8: nothing in the password was executed"
grep -qF -e "$PW8" -e 'touch${IFS}' "$W/s8.out" && no "S8 output holds the password" || ok "S8: no password in the output"
[ "$(grep -c -F -e "$PW8" -e 'touch${IFS}' "$W/log-s8")" = 0 ] && ok "S8: no password in the server log" || no "S8 password in the server log"
[ "$(docker logs "$C" 2>&1 | grep -c -F -e "$NEWPW" -e "$sha" -e "$PW8")" = 0 ] && ok "whole server log at the end: no password, no SHA-256" || no "a password or SHA-256 in the final server log"
[ "$fail" -eq 0 ] && echo "SELFTEST PASS" || echo "SELFTEST FAIL"
exit "$fail"
