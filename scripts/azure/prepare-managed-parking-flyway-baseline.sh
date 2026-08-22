#!/usr/bin/env bash
#
# PROD-DEPLOY-01A-R8.5 — one-time preparation of the managed parking database so the
# application's explicit Flyway baseline can take over.
#
# WHY THIS EXISTS
#
# On Azure Flexible Server the migration role may not run CREATE EXTENSION, so
# V1__enable_postgis.sql can never execute as the application identity and the chain has to begin
# at V2 behind a Flyway BASELINE marker. ManagedFlywayBaselineStrategy establishes that marker on
# any managed database that has no schema history table.
#
# Live parkio_parking is not in that state: earlier deploys attempted V1, and each attempt left the
# history table behind with its failed row rolled back. Flyway refuses to baseline over an existing
# empty history table — verbatim: "already exists, and is empty. Delete the schema history table,
# and run baseline again." Dropping a table is an operator mutation, never an application startup
# side effect, so it lives here.
#
# WHAT IT DOES
#
#   default        read-only. Inspects the target and prints a verdict. Mutates nothing.
#   --apply        performs exactly ONE mutation: DROP TABLE public.flyway_schema_history,
#                  and only when every precondition below holds.
#
# It never runs Flyway, never grants a privilege, never touches an application table, and never
# touches hosted-beta. After a successful --apply the next invite-production deploy baselines at
# version 1 and applies V2..V40; re-running this script then reports CONVERGED.
#
# IDENTITY: the migration role, not the administrator. Flyway created the history table as that
# role, so it already owns it — dropping it needs no elevated identity and none is requested.
#
# CREDENTIALS: read from Key Vault into PGPASSWORD. Never passed in argv, never echoed.
#
# Usage:
#   PARKIO_DEPLOYMENT_PROFILE=invite-production scripts/azure/prepare-managed-parking-flyway-baseline.sh
#   PARKIO_DEPLOYMENT_PROFILE=invite-production \
#     PARKIO_BASELINE_PREPARE_CONFIRM=DROP-EMPTY-FLYWAY-HISTORY/parkio_parking \
#     scripts/azure/prepare-managed-parking-flyway-baseline.sh --apply

set -euo pipefail

EXPECTED_PROFILE="invite-production"
EXPECTED_DATABASE="parkio_parking"
EXPECTED_FQDN_SUFFIX=".postgres.database.azure.com"
CONFIRM_TOKEN="DROP-EMPTY-FLYWAY-HISTORY/${EXPECTED_DATABASE}"
RESOURCE_GROUP="${PARKIO_AZURE_RESOURCE_GROUP:-rg-parkio-invite-production-we}"
FOUNDATION_DEPLOYMENT="${PARKIO_AZURE_FOUNDATION_DEPLOYMENT:-prod-deploy-01a-foundation}"
MIGRATION_ROLE="${POSTGRES_PARKING_MIGRATION_USER:-parkio_parking_migrator}"
MIGRATION_SECRET="postgres-parking-migration-password"
ROOT_CERT="${PARKIO_PG_SSLROOTCERT:-/opt/parkio/certs/azure-postgres-root.crt}"
EVIDENCE_DIR="${PARKIO_EVIDENCE_DIR:-/var/lib/parkio/evidence}"
EVIDENCE_FILE="$EVIDENCE_DIR/managed-parking-flyway-baseline.txt"

MODE=report
case "${1:-}" in
  ""|--dry-run|--report) MODE=report ;;
  --apply)               MODE=apply ;;
  -h|--help) sed -n '2,40p' "$0"; exit 0 ;;
  *) echo "ERROR: unknown argument '$1' (expected --dry-run or --apply)." >&2; exit 2 ;;
esac

die() { echo "ERROR: $*" >&2; exit 3; }

# ---------------------------------------------------------------------------
# 1. Target lock. Everything below is refused unless this is the invite-production
#    managed database — the profile, the resolved server, and the database name.
# ---------------------------------------------------------------------------

for tool in az psql getent; do
  command -v "$tool" >/dev/null 2>&1 || die "required tool not found: $tool"
done

profile="${PARKIO_DEPLOYMENT_PROFILE:-}"
[ "$profile" = "$EXPECTED_PROFILE" ] \
  || die "refusing to run against profile '${profile:-<unset>}'; this tool is $EXPECTED_PROFILE only."

[ -r "$ROOT_CERT" ] || die "PostgreSQL root certificate not readable at $ROOT_CERT."

az login --identity --allow-no-subscriptions --output none

KEY_VAULT_NAME="$(az deployment group show \
  --resource-group "$RESOURCE_GROUP" \
  --name "$FOUNDATION_DEPLOYMENT" \
  --query properties.outputs.keyVaultName.value \
  --output tsv | tr -d '\r')"
POSTGRES_HOST="$(az deployment group show \
  --resource-group "$RESOURCE_GROUP" \
  --name "$FOUNDATION_DEPLOYMENT" \
  --query properties.outputs.postgresqlFqdn.value \
  --output tsv | tr -d '\r')"
[ -n "$KEY_VAULT_NAME" ] && [ -n "$POSTGRES_HOST" ] \
  || die "foundation deployment did not expose Key Vault and PostgreSQL identities."

# The FQDN must come from the invite-production foundation deployment AND look like a managed
# Flexible Server. A hosted-beta or local target cannot satisfy both.
case "$POSTGRES_HOST" in
  *"$EXPECTED_FQDN_SUFFIX") ;;
  *) die "server '$POSTGRES_HOST' is not a managed Azure PostgreSQL FQDN; refusing." ;;
esac
if [ -n "${PARKIO_PG_HOST:-}" ] && [ "$PARKIO_PG_HOST" != "$POSTGRES_HOST" ]; then
  die "PARKIO_PG_HOST='$PARKIO_PG_HOST' does not match the foundation server '$POSTGRES_HOST'."
fi

# Private-endpoint only. A public or loopback answer means we are not on the invite-production
# VNet and must not proceed.
mapfile -t RESOLVED_ADDRESSES < <(getent ahostsv4 "$POSTGRES_HOST" | awk '{print $1}' | sort -u)
[ "${#RESOLVED_ADDRESSES[@]}" -gt 0 ] || die "server hostname did not resolve."
for address in "${RESOLVED_ADDRESSES[@]}"; do
  case "$address" in
    10.*|192.168.*|172.1[6-9].*|172.2[0-9].*|172.3[01].*) ;;
    *) die "server resolved to non-private address $address; refusing." ;;
  esac
done

# ---------------------------------------------------------------------------
# 2. Credentials. Key Vault -> PGPASSWORD only; never argv, never logged.
# ---------------------------------------------------------------------------

umask 077
MIGRATION_PASSWORD="$(az keyvault secret show \
  --vault-name "$KEY_VAULT_NAME" \
  --name "$MIGRATION_SECRET" \
  --query value --output tsv | tr -d '\r')"
[ -n "$MIGRATION_PASSWORD" ] || die "parking migration secret is unavailable."
export PGPASSWORD="$MIGRATION_PASSWORD"
unset MIGRATION_PASSWORD

CONN="host=$POSTGRES_HOST port=5432 dbname=$EXPECTED_DATABASE user=$MIGRATION_ROLE sslmode=verify-full sslrootcert=$ROOT_CERT"

psql_scalar() {
  psql "$CONN" --set=ON_ERROR_STOP=1 --tuples-only --no-align --quiet --command="$1" | tr -d '\r'
}

# ---------------------------------------------------------------------------
# 3. Read-only inspection. No mutation on any path through this section.
# ---------------------------------------------------------------------------

ACTUAL_DATABASE="$(psql_scalar "SELECT current_database()")"
[ "$ACTUAL_DATABASE" = "$EXPECTED_DATABASE" ] \
  || die "connected to database '$ACTUAL_DATABASE', expected '$EXPECTED_DATABASE'."

POSTGIS_VERSION="$(psql_scalar \
  "SELECT coalesce((SELECT extversion FROM pg_extension WHERE extname = 'postgis'), 'ABSENT')")"
HISTORY_TABLE="$(psql_scalar \
  "SELECT (to_regclass('public.flyway_schema_history') IS NOT NULL)::text")"

HISTORY_ROWS=0
HISTORY_FAILED=0
HISTORY_HEAD="-"
HISTORY_FIRST_TYPE="-"
if [ "$HISTORY_TABLE" = "true" ]; then
  HISTORY_ROWS="$(psql_scalar "SELECT count(*) FROM public.flyway_schema_history")"
  HISTORY_FAILED="$(psql_scalar \
    "SELECT count(*) FROM public.flyway_schema_history WHERE NOT success")"
  if [ "$HISTORY_ROWS" -gt 0 ]; then
    HISTORY_HEAD="$(psql_scalar \
      "SELECT coalesce(max(version::text), '-') FROM public.flyway_schema_history WHERE success")"
    HISTORY_FIRST_TYPE="$(psql_scalar \
      "SELECT type FROM public.flyway_schema_history ORDER BY installed_rank LIMIT 1")"
  fi
fi

# Application tables: ordinary/partitioned relations in public that no extension owns, excluding
# Flyway's own. PostGIS objects are extension-owned and correctly ignored here.
APP_TABLES="$(psql_scalar "
  SELECT count(*) FROM pg_class c
  JOIN pg_namespace n ON n.oid = c.relnamespace
  LEFT JOIN pg_depend d ON d.objid = c.oid AND d.deptype = 'e'
  WHERE n.nspname = 'public' AND c.relkind IN ('r','p') AND d.objid IS NULL
    AND c.relname NOT LIKE 'flyway_schema_history%'")"

# Anything else in public that neither PostGIS nor Flyway accounts for: stray views, sequences,
# materialized views, routines. Their presence means the schema is not the state we certified.
UNEXPECTED_OBJECTS="$(psql_scalar "
  SELECT (
    SELECT count(*) FROM pg_class c
    JOIN pg_namespace n ON n.oid = c.relnamespace
    LEFT JOIN pg_depend d ON d.objid = c.oid AND d.deptype = 'e'
    WHERE n.nspname = 'public' AND d.objid IS NULL AND c.relkind NOT IN ('i','I')
      AND c.relname NOT LIKE 'flyway_schema_history%'
  ) + (
    SELECT count(*) FROM pg_proc p
    JOIN pg_namespace n ON n.oid = p.pronamespace
    LEFT JOIN pg_depend d ON d.objid = p.oid AND d.deptype = 'e'
    WHERE n.nspname = 'public' AND d.objid IS NULL
  )")"

# ---------------------------------------------------------------------------
# 4. Verdict. Fail closed: anything not explicitly recognised is BLOCKED.
# ---------------------------------------------------------------------------

BLOCKERS=()
[ "$POSTGIS_VERSION" != "ABSENT" ] || BLOCKERS+=("postgis extension is absent")
[ "$HISTORY_FAILED" -eq 0 ] || BLOCKERS+=("$HISTORY_FAILED failed migration row(s) recorded")

VERDICT=BLOCKED
if [ "${#BLOCKERS[@]}" -eq 0 ]; then
  if [ "$HISTORY_TABLE" = "true" ] && [ "$HISTORY_ROWS" -gt 0 ]; then
    VERDICT=CONVERGED
  elif [ "$APP_TABLES" -ne 0 ]; then
    BLOCKERS+=("$APP_TABLES application table(s) present with no migration lineage")
  elif [ "$UNEXPECTED_OBJECTS" -ne 0 ]; then
    BLOCKERS+=("$UNEXPECTED_OBJECTS schema object(s) beyond the accepted PostGIS/Flyway set")
  elif [ "$HISTORY_TABLE" != "true" ]; then
    VERDICT=ALREADY_PREPARED
  elif [ "$HISTORY_ROWS" -eq 0 ]; then
    VERDICT=READY
  fi
fi

{
  echo "timestamp=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "mode=$MODE"
  echo "profile=$profile"
  echo "server=$POSTGRES_HOST"
  echo "resolvedPrivateAddresses=${RESOLVED_ADDRESSES[*]}"
  echo "database=$ACTUAL_DATABASE"
  echo "identity=$MIGRATION_ROLE"
  echo "postgis=$POSTGIS_VERSION"
  echo "historyTable=$HISTORY_TABLE"
  echo "historyRows=$HISTORY_ROWS"
  echo "historyFailed=$HISTORY_FAILED"
  echo "historyFirstRowType=$HISTORY_FIRST_TYPE"
  echo "historyHeadVersion=$HISTORY_HEAD"
  echo "applicationTables=$APP_TABLES"
  echo "unexpectedObjects=$UNEXPECTED_OBJECTS"
  echo "verdict=$VERDICT"
  for blocker in ${BLOCKERS+"${BLOCKERS[@]}"}; do echo "blocker=$blocker"; done
} > "$EVIDENCE_DIR/.managed-parking-flyway-baseline.$$" 2>/dev/null && {
  mv "$EVIDENCE_DIR/.managed-parking-flyway-baseline.$$" "$EVIDENCE_FILE"
  chmod 0640 "$EVIDENCE_FILE" 2>/dev/null || true
  cat "$EVIDENCE_FILE"
} || {
  echo "WARNING: evidence directory $EVIDENCE_DIR is not writable; reporting to stdout only." >&2
  echo "verdict=$VERDICT"
  echo "postgis=$POSTGIS_VERSION historyTable=$HISTORY_TABLE historyRows=$HISTORY_ROWS"
  echo "historyFailed=$HISTORY_FAILED applicationTables=$APP_TABLES unexpectedObjects=$UNEXPECTED_OBJECTS"
  for blocker in ${BLOCKERS+"${BLOCKERS[@]}"}; do echo "blocker=$blocker"; done
}

case "$VERDICT" in
  CONVERGED)
    echo "Managed parking database already carries migration history (head=$HISTORY_HEAD," \
         "first row type=$HISTORY_FIRST_TYPE). Nothing to prepare."
    ;;
  ALREADY_PREPARED)
    echo "Managed parking database has no schema history table. The next invite-production deploy" \
         "will baseline at version 1 and apply V2+. Nothing to prepare."
    ;;
  READY)
    echo "Managed parking database is in the certified pre-baseline state: PostGIS installed," \
         "an empty flyway_schema_history table, no application tables."
    ;;
  *)
    echo "BLOCKED — refusing to prepare this database." >&2
    for blocker in ${BLOCKERS+"${BLOCKERS[@]}"}; do echo "  - $blocker" >&2; done
    exit 4
    ;;
esac

if [ "$MODE" != "apply" ]; then
  echo "Read-only run: nothing was modified. Re-run with --apply (and the confirmation token) to" \
       "drop the empty history table."
  exit 0
fi

[ "$VERDICT" = "READY" ] \
  || die "--apply is only valid for verdict READY; this database is $VERDICT."
[ "${PARKIO_BASELINE_PREPARE_CONFIRM:-}" = "$CONFIRM_TOKEN" ] \
  || die "--apply requires PARKIO_BASELINE_PREPARE_CONFIRM=$CONFIRM_TOKEN"

# ---------------------------------------------------------------------------
# 5. The single mutation. Re-checked inside the transaction that performs it, so a row written
#    between the inspection above and this statement aborts the drop instead of losing it.
# ---------------------------------------------------------------------------

psql "$CONN" --set=ON_ERROR_STOP=1 --quiet <<'SQL'
BEGIN;
LOCK TABLE public.flyway_schema_history IN ACCESS EXCLUSIVE MODE;
DO $$
BEGIN
  IF (SELECT count(*) FROM public.flyway_schema_history) <> 0 THEN
    RAISE EXCEPTION 'flyway_schema_history is no longer empty; aborting drop';
  END IF;
END $$;
DROP TABLE public.flyway_schema_history;
COMMIT;
SQL

echo "Dropped the empty public.flyway_schema_history table."
echo "Next step: the invite-production deploy establishes the Flyway baseline at version 1 and" \
     "applies V2..V40. Re-run this script read-only afterwards to confirm verdict=CONVERGED with" \
     "historyFirstRowType=BASELINE."
