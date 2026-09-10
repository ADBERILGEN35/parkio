#!/usr/bin/env bash
# Create least-privilege migration/runtime roles and prove private verify-full TLS.
# Uses only empty/synthetic invite-production databases and never emits passwords.

set -euo pipefail

RESOURCE_GROUP="rg-parkio-invite-production-we"
FOUNDATION_DEPLOYMENT="prod-deploy-01a-foundation"
POSTGRES_ADMIN_USER="parkioops"
ROOT_CERT="/opt/parkio/certs/azure-postgres-root.crt"
EVIDENCE_DIR="/var/lib/parkio/evidence"
EVIDENCE_FILE="$EVIDENCE_DIR/managed-postgresql-bootstrap.txt"

if [ "$(id -u)" -ne 0 ]; then
  echo "ERROR: database bootstrap must run as root." >&2
  exit 2
fi

for tool in az psql getent; do
  command -v "$tool" >/dev/null 2>&1 || { echo "ERROR: required tool not found: $tool" >&2; exit 2; }
done
[ -r "$ROOT_CERT" ] || { echo "ERROR: root certificate bundle not found." >&2; exit 2; }

install -d -m 0750 "$EVIDENCE_DIR"
umask 077
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
if [ -z "$KEY_VAULT_NAME" ] || [ -z "$POSTGRES_HOST" ]; then
  echo "ERROR: foundation deployment did not expose Key Vault and PostgreSQL identities." >&2
  exit 3
fi
POSTGRES_ADMIN_PASSWORD="$(az keyvault secret show \
  --vault-name "$KEY_VAULT_NAME" \
  --name postgresql-administrator-password \
  --query value \
  --output tsv | tr -d '\r')"
if [ -z "$POSTGRES_ADMIN_PASSWORD" ]; then
  echo "ERROR: PostgreSQL administrator secret is unavailable." >&2
  exit 3
fi

mapfile -t RESOLVED_ADDRESSES < <(getent ahostsv4 "$POSTGRES_HOST" | awk '{print $1}' | sort -u)
if [ "${#RESOLVED_ADDRESSES[@]}" -eq 0 ]; then
  echo "ERROR: private PostgreSQL hostname did not resolve." >&2
  exit 3
fi
for address in "${RESOLVED_ADDRESSES[@]}"; do
  case "$address" in
    10.*|192.168.*|172.1[6-9].*|172.2[0-9].*|172.3[01].*) ;;
    *) echo "ERROR: PostgreSQL resolved to non-private address $address." >&2; exit 3 ;;
  esac
done

export PGPASSWORD="$POSTGRES_ADMIN_PASSWORD"
export PGSSLMODE=verify-full
export PGSSLROOTCERT="$ROOT_CERT"

SERVICES=(auth gateway user parking media gamification notification moderation analytics aivalidation)
for service in "${SERVICES[@]}"; do
  database="parkio_${service}"
  runtime_role="parkio_${service}"
  migration_role="parkio_${service}_migrator"
  runtime_password="$(az keyvault secret show \
    --vault-name "$KEY_VAULT_NAME" \
    --name "postgres-${service}-runtime-password" \
    --query value --output tsv | tr -d '\r')"
  migration_password="$(az keyvault secret show \
    --vault-name "$KEY_VAULT_NAME" \
    --name "postgres-${service}-migration-password" \
    --query value --output tsv | tr -d '\r')"

  psql "host=$POSTGRES_HOST port=5432 dbname=postgres user=$POSTGRES_ADMIN_USER sslmode=verify-full sslrootcert=$ROOT_CERT" \
    --set=ON_ERROR_STOP=1 >/dev/null <<SQL
SELECT format('CREATE ROLE %I LOGIN', '$runtime_role')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '$runtime_role') \gexec
SELECT format('CREATE ROLE %I LOGIN', '$migration_role')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '$migration_role') \gexec
SELECT format('ALTER ROLE %I PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION', '$runtime_role', '$runtime_password') \gexec
SELECT format('ALTER ROLE %I PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION', '$migration_role', '$migration_password') \gexec
SELECT format('ALTER DATABASE %I OWNER TO %I', '$database', '$migration_role') \gexec
SELECT format('REVOKE ALL ON DATABASE %I FROM PUBLIC', '$database') \gexec
SELECT format('GRANT CONNECT ON DATABASE %I TO %I', '$database', '$runtime_role') \gexec
SELECT format('GRANT CONNECT, TEMPORARY ON DATABASE %I TO %I', '$database', '$migration_role') \gexec
SQL

  psql "host=$POSTGRES_HOST port=5432 dbname=$database user=$POSTGRES_ADMIN_USER sslmode=verify-full sslrootcert=$ROOT_CERT" \
    --set=ON_ERROR_STOP=1 >/dev/null <<SQL
SELECT format('ALTER SCHEMA public OWNER TO %I', '$migration_role') \gexec
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
SELECT format('GRANT USAGE ON SCHEMA public TO %I', '$runtime_role') \gexec
SELECT format('GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO %I', '$runtime_role') \gexec
SELECT format('GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA public TO %I', '$runtime_role') \gexec
SELECT format('ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO %I', '$migration_role', '$runtime_role') \gexec
SELECT format('ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA public GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO %I', '$migration_role', '$runtime_role') \gexec
SQL
done

# PostGIS is administrator-preprovisioned before Flyway. The spatial proof is
# transactional and rolls back its synthetic table/index.
psql "host=$POSTGRES_HOST port=5432 dbname=parkio_parking user=$POSTGRES_ADMIN_USER sslmode=verify-full sslrootcert=$ROOT_CERT" \
  --set=ON_ERROR_STOP=1 >/dev/null <<'SQL'
CREATE EXTENSION IF NOT EXISTS postgis;
GRANT SELECT ON TABLE spatial_ref_sys TO parkio_parking;
BEGIN;
CREATE TABLE prod_deploy_01a_spatial_probe (id bigint PRIMARY KEY, location geometry(Point, 4326) NOT NULL);
CREATE INDEX prod_deploy_01a_spatial_probe_gist ON prod_deploy_01a_spatial_probe USING gist (location);
INSERT INTO prod_deploy_01a_spatial_probe VALUES (1, ST_SetSRID(ST_MakePoint(27.1428, 38.4237), 4326));
DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM prod_deploy_01a_spatial_probe
    WHERE ST_DWithin(location::geography, ST_SetSRID(ST_MakePoint(27.1429, 38.4238), 4326)::geography, 100)
  ) THEN
    RAISE EXCEPTION 'ST_DWithin synthetic acceptance failed';
  END IF;
END $$;
ROLLBACK;
SQL

{
  echo "timestamp=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "postgresHost=$POSTGRES_HOST"
  echo "resolvedPrivateAddresses=${RESOLVED_ADDRESSES[*]}"
  echo "sslMode=verify-full"
  echo "rootCertificate=$ROOT_CERT"
  psql "host=$POSTGRES_HOST port=5432 dbname=postgres user=$POSTGRES_ADMIN_USER sslmode=verify-full sslrootcert=$ROOT_CERT" \
    --set=ON_ERROR_STOP=1 --tuples-only --no-align \
    --command="SELECT 'tls=' || ssl || ',version=' || version || ',cipher=' || cipher FROM pg_stat_ssl WHERE pid = pg_backend_pid();"
  psql "host=$POSTGRES_HOST port=5432 dbname=parkio_parking user=$POSTGRES_ADMIN_USER sslmode=verify-full sslrootcert=$ROOT_CERT" \
    --set=ON_ERROR_STOP=1 --tuples-only --no-align \
    --command="SELECT 'postgis=' || extversion FROM pg_extension WHERE extname = 'postgis';"
  psql "host=$POSTGRES_HOST port=5432 dbname=postgres user=$POSTGRES_ADMIN_USER sslmode=verify-full sslrootcert=$ROOT_CERT" \
    --set=ON_ERROR_STOP=1 --tuples-only --no-align \
    --command="SELECT 'runtimeRoles=' || count(*) FROM pg_roles WHERE rolname = ANY (ARRAY['parkio_auth','parkio_gateway','parkio_user','parkio_parking','parkio_media','parkio_gamification','parkio_notification','parkio_moderation','parkio_analytics','parkio_aivalidation']) AND NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole;"
  echo "spatialProbe=SRID4326_GiST_ST_DWithin_PASS"
} > "$EVIDENCE_FILE"
chmod 0640 "$EVIDENCE_FILE"

unset PGPASSWORD POSTGRES_ADMIN_PASSWORD runtime_password migration_password
echo "Invite-production database bootstrap and private TLS acceptance passed."
cat "$EVIDENCE_FILE"
