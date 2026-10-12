#!/usr/bin/env bash
# R1 - read-only account and schema check before the scoped recovery of user@real-e2e.parkio.local (owner request
# 2026-10-12; docs/operations/real-e2e-account-rotation.md).
#
# Run as civo on parkio-civo-prod:
#   bash ~/parkio-release-20261009/tools/r1-real-e2e-account-check.sh
#
# Reads only. Every query runs through `docker exec <postgres container> psql` with the container's own POSTGRES_USER and
# POSTGRES_DB, in a read-only session with statement and lock timeouts (the access T1 uses). It prints names, counts,
# times and the password-hash scheme (algorithm and cost); never a hash, token, account id or secret. It sends no login
# and no HTTP request.
#   A. auth Postgres: server and psql versions (psql 16 has \getenv and \bind), pgcrypto in the auth database.
#   B. auth Flyway: latest applied script and failed migrations.
#   C. The schema the recovery uses: auth_users (unique email; password_hash, session_epoch, status, email_verified,
#      updated_at, version), refresh_tokens (user_id, revoked, revoked_reason, revoked_at, expires_at, version; the
#      revoked_reason check allows ADMIN_REVOKED), password_reset_tokens; any trigger on these tables.
#   D. user@real-e2e.parkio.local only: rows with exactly that address and with any letter case, status, email verified,
#      roles, created/updated time, session epoch, refresh tokens (active / not revoked / total), open password-reset
#      tokens, password-hash scheme.
#   E. Every other account on the real-e2e.parkio.local domain (left untouched): address, status, roles, updated time,
#      active refresh tokens; and the total number of accounts.
#   F. The user-service profile of that address: rows and status (left untouched).
# Exit 0: exactly one such account, ACTIVE and verified, with a bcrypt hash, and every prerequisite present (R2 can be
# prepared). 3: a prerequisite is missing or the account is ambiguous (STOP). 1: a query failed (nothing can be
# concluded). The same text is written to ~/parkio-release-20261009/recovery/R1-<UTC stamp>.txt (mode 600).
set -uo pipefail
AUTH_C="${R1_AUTH_CONTAINER:-parkio-postgres-auth}"
USER_C="${R1_USER_CONTAINER:-parkio-postgres-user}"
OUT="${R1_OUT:-$HOME/parkio-release-20261009}/recovery"
EMAIL="user@real-e2e.parkio.local"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
REPORT=""
missing=0
say() { printf '%s\n' "$*"; REPORT+="$*"$'\n'; }
need() {  # label yes|no [detail]
  if [ "$2" = yes ]; then say "  ok    $1"; else say "  MISS  $1${3:+ ($3)}"; missing=$((missing + 1)); fi
}
save() {
  mkdir -p "$OUT" && chmod 700 "$OUT" && ( umask 077; printf '%s' "$REPORT" > "$OUT/R1-$STAMP.txt" ) \
    && echo "saved: $OUT/R1-$STAMP.txt"
}
fail() { say "ERROR: $*"; say "R1: query failed - nothing can be concluded; nothing was changed"; save; exit 1; }
q() {  # container; SQL on stdin -> rows "a|b|..." on stdout
  { printf '%s\n' "SET default_transaction_read_only = on;" "SET statement_timeout = '30s';" "SET lock_timeout = '2s';"; cat; } \
    | docker exec -i "$1" sh -c 'psql -X -q -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -At -F "|" -f -'
}
yn() { if [ "$1" = "$2" ]; then echo yes; else echo no; fi; }

say "R1 real-e2e account check | $(hostname) | $(date -u +%Y-%m-%dT%H:%M:%SZ) | read-only | account $EMAIL"
say ""
say "A. auth Postgres ($AUTH_C)"
docker inspect "$AUTH_C" >/dev/null 2>&1 || fail "container $AUTH_C not found"
sv="$(q "$AUTH_C" <<< "select current_setting('server_version');")" || fail "cannot query $AUTH_C"
pv="$(docker exec "$AUTH_C" psql --version 2>/dev/null | awk '{ print $3 }')"
pgc="$(q "$AUTH_C" <<< "select coalesce((select extversion from pg_extension where extname = 'pgcrypto'), 'absent');")" \
  || fail "cannot read the extensions"
say "  server $sv; psql ${pv:-?}; pgcrypto in the auth database: $pgc"
need "psql 16 or later in the container (\\getenv and \\bind)" "$([ "${pv%%.*}" -ge 16 ] 2>/dev/null && echo yes || echo no)" "psql ${pv:-?}"
need "pgcrypto installed in the auth database (bcrypt in the database)" "$([ "$pgc" != absent ] && echo yes || echo no)" \
  "installing it would change the schema: owner decision"

say ""
say "B. auth Flyway"
fw="$(q "$AUTH_C" <<'SQL'
select coalesce((select script from flyway_schema_history where success and type = 'SQL' order by installed_rank desc limit 1), '-')
       || '|' || (select count(*) from flyway_schema_history where not success);
SQL
)" || fail "cannot read flyway_schema_history"
say "  latest applied ${fw%%|*}; failed migrations ${fw#*|}"
need "no failed migration" "$(yn "${fw#*|}" 0)"

say ""
say "C. Schema used by the recovery"
want="auth_users.email auth_users.email_verified auth_users.password_hash auth_users.session_epoch auth_users.status auth_users.updated_at auth_users.version password_reset_tokens.consumed_at password_reset_tokens.expires_at password_reset_tokens.user_id refresh_tokens.expires_at refresh_tokens.revoked refresh_tokens.revoked_at refresh_tokens.revoked_reason refresh_tokens.user_id refresh_tokens.version"
have="$(q "$AUTH_C" <<'SQL'
select table_name || '.' || column_name from information_schema.columns
 where table_schema = current_schema() and table_name in ('auth_users', 'refresh_tokens', 'password_reset_tokens')
 order by 1;
SQL
)" || fail "cannot read the columns"
absent=""
for c in $want; do printf '%s\n' "$have" | grep -qx "$c" || absent="$absent $c"; done
need "columns present (16)" "$([ -z "$absent" ] && echo yes || echo no)" "absent:$absent"
uq="$(q "$AUTH_C" <<'SQL'
select count(*) from pg_index i join pg_class c on c.oid = i.indrelid
  join pg_attribute a on a.attrelid = c.oid and a.attnum = i.indkey[0]
 where c.relname = 'auth_users' and c.relnamespace = current_schema()::regnamespace
   and i.indisunique and i.indnatts = 1 and a.attname = 'email';
SQL
)" || fail "cannot read the indexes"
need "auth_users.email is unique" "$([ "${uq:-0}" -ge 1 ] && echo yes || echo no)"
ck="$(q "$AUTH_C" <<'SQL'
select count(*) from pg_constraint
 where conrelid = 'refresh_tokens'::regclass and contype = 'c' and pg_get_constraintdef(oid) like '%ADMIN_REVOKED%';
SQL
)" || fail "cannot read the constraints"
need "refresh_tokens.revoked_reason allows ADMIN_REVOKED" "$([ "${ck:-0}" -ge 1 ] && echo yes || echo no)"
tg="$(q "$AUTH_C" <<'SQL'
select coalesce(string_agg(c.relname || ':' || t.tgname, ', ' order by c.relname, t.tgname), '')
  from pg_trigger t join pg_class c on c.oid = t.tgrelid
 where not t.tgisinternal and c.relname in ('auth_users', 'refresh_tokens', 'password_reset_tokens');
SQL
)" || fail "cannot read the triggers"
need "no trigger on auth_users, refresh_tokens or password_reset_tokens" "$([ -z "$tg" ] && echo yes || echo no)" "$tg"

say ""
say "D. $EMAIL (the only account the recovery touches)"
cnt="$(q "$AUTH_C" <<'SQL'
select (select count(*) from auth_users where email = 'user@real-e2e.parkio.local')
       || '|' || (select count(*) from auth_users where lower(email) = 'user@real-e2e.parkio.local');
SQL
)" || fail "cannot count the account"
say "  rows with exactly this address: ${cnt%%|*}; with any letter case: ${cnt#*|}"
need "exactly one account, no letter-case duplicate" "$(yn "$cnt" "1|1")"
if [ "${cnt%%|*}" = 1 ]; then
  acc="$(q "$AUTH_C" <<'SQL'
select u.status, u.email_verified, coalesce(string_agg(distinct r.name, ',' order by r.name), '-'),
       to_char(u.created_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
       to_char(u.updated_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
       u.session_epoch, u.version,
       (select count(*) from refresh_tokens t where t.user_id = u.id and not t.revoked and t.expires_at > now()),
       (select count(*) from refresh_tokens t where t.user_id = u.id and not t.revoked),
       (select count(*) from refresh_tokens t where t.user_id = u.id),
       (select count(*) from password_reset_tokens p where p.user_id = u.id and p.consumed_at is null and p.expires_at > now()),
       case when u.password_hash ~ '^\$2[abxy]\$[0-9][0-9]\$'
            then 'bcrypt $' || substr(u.password_hash, 2, 2) || '$ cost ' || substr(u.password_hash, 5, 2)
            else 'not bcrypt (' || length(u.password_hash) || ' characters)' end
  from auth_users u
  left join auth_user_roles ur on ur.user_id = u.id
  left join roles r on r.id = ur.role_id
 where u.email = 'user@real-e2e.parkio.local'
 group by u.id;
SQL
)" || fail "cannot read the account"
  IFS='|' read -r st ver roles created updated epoch rowver active notrev total resets scheme <<< "$acc"
  say "  status $st; email verified $ver; roles $roles; created $created; updated $updated"
  say "  session epoch $epoch; row version $rowver; refresh tokens: $active active, $notrev not revoked, $total in total"
  say "  open password-reset tokens: $resets; password hash: $scheme"
  need "status ACTIVE and email verified (login needs both; the recovery does not change them)" \
    "$(yn "$st|$ver" "ACTIVE|t")" "status $st, verified $ver"
  need "password hash is bcrypt (the service's BCryptPasswordEncoder)" "$(case "$scheme" in bcrypt*) echo yes ;; *) echo no ;; esac)" "$scheme"
fi

say ""
say "E. Other accounts on real-e2e.parkio.local (left untouched)"
others="$(q "$AUTH_C" <<'SQL'
select u.email, u.status, coalesce(string_agg(distinct r.name, ',' order by r.name), '-'),
       to_char(u.updated_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
       (select count(*) from refresh_tokens t where t.user_id = u.id and not t.revoked and t.expires_at > now())
  from auth_users u
  left join auth_user_roles ur on ur.user_id = u.id
  left join roles r on r.id = ur.role_id
 where lower(u.email) like '%@real-e2e.parkio.local' and u.email <> 'user@real-e2e.parkio.local'
 group by u.id, u.email
 order by u.email;
SQL
)" || fail "cannot read the other test accounts"
if [ -z "$others" ]; then
  say "  none"
else
  while IFS='|' read -r e s r u a; do say "  $e: status $s; roles $r; updated $u; active refresh tokens $a"; done <<< "$others"
fi
tot="$(q "$AUTH_C" <<< "select count(*) from auth_users;")" || fail "cannot count the accounts"
say "  accounts in the auth database: $tot"

say ""
say "F. user-service profile ($USER_C; left untouched)"
if prof="$(q "$USER_C" <<'SQL' 2>/dev/null
select count(*) || '|' || coalesce(string_agg(status, ','), '-') from user_profiles where lower(email) = 'user@real-e2e.parkio.local';
SQL
)"; then
  say "  profiles for this address: ${prof%%|*}; status ${prof#*|}"
else
  say "  not readable (informational only)"
fi

say ""
if [ "$missing" -eq 0 ]; then
  say "R1: READY for the scoped recovery of $EMAIL (R2) - nothing was changed"
  save
  exit 0
fi
say "R1: STOP - $missing prerequisite(s) missing; nothing was changed"
save
exit 3
