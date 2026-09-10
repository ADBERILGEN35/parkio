#!/usr/bin/env bash
#
# PROD-DEPLOY-01A-R8.5/R8.6 — fail-closed contract of the managed parking baseline preparation tool.
#
# az/psql/getent are faked, so every branch — including the ones that must refuse — is exercised
# deterministically on any machine, without an Azure subscription and without touching a database.
# The fakes record their full argv, which is how the "no credential ever reaches argv" assertion is
# made rather than assumed.
#
# The mechanism these preconditions guard is proven separately against a real PostGIS in
# services/parking-service/.../ManagedParkingFlywayBaselineIT.java.
#
# R8.6 SCOPE NOTE. Faking psql cannot prove catalog semantics, and pretending otherwise is exactly
# what let R8.5 ship a census that mis-classified PostGIS composite types: this harness fed the
# result in as FAKE_UNEXPECTED=0, so the query itself was never executed anywhere. Catalog truth
# now belongs to ManagedParkingPublicCensusIT, which runs the shipped SQL against a real PostGIS.
# What this harness owns is the contract around it — that the tool runs that file, interprets its
# output correctly, and fails closed on every ambiguous answer.

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
census_file=""
prev=""
for arg in "$@"; do
  case "$arg" in --command=*) sql="${arg#--command=}" ;; esac
  [ "$prev" = "-f" ] && census_file="$arg"
  prev="$arg"
done
# The census is executed from a file. Record the path so the harness can assert the script runs
# the shipped SQL rather than an inline copy, and answer with the canned census.
if [ -n "$census_file" ]; then
  printf '%s\n' "$census_file" >> "$PARKIO_FAKE_CENSUS_LOG"
  printf '%s' "${FAKE_CENSUS-$DEFAULT_CENSUS}"
  [ -n "${FAKE_CENSUS-$DEFAULT_CENSUS}" ] && printf '\n'
  exit "${FAKE_CENSUS_EXIT:-0}"
fi
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
# A clean certified STATE B census: PostGIS objects (including the composite types and the index
# that produced R8.5's false positive) plus Flyway's own table, nothing unattributed.
DEFAULT_CENSUS='c|geometry_dump|extension:postgis
c|valid_detail|extension:postgis
i|flyway_schema_history_pk|flyway
i|flyway_schema_history_s_idx|flyway
i|spatial_ref_sys_pkey|extension:postgis
r|flyway_schema_history|flyway
r|spatial_ref_sys|extension:postgis
type|geography|extension:postgis
type|geometry|extension:postgis
v|geography_columns|extension:postgis
v|geometry_columns|extension:postgis'
export DEFAULT_CENSUS

reset_env() {
  export PARKIO_FAKE_ARGV_LOG="$TMP/argv.log"
  export PARKIO_FAKE_SQL_LOG="$TMP/sql.log"
  export PARKIO_FAKE_CENSUS_LOG="$TMP/census.log"
  : > "$PARKIO_FAKE_ARGV_LOG"
  : > "$PARKIO_FAKE_SQL_LOG"
  : > "$PARKIO_FAKE_CENSUS_LOG"
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
  export FAKE_HEAD="40"
  export FAKE_FIRST_TYPE="BASELINE"
  unset PARKIO_BASELINE_PREPARE_CONFIRM PARKIO_PG_HOST FAKE_MUTATION_EXIT || true
  unset FAKE_CENSUS FAKE_CENSUS_EXIT || true
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
    bad "$what: expected exit $want, got $STATUS"; echo "$OUT" | awk '{ print "        " $0 }' >&2
  fi
}

expect_output() {
  if printf '%s' "$OUT" | grep -qF "$1"; then ok "$2"; else
    bad "$2: output did not contain '$1'"; echo "$OUT" | awk '{ print "        " $0 }' >&2
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

reset_env; export FAKE_HISTORY_TABLE="false"
export FAKE_CENSUS="$DEFAULT_CENSUS
r|parking_spots|UNATTRIBUTED
i|parking_spots_pkey|UNATTRIBUTED"
run_tool
expect_status 4 "application tables without migration lineage are blocked"
expect_output "application table(s) present" "unexplained-table blocker is reported"
expect_output "parking_spots" "the blocker names the offending application relation"

reset_env; export FAKE_HISTORY_TABLE="false"
export FAKE_CENSUS="$DEFAULT_CENSUS
v|mystery_view|UNATTRIBUTED
type|mystery_enum|UNATTRIBUTED"
run_tool
expect_status 4 "unexpected schema objects are blocked"
expect_output "beyond the accepted PostGIS/Flyway set" "unexpected-object blocker is reported"
expect_output "v:mystery_view" "the blocker names each unattributed object"
expect_output "type:mystery_enum" "the blocker names unattributed types too"

# R8.6 regression: PostGIS composite types and the spatial_ref_sys index must NOT block. This is
# the exact live shape that made the R8.5 tool refuse a valid database.
reset_env
run_tool
expect_output "unexpectedObjects=0" \
  "PostGIS composite types and inherited indexes do not count as unexpected (R8.6 regression)"

# Fail closed when the census cannot be trusted rather than reading silence as cleanliness.
reset_env; export FAKE_CENSUS=""
run_tool
expect_status 3 "an empty census is refused"
expect_output "refusing to interpret that as clean" "empty-census refusal is explicit"
expect_no_drop "an empty census mutates nothing"

reset_env; export FAKE_CENSUS_EXIT="1"
run_tool
expect_status 3 "a failing census is refused"
expect_no_drop "a failing census mutates nothing"

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

echo "== census SQL contract (R8.6) =="

reset_env
run_tool
if grep -q 'scripts/azure/sql/managed-parking-public-census.sql' "$PARKIO_FAKE_CENSUS_LOG"; then
  ok "the tool executes the shipped census SQL file, not an inline copy"
else
  bad "the tool did not run scripts/azure/sql/managed-parking-public-census.sql"
  awk '{ print "        " $0 }' "$PARKIO_FAKE_CENSUS_LOG" >&2
fi
if [ -r "$ROOT/scripts/azure/sql/managed-parking-public-census.sql" ]; then
  ok "the shipped census SQL exists in the repository"
else
  bad "scripts/azure/sql/managed-parking-public-census.sql is missing"
fi
# Catalog semantics are proven by ManagedParkingPublicCensusIT against a real PostGIS; this
# harness only pins that the tool consumes that file and interprets its output correctly.
if grep -q 'pg_type' "$ROOT/scripts/azure/sql/managed-parking-public-census.sql" \
   && grep -q 'pg_index' "$ROOT/scripts/azure/sql/managed-parking-public-census.sql"; then
  ok "the census traces composite types via pg_type and indexes via pg_index"
else
  bad "the census SQL lost its pg_type / pg_index attribution"
fi

reset_env
export PARKIO_EVIDENCE_DIR="$TMP/fresh-evidence-dir/nested"
run_tool
expect_status 0 "the tool creates a missing evidence directory rather than warning"
if [ -f "$TMP/fresh-evidence-dir/nested/managed-parking-flyway-baseline.txt" ]; then
  ok "evidence file is written into the created directory"
else
  bad "evidence file was not written"
fi
perms="$(stat -c '%a' "$TMP/fresh-evidence-dir/nested" 2>/dev/null)"
if [ "$perms" = "750" ]; then ok "evidence directory mode is 0750"; else
  bad "evidence directory mode is '$perms', expected 750"; fi
for key in "verdict=READY" "unexpectedObjects=0" "historyRows=0" "applicationRelations=0" \
           "postgisPresent=true" "censusedObjects="; do
  if grep -q "$key" "$TMP/fresh-evidence-dir/nested/managed-parking-flyway-baseline.txt"; then
    ok "evidence records $key"
  else
    bad "evidence is missing $key"
  fi
done

reset_env
export PARKIO_EVIDENCE_DIR="/proc/parkio-cannot-create-here"
run_tool
expect_status 3 "an uncreatable evidence directory fails clearly instead of warning"
expect_output "cannot create evidence directory" "the failure names the directory problem"
expect_no_drop "an evidence-directory failure mutates nothing"

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
echo "PROD-DEPLOY-01A-R8.6 managed parking baseline preparation gates: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
