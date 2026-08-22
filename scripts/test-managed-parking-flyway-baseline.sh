#!/usr/bin/env bash
#
# PROD-DEPLOY-01A-R8.5 — fail-closed contract of the managed parking baseline preparation tool.
#
# az/psql/getent are faked, so every branch — including the ones that must refuse — is exercised
# deterministically on any machine, without an Azure subscription and without touching a database.
# The fakes record their full argv, which is how the "no credential ever reaches argv" assertion is
# made rather than assumed.
#
# The mechanism these preconditions guard is proven separately against a real PostGIS in
# services/parking-service/.../ManagedParkingFlywayBaselineIT.java.

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TOOL="$ROOT/scripts/azure/prepare-managed-parking-flyway-baseline.sh"

PASS=0
FAIL=0
ok()  { echo "  PASS  $*"; PASS=$((PASS + 1)); }
bad() { echo "  FAIL  $*" >&2; FAIL=$((FAIL + 1)); }

TMP="$(mktemp -d)"
trap 'rm -rf -- "$TMP"' EXIT
mkdir -p "$TMP/bin" "$TMP/evidence"
: > "$TMP/root.crt"

SECRET_VALUE='s3cr3t-migration-password'

cat > "$TMP/bin/az" <<'FAKE'
#!/usr/bin/env bash
set -u
printf '%s\n' "az $*" >> "$PARKIO_FAKE_ARGV_LOG"
case "$*" in
  *keyVaultName*)    printf '%s\n' "${FAKE_KEY_VAULT:-kv-parkio-invite}" ;;
  *postgresqlFqdn*)  printf '%s\n' "${FAKE_FQDN:?}" ;;
  *"keyvault secret show"*) printf '%s\n' "${FAKE_SECRET:?}" ;;
  *) : ;;
esac
FAKE

cat > "$TMP/bin/getent" <<'FAKE'
#!/usr/bin/env bash
set -u
printf '%s  %s\n' "${FAKE_ADDRESS:-10.0.1.4}" "$2"
FAKE

# Answers each read-only probe from FAKE_* state, and records every statement it is given so the
# tests can assert exactly which mutations were attempted.
cat > "$TMP/bin/psql" <<'FAKE'
#!/usr/bin/env bash
set -u
printf '%s\n' "psql $*" >> "$PARKIO_FAKE_ARGV_LOG"
sql=""
for arg in "$@"; do
  case "$arg" in --command=*) sql="${arg#--command=}" ;; esac
done
if [ -z "$sql" ]; then
  sql="$(cat)"
  printf '%s\n' "$sql" >> "$PARKIO_FAKE_SQL_LOG"
  exit "${FAKE_MUTATION_EXIT:-0}"
fi
printf '%s\n' "$sql" >> "$PARKIO_FAKE_SQL_LOG"
case "$sql" in
  *current_database*)                       printf '%s\n' "${FAKE_DATABASE:-parkio_parking}" ;;
  *extversion*)                             printf '%s\n' "${FAKE_POSTGIS:-3.6.1}" ;;
  *to_regclass*)                            printf '%s\n' "${FAKE_HISTORY_TABLE:-true}" ;;
  *"WHERE NOT success"*)                    printf '%s\n' "${FAKE_HISTORY_FAILED:-0}" ;;
  *"max(version::text)"*)                   printf '%s\n' "${FAKE_HEAD:-40}" ;;
  *"SELECT type FROM public.flyway_schema_history"*) printf '%s\n' "${FAKE_FIRST_TYPE:-BASELINE}" ;;
  *"count(*) FROM public.flyway_schema_history")     printf '%s\n' "${FAKE_HISTORY_ROWS:-0}" ;;
  *pg_proc*)                                printf '%s\n' "${FAKE_UNEXPECTED:-0}" ;;
  *"relkind IN ('r','p')"*)                 printf '%s\n' "${FAKE_APP_TABLES:-0}" ;;
  *)                                        printf '\n' ;;
esac
FAKE

chmod +x "$TMP/bin/az" "$TMP/bin/getent" "$TMP/bin/psql"
export PATH="$TMP/bin:$PATH"

# Baseline environment: the certified READY state on the real invite-production target.
reset_env() {
  export PARKIO_FAKE_ARGV_LOG="$TMP/argv.log"
  export PARKIO_FAKE_SQL_LOG="$TMP/sql.log"
  : > "$PARKIO_FAKE_ARGV_LOG"
  : > "$PARKIO_FAKE_SQL_LOG"
  export PARKIO_DEPLOYMENT_PROFILE=invite-production
  export PARKIO_PG_SSLROOTCERT="$TMP/root.crt"
  export PARKIO_EVIDENCE_DIR="$TMP/evidence"
  export FAKE_FQDN="psql-parkio-invite-production-we.postgres.database.azure.com"
  export FAKE_SECRET="$SECRET_VALUE"
  export FAKE_ADDRESS="10.0.1.4"
  export FAKE_DATABASE="parkio_parking"
  export FAKE_POSTGIS="3.6.1"
  export FAKE_HISTORY_TABLE="true"
  export FAKE_HISTORY_ROWS="0"
  export FAKE_HISTORY_FAILED="0"
  export FAKE_APP_TABLES="0"
  export FAKE_UNEXPECTED="0"
  export FAKE_HEAD="40"
  export FAKE_FIRST_TYPE="BASELINE"
  unset PARKIO_BASELINE_PREPARE_CONFIRM PARKIO_PG_HOST FAKE_MUTATION_EXIT || true
}

# Run the tool, capturing status and combined output.
run_tool() {
  set +e
  OUT="$("$TOOL" "$@" 2>&1)"
  STATUS=$?
  set -e
}

expect_status() {
  local want="$1" what="$2"
  if [ "$STATUS" -eq "$want" ]; then ok "$what (exit $want)"; else
    bad "$what: expected exit $want, got $STATUS"; echo "$OUT" | sed 's/^/        /' >&2
  fi
}

expect_output() {
  if printf '%s' "$OUT" | grep -qF "$1"; then ok "$2"; else
    bad "$2: output did not contain '$1'"; echo "$OUT" | sed 's/^/        /' >&2
  fi
}

expect_no_drop() {
  if grep -qi 'DROP TABLE' "$PARKIO_FAKE_SQL_LOG"; then
    bad "$1: a DROP TABLE was issued"
  else ok "$1"; fi
}

echo "== target lock =="

reset_env; unset PARKIO_DEPLOYMENT_PROFILE
run_tool
expect_status 3 "unset deployment profile is refused"
expect_no_drop "unset profile mutates nothing"

reset_env; export PARKIO_DEPLOYMENT_PROFILE=hosted-beta
run_tool
expect_status 3 "hosted-beta target is refused"
expect_output "this tool is invite-production only" "hosted-beta refusal names the required profile"
expect_no_drop "hosted-beta mutates nothing"

reset_env; export PARKIO_DEPLOYMENT_PROFILE=azure-hosted-beta
run_tool
expect_status 3 "azure-hosted-beta target is refused"

reset_env; export FAKE_FQDN="parkio-hosted-beta.westeurope.cloudapp.azure.com"
run_tool
expect_status 3 "a non-Flexible-Server FQDN is refused"
expect_output "not a managed Azure PostgreSQL FQDN" "FQDN refusal is explicit"

reset_env; export PARKIO_PG_HOST="psql-someone-else.postgres.database.azure.com"
run_tool
expect_status 3 "an operator-supplied host that disagrees with the foundation output is refused"

reset_env; export FAKE_ADDRESS="52.178.10.11"
run_tool
expect_status 3 "a server resolving to a public address is refused"
expect_output "non-private address" "public-address refusal is explicit"

reset_env; export FAKE_ADDRESS="127.0.0.1"
run_tool
expect_status 3 "a loopback target is refused"

reset_env; export FAKE_DATABASE="parkio_media"
run_tool
expect_status 3 "connecting to the wrong database is refused"
expect_output "expected 'parkio_parking'" "wrong-database refusal names the expected database"

reset_env
run_tool --nonsense
expect_status 2 "an unknown argument is refused"

echo "== schema-state preconditions =="

reset_env; export FAKE_POSTGIS="ABSENT"
run_tool
expect_status 4 "a database without PostGIS is blocked"
expect_output "postgis extension is absent" "missing-PostGIS blocker is reported"
expect_no_drop "missing PostGIS mutates nothing"

reset_env; export FAKE_HISTORY_ROWS="3" FAKE_HISTORY_FAILED="1"
run_tool
expect_status 4 "a recorded failed migration is blocked"
expect_output "failed migration row(s) recorded" "failed-migration blocker is reported"

reset_env; export FAKE_HISTORY_TABLE="false" FAKE_APP_TABLES="12"
run_tool
expect_status 4 "application tables without migration lineage are blocked"
expect_output "application table(s) present" "unexplained-table blocker is reported"

reset_env; export FAKE_HISTORY_TABLE="false" FAKE_UNEXPECTED="2"
run_tool
expect_status 4 "unexpected schema objects are blocked"
expect_output "beyond the accepted PostGIS/Flyway set" "unexpected-object blocker is reported"

echo "== verdicts =="

reset_env; export FAKE_HISTORY_TABLE="false"
run_tool
expect_status 0 "a managed database with no history table needs no preparation"
expect_output "verdict=ALREADY_PREPARED" "no-history-table state reports ALREADY_PREPARED"
expect_no_drop "ALREADY_PREPARED mutates nothing"

reset_env; export FAKE_HISTORY_ROWS="40" FAKE_FIRST_TYPE="BASELINE" FAKE_HEAD="40"
run_tool
expect_status 0 "an already-migrated database reports CONVERGED"
expect_output "verdict=CONVERGED" "converged state is reported"
expect_output "historyFirstRowType=BASELINE" "converged evidence proves the first row is the baseline"
expect_output "historyHeadVersion=40" "converged evidence records the head version"
expect_no_drop "CONVERGED mutates nothing"

reset_env
run_tool
expect_status 0 "the live pre-baseline state reports READY"
expect_output "verdict=READY" "READY verdict is reported"
expect_output "Read-only run: nothing was modified" "the default run states that it mutated nothing"
expect_no_drop "the default run is read-only"

echo "== the one mutation =="

reset_env
run_tool --apply
expect_status 3 "--apply without the confirmation token is refused"
expect_output "PARKIO_BASELINE_PREPARE_CONFIRM" "the refusal names the required token"
expect_no_drop "an unconfirmed --apply mutates nothing"

reset_env; export PARKIO_BASELINE_PREPARE_CONFIRM="DROP-EMPTY-FLYWAY-HISTORY/parkio_media"
run_tool --apply
expect_status 3 "a confirmation token for another database is refused"
expect_no_drop "a mismatched token mutates nothing"

reset_env; export PARKIO_BASELINE_PREPARE_CONFIRM="DROP-EMPTY-FLYWAY-HISTORY/parkio_parking"
export FAKE_HISTORY_ROWS="40" FAKE_FIRST_TYPE="BASELINE"
run_tool --apply
expect_status 3 "--apply against a CONVERGED database is refused"
expect_output "only valid for verdict READY" "the refusal names the required verdict"
expect_no_drop "--apply on CONVERGED mutates nothing"

reset_env; export PARKIO_BASELINE_PREPARE_CONFIRM="DROP-EMPTY-FLYWAY-HISTORY/parkio_parking"
export FAKE_HISTORY_TABLE="false"
run_tool --apply
expect_status 3 "--apply against an ALREADY_PREPARED database is refused"
expect_no_drop "--apply on ALREADY_PREPARED mutates nothing"

reset_env; export PARKIO_BASELINE_PREPARE_CONFIRM="DROP-EMPTY-FLYWAY-HISTORY/parkio_parking"
run_tool --apply
expect_status 0 "a confirmed --apply on the READY state succeeds"
drops="$(grep -ci 'DROP TABLE' "$PARKIO_FAKE_SQL_LOG" || true)"
if [ "$drops" -eq 1 ]; then ok "exactly one DROP TABLE is issued"; else
  bad "expected exactly one DROP TABLE, saw $drops"; fi
if grep -q 'DROP TABLE public.flyway_schema_history' "$PARKIO_FAKE_SQL_LOG"; then
  ok "the drop targets only public.flyway_schema_history"
else bad "the drop did not target public.flyway_schema_history"; fi
if grep -q 'ACCESS EXCLUSIVE MODE' "$PARKIO_FAKE_SQL_LOG" \
   && grep -q 'no longer empty; aborting drop' "$PARKIO_FAKE_SQL_LOG"; then
  ok "the drop re-checks emptiness inside its own locked transaction"
else bad "the drop is not guarded by an in-transaction emptiness re-check"; fi
for forbidden in 'CREATE EXTENSION' 'GRANT ' 'ALTER ROLE' 'DELETE FROM' 'INSERT INTO' 'TRUNCATE'; do
  if grep -q "$forbidden" "$PARKIO_FAKE_SQL_LOG"; then
    bad "the tool issued a forbidden statement: $forbidden"
  fi
done
ok "no extension, grant, role or history-row statement is ever issued"

echo "== credential handling =="

reset_env; export PARKIO_BASELINE_PREPARE_CONFIRM="DROP-EMPTY-FLYWAY-HISTORY/parkio_parking"
run_tool --apply
if grep -qF "$SECRET_VALUE" "$PARKIO_FAKE_ARGV_LOG"; then
  bad "the migration password reached a subprocess argv"
else ok "no credential ever reaches argv"; fi
if printf '%s' "$OUT" | grep -qF "$SECRET_VALUE"; then
  bad "the migration password was printed"
else ok "no credential is printed"; fi
if grep -qF "$SECRET_VALUE" "$TMP/evidence/managed-parking-flyway-baseline.txt" 2>/dev/null; then
  bad "the migration password was written to the evidence file"
else ok "no credential is written to the evidence file"; fi
if grep -q 'sslmode=verify-full' "$PARKIO_FAKE_ARGV_LOG"; then
  ok "every psql connection pins sslmode=verify-full"
else bad "a psql connection did not pin verify-full TLS"; fi

echo
echo "PROD-DEPLOY-01A-R8.5 managed parking baseline preparation gates: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
