#!/usr/bin/env bash
# R2 - scoped session revocation and password rotation of user@real-e2e.parkio.local in one transaction (owner request
# 2026-10-12; docs/operations/real-e2e-account-rotation.md section 2, steps 1-2, for this one account only).
#
# Run as civo on parkio-civo-prod after R1 reported READY; it asks for the new password twice, hidden:
#   bash ~/parkio-release-20261009/tools/r2-real-e2e-user-rotate.sh
#
# 1. Guard: the newest ~/parkio-release-20261009/recovery/R1-*.txt says READY and is less than 3 hours old.
# 2. Password: read twice with `read -s` from the terminal; never echoed, never on a command line, never written to a
#    file. Enter the value stored in the GitHub secret HOSTED_BETA_REAL_USER_PASSWORD. Rules - the service's
#    PasswordPolicy plus what bcrypt and the smoke's JSON body carry: 12-72 characters of printable ASCII without space,
#    double quote or backslash; at least one lower-case letter, one upper-case letter and one digit; not a deny-listed
#    word. A failed rule is named; the value is never shown. Nothing reaches the database before both entries pass.
# 3. One transaction in the auth database (docker exec psql as the container's POSTGRES_USER; lock timeout 5 s,
#    statement timeout 60 s). The session first switches off statement, duration and parameter logging for itself
#    (session-level SET before BEGIN, so COMMIT is not logged either; it ends with the session; no server setting
#    changes). The password and its SHA-256 reach the container only as exec environment
#    variables (`docker exec -e NAME`, values not in argv) and the database only as COPY data
#    (`\copy ... FROM PROGRAM 'printenv NAME'` into two ON COMMIT DROP temporary tables), so they are in no SQL text,
#    meta-command argument, log line or output. A temporary function (pg_temp, gone with the session) then does, all or
#    nothing:
#    - the password arrived byte for byte (its SHA-256 in the database equals the one computed here);
#    - exactly one account with that address, also with any letter case; ACTIVE; e-mail verified; bcrypt hash; its row is
#      locked; fingerprints of every other auth_users row and of every other account's refresh tokens are taken;
#    - revokes the account's not-revoked refresh tokens as the service's admin revocation does (revoked = true,
#      revoked_reason = ADMIN_REVOKED, revoked_at = now(), version + 1);
#    - sets the new bcrypt hash made by pgcrypto, crypt(pw, gen_salt('bf', 10)) = $2a$10$ like the service's
#      BCryptPasswordEncoder; session_epoch + 1, so access tokens already minted are rejected at the gateway;
#      version + 1, for the service's optimistic locking; updated_at = now();
#    - verifies before COMMIT: the new hash matches the password, epoch and version advanced by exactly one, no
#      not-revoked token left, every other row unchanged. Any failure rolls everything back.
#    Nothing else changes: no other account or token, no role, status, verification flag, reset token, user-service row,
#    extension or server setting. No login is made.
# 4. Prints counts, epochs, versions and times only; writes ~/parkio-release-20261009/recovery/R2-<UTC stamp>.txt (600).
# Exit 0: committed and verified. 2: the entries differ or break a rule (nothing sent). 3: the transaction refused or
# failed (nothing changed). 4: psql succeeded but the result was incomplete (run R1 to see the state). 1: a guard failed
# (no R1 READY report, no container); nothing was sent.
set -uo pipefail
export LC_ALL=C
AUTH_C="${R2_AUTH_CONTAINER:-parkio-postgres-auth}"
R="${R2_OUT:-$HOME/parkio-release-20261009}"
OUT="$R/recovery"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
START="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
REPORT=""
say() { printf '%s\n' "$*"; REPORT+="$*"$'\n'; }
save() {
  mkdir -p "$OUT" && chmod 700 "$OUT" && ( umask 077; printf '%s' "$REPORT" > "$OUT/R2-$STAMP.txt" ) \
    && echo "saved: $OUT/R2-$STAMP.txt"
}
finish() { local rc="$1"; shift; say "$*"; save; exit "$rc"; }

rule_failed() {  # prints the first rule the candidate breaks; prints nothing when every rule passes
  local p="$1" n
  [ "${#p}" -ge 12 ] || { echo "it is shorter than 12 characters"; return; }
  [ "${#p}" -le 72 ] || { echo "it is longer than 72 bytes (bcrypt's limit)"; return; }
  case "$p" in *[!!-~]*) echo "it contains a space, a control character or a non-ASCII character"; return ;; esac
  case "$p" in *'"'*|*'\'*) echo "it contains a double quote or a backslash (the smoke's JSON body cannot carry them)"; return ;; esac
  case "$p" in *[a-z]*) ;; *) echo "it has no lower-case letter"; return ;; esac
  case "$p" in *[A-Z]*) ;; *) echo "it has no upper-case letter"; return ;; esac
  case "$p" in *[0-9]*) ;; *) echo "it has no digit"; return ;; esac
  n="${p,,}"; n="${n//[!a-z0-9]/}"
  case "$n" in
    password|password1|password12|password123|password1234|password12345|qwerty|qwerty123|qwerty1234|admin|admin123|\
    admin1234|letmein|welcome|welcome123|12345678|123456789|1234567890|11111111|00000000)
      echo "it is a deny-listed word"; return ;;
  esac
}

read -r -d '' SQL <<'SQL'
\set ON_ERROR_STOP on
SET log_min_duration_statement = -1;
SET log_statement = 'none';
SET log_parameter_max_length = 0;
SET log_parameter_max_length_on_error = 0;
SET log_min_error_statement = 'panic';
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';
CREATE TEMP TABLE r2_pw (v text) ON COMMIT DROP;
CREATE TEMP TABLE r2_sha (v text) ON COMMIT DROP;
\copy r2_pw (v) FROM PROGRAM 'printenv R2_NEW_PW'
\copy r2_sha (v) FROM PROGRAM 'printenv R2_PW_SHA256'
CREATE FUNCTION pg_temp.r2_rotate() RETURNS TABLE (k text, v text) LANGUAGE plpgsql AS $fn$
DECLARE
  c_email CONSTANT text := 'user@real-e2e.parkio.local';
  v_pw text; v_sha text; v_n_pw int; v_n_sha int;
  v_exact int; v_any int; v_id uuid; v_status text; v_verified boolean; v_epoch bigint; v_version bigint; v_hash text;
  v_revoked int; v_rows int; v_left int; v_other_users int; v_other_tokens int;
  v_users_before text; v_users_after text; v_tokens_before text; v_tokens_after text; v_updated text;
BEGIN
  SELECT count(*) INTO v_n_pw FROM r2_pw;
  SELECT count(*) INTO v_n_sha FROM r2_sha;
  IF v_n_pw <> 1 OR v_n_sha <> 1 THEN
    RAISE EXCEPTION 'R2: the password did not reach the database (% and % rows); nothing was changed', v_n_pw, v_n_sha;
  END IF;
  SELECT r.v INTO v_pw FROM r2_pw r;
  SELECT r.v INTO v_sha FROM r2_sha r;
  IF v_pw IS NULL OR encode(digest(v_pw, 'sha256'), 'hex') IS DISTINCT FROM v_sha THEN
    RAISE EXCEPTION 'R2: the password did not arrive byte for byte; nothing was changed';
  END IF;
  IF length(v_pw) < 12 OR octet_length(v_pw) > 72 THEN
    RAISE EXCEPTION 'R2: the password breaks the length rule; nothing was changed';
  END IF;
  SELECT count(*) INTO v_exact FROM auth_users WHERE email = c_email;
  SELECT count(*) INTO v_any FROM auth_users WHERE lower(email) = c_email;
  IF v_exact <> 1 OR v_any <> 1 THEN
    RAISE EXCEPTION 'R2: % row(s) with this address and % with any letter case (want 1 and 1); nothing was changed', v_exact, v_any;
  END IF;
  SELECT u.id, u.status, u.email_verified, u.session_epoch, u.version, u.password_hash
    INTO v_id, v_status, v_verified, v_epoch, v_version, v_hash
    FROM auth_users u WHERE u.email = c_email FOR UPDATE;
  IF v_status IS DISTINCT FROM 'ACTIVE' OR v_verified IS NOT TRUE THEN
    RAISE EXCEPTION 'R2: the account is % with e-mail verified %, not ACTIVE and verified; nothing was changed', v_status, v_verified;
  END IF;
  IF v_hash !~ '^\$2[abxy]\$[0-9][0-9]\$' THEN
    RAISE EXCEPTION 'R2: the current password hash is not bcrypt; nothing was changed';
  END IF;
  SELECT count(*), md5(coalesce(string_agg(u::text, '|' ORDER BY u.id), ''))
    INTO v_other_users, v_users_before FROM auth_users u WHERE u.id <> v_id;
  SELECT count(*), md5(coalesce(string_agg(t::text, '|' ORDER BY t.id), ''))
    INTO v_other_tokens, v_tokens_before FROM refresh_tokens t WHERE t.user_id <> v_id;

  UPDATE refresh_tokens
     SET revoked = true, revoked_reason = 'ADMIN_REVOKED', revoked_at = now(), version = version + 1
   WHERE user_id = v_id AND revoked = false;
  GET DIAGNOSTICS v_revoked = ROW_COUNT;

  UPDATE auth_users
     SET password_hash = crypt(v_pw, gen_salt('bf', 10)), session_epoch = session_epoch + 1, version = version + 1,
         updated_at = now()
   WHERE id = v_id AND email = c_email;
  GET DIAGNOSTICS v_rows = ROW_COUNT;
  IF v_rows <> 1 THEN
    RAISE EXCEPTION 'R2: % account row(s) updated (want 1); rolled back', v_rows;
  END IF;

  PERFORM 1 FROM auth_users u
   WHERE u.id = v_id AND u.password_hash ~ '^\$2a\$10\$' AND u.password_hash = crypt(v_pw, u.password_hash)
     AND u.session_epoch = v_epoch + 1 AND u.version = v_version + 1;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'R2: the new hash, epoch or version did not verify; rolled back';
  END IF;
  SELECT count(*) INTO v_left FROM refresh_tokens WHERE user_id = v_id AND revoked = false;
  IF v_left <> 0 THEN
    RAISE EXCEPTION 'R2: % refresh token(s) still not revoked; rolled back', v_left;
  END IF;
  SELECT md5(coalesce(string_agg(u::text, '|' ORDER BY u.id), '')) INTO v_users_after FROM auth_users u WHERE u.id <> v_id;
  SELECT md5(coalesce(string_agg(t::text, '|' ORDER BY t.id), '')) INTO v_tokens_after FROM refresh_tokens t WHERE t.user_id <> v_id;
  IF v_users_after IS DISTINCT FROM v_users_before OR v_tokens_after IS DISTINCT FROM v_tokens_before THEN
    RAISE EXCEPTION 'R2: another account or token changed; rolled back';
  END IF;
  SELECT to_char(u.updated_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"') INTO v_updated FROM auth_users u WHERE u.id = v_id;
  RETURN QUERY VALUES
    ('revoked_tokens', v_revoked::text),
    ('not_revoked_left', v_left::text),
    ('session_epoch', v_epoch || ' -> ' || (v_epoch + 1)),
    ('row_version', v_version || ' -> ' || (v_version + 1)),
    ('password_hash', 'bcrypt $2a$ cost 10; matches the entered password'),
    ('updated_at', v_updated),
    ('other_accounts', v_other_users || ' unchanged'),
    ('other_accounts_tokens', v_other_tokens || ' unchanged');
END
$fn$;
SELECT k || '=' || v FROM pg_temp.r2_rotate();
COMMIT;
SQL

say "R2 scoped recovery of user@real-e2e.parkio.local | $(hostname) | start $START | revocation and rotation in one transaction"
say "== 1. guard"
R1F="$(ls -t "$OUT"/R1-*.txt 2>/dev/null | head -1)"
[ -n "$R1F" ] && grep -q '^R1: READY' "$R1F" || finish 1 "STOP: no R1 READY report in $OUT; run R1 first - nothing was sent"
age=$(( $(date +%s) - $(stat -c %Y "$R1F") ))
[ "$age" -lt 10800 ] || finish 1 "STOP: the last R1 report is $((age / 60)) min old (limit 180); run R1 again - nothing was sent"
say "  R1 READY report $(basename "$R1F") ($((age / 60)) min old)"
docker inspect "$AUTH_C" >/dev/null 2>&1 || finish 1 "STOP: container $AUTH_C not found - nothing was sent"
say "  auth database container $AUTH_C present"

say "== 2. new password (hidden; enter the value stored in the GitHub secret HOSTED_BETA_REAL_USER_PASSWORD)"
IFS= read -r -s -p '  new password for user@real-e2e.parkio.local: ' P1; echo >&2
IFS= read -r -s -p '  repeat the new password: ' P2; echo >&2
if [ -z "$P1" ] || [ "$P1" != "$P2" ]; then
  unset P1 P2
  finish 2 "STOP: the two entries are empty or differ - nothing was sent"
fi
unset P2
why="$(rule_failed "$P1")"
if [ -n "$why" ]; then
  unset P1
  finish 2 "STOP: the password breaks a rule: $why - nothing was sent; choose another value and update the GitHub secret with it"
fi
say "  two matching entries; rules passed (12-72 printable ASCII characters, upper and lower case and a digit, not deny-listed)"
SHA="$(printf '%s' "$P1" | sha256sum | cut -d' ' -f1)"
[ -n "${R2_TEST_CORRUPT_SHA:-}" ] && SHA="$(printf '%s' "x$P1" | sha256sum | cut -d' ' -f1)"   # self-test only

say "== 3. one transaction in the auth database (only this account and its refresh tokens)"
out="$(R2_NEW_PW="$P1" R2_PW_SHA256="$SHA" docker exec -e R2_NEW_PW -e R2_PW_SHA256 -i "$AUTH_C" \
  sh -c 'psql -X -q -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -At -f -' <<< "$SQL" 2>&1)"
rc=$?
out="${out//"$P1"/<hidden>}"; out="${out//"$SHA"/<hidden>}"
unset P1 SHA
if [ "$rc" -ne 0 ]; then
  printf '%s\n' "$out" | grep -E 'ERROR|R2:' | sed -e 's/^psql:[^ ]* //' -e 's/^/  /' | head -5 \
    | while IFS= read -r line; do say "$line"; done
  finish 3 "STOP: the transaction was refused or failed (psql exit $rc) - nothing was changed; do not run R3; send this output"
fi
missing=""
for key in revoked_tokens not_revoked_left session_epoch row_version password_hash updated_at other_accounts other_accounts_tokens; do
  val="$(printf '%s\n' "$out" | sed -n "s/^$key=//p" | head -1)"
  if [ -n "$val" ]; then say "  $key: $val"; else missing="$missing $key"; fi
done
[ -z "$missing" ] || finish 4 "STOP: psql succeeded but the result is incomplete (missing:$missing); run R1 to see the state"
finish 0 "R2 COMMITTED AND VERIFIED - sessions of user@real-e2e.parkio.local revoked and its password rotated; no login was made"
