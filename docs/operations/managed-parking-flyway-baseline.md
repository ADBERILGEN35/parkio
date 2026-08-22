# Managed parking Flyway baseline (PROD-DEPLOY-01A-R8.5)

How `parkio_parking` migrates on managed PostgreSQL, why `baselineOnMigrate` could not do it, and
the one-time preparation live invite-production still needs.

## The constraint

Azure Database for PostgreSQL Flexible Server rejects `CREATE EXTENSION` for any role outside
`azure_pg_admin`. The guard is a utility hook that fires on the statement, *before* PostgreSQL's own
`IF NOT EXISTS` short-circuit — so `V1__enable_postgis.sql` is refused even on a database where
PostGIS is already installed:

```
ERROR: Because postgis isn't a trusted extension, only members of "azure_pg_admin"
       are allowed to use CREATE EXTENSION postgis
```

PostGIS is provisioned by `scripts/azure/bootstrap-invite-production-databases.sh` as the
administrator. The application chain therefore has to begin at V2, behind a Flyway BASELINE marker.

`V1__enable_postgis.sql` is **frozen**, not rewritten. Hosted-beta and local already applied it and
recorded its checksum; changing the file fails their `validate()` on the next boot.
`ParkingMigrationV1ImmutabilityTest` pins the bytes (`sha256 962ca38a…`) and the Flyway checksum
(`-653528947`), and `ManagedParkingFlywayBaselineIT` reads that same checksum back out of a real
history table.

## Why `baselineOnMigrate` was the wrong mechanism

R8 set `SPRING_FLYWAY_BASELINE_ON_MIGRATE=true`. R8.5 exercised it against real PostgreSQL + PostGIS
with a genuinely unprivileged migration role and an emulation of Azure's extension gate
(`ManagedParkingFlywayBaselineIT`). Measured results:

| State | Schema | History table | baselineOnMigrate engages? | Outcome |
|---|---|---|---|---|
| A | empty (PostGIS elsewhere) | absent | **no** | V1 attempted → rejected |
| B | PostGIS in `public` | present, 0 rows | **no** | V1 attempted → rejected |
| C | PostGIS in `public` | absent | **no** | V1 attempted → rejected, and the attempt *creates* state B |
| D | PostGIS in `public` | either | n/a — explicit `baseline()` | BASELINE at 1, then V2…V40 |
| E | owner environment | absent | n/a | V1 executes normally, checksum recorded |

Two findings decide it:

- Flyway baselines on migrate only for a **non-empty** schema with no history table, and its
  emptiness check **excludes extension-owned objects**. A managed `public` holding nothing but
  PostGIS reads as empty, so the flag never engages.
- Each failed attempt leaves the history table behind with its failed row rolled back (PostgreSQL
  DDL is transactional). From then on the table's mere existence suppresses baselining permanently.
  That is how live `parkio_parking` reached an empty history table it cannot migrate out of.

## The mechanism now in place

`ManagedFlywayBaselineStrategy` — a Spring `FlywayMigrationStrategy` armed only by
`PARKIO_PARKING_FLYWAY_MANAGED_BASELINE_ENABLED=true`, which only
`docker/docker-compose.managed-db.yml` sets. It inspects the schema and takes one of six branches:

| Observed state | Action |
|---|---|
| no history table, no application tables, PostGIS present | `baseline()` at 1, then `migrate()` |
| history table with rows, none failed | `migrate()` only |
| history table present but **empty** | refuse — names the preparation script |
| application tables but no history table | refuse — lineage would be discarded |
| PostGIS absent | refuse — V2's geography column has nothing to bind to |
| a failed migration recorded | refuse — operator repair |

Nothing is ever written into `flyway_schema_history` by hand. Local, CI and hosted-beta keep the
stock strategy, so V1 still executes there and its checksum stays valid.

## One-time live preparation

Live `parkio_parking` is in state B, and Flyway itself refuses to baseline over it:

```
Unable to baseline schema history table "public"."flyway_schema_history" as it already
exists, and is empty. Delete the schema history table, and run baseline again.
```

Dropping the empty table is therefore required, and it is Flyway's own documented remedy. It is an
operator mutation, never an application startup side effect.

`scripts/azure/prepare-managed-parking-flyway-baseline.sh` is read-only by default. It connects as
the **migration role** — which owns the table Flyway created, so no elevated identity is needed —
and refuses to proceed unless all of the following hold:

- `PARKIO_DEPLOYMENT_PROFILE=invite-production` (hosted-beta and azure-hosted-beta are rejected)
- the server FQDN comes from the invite-production foundation deployment and ends in
  `.postgres.database.azure.com`, and resolves only to RFC1918 addresses
- `current_database()` is exactly `parkio_parking`
- PostGIS is installed
- `flyway_schema_history` exists with **exactly zero** rows and no failed rows
- zero application tables and zero schema objects beyond the accepted PostGIS/Flyway set

Any deviation exits non-zero without mutating anything.

### Procedure

```bash
# 1. Read-only. Expect verdict=READY.
PARKIO_DEPLOYMENT_PROFILE=invite-production \
  scripts/azure/prepare-managed-parking-flyway-baseline.sh

# 2. The single mutation: DROP TABLE public.flyway_schema_history, nothing else.
PARKIO_DEPLOYMENT_PROFILE=invite-production \
PARKIO_BASELINE_PREPARE_CONFIRM=DROP-EMPTY-FLYWAY-HISTORY/parkio_parking \
  scripts/azure/prepare-managed-parking-flyway-baseline.sh --apply

# 3. Deploy. parking-service baselines at version 1 and applies V2..V40.

# 4. Read-only again. Expect verdict=CONVERGED, historyFirstRowType=BASELINE,
#    historyHeadVersion=40.
PARKIO_DEPLOYMENT_PROFILE=invite-production \
  scripts/azure/prepare-managed-parking-flyway-baseline.sh
```

The drop re-checks emptiness inside its own `ACCESS EXCLUSIVE`-locked transaction, so a row written
between inspection and mutation aborts it rather than being lost.

Expected converged history:

```
installed_rank | version |   type   | success
             1 |       1 | BASELINE | t        << PostGIS preprovisioned by infrastructure bootstrap
             2 |       2 | SQL      | t
           ... |     ... | SQL      | t
            40 |      40 | SQL      | t
```

## Privilege model

| Phase | Identity | Why |
|---|---|---|
| PostGIS provisioning | `parkioops` (administrator) | only `azure_pg_admin` may `CREATE EXTENSION` |
| history-table drop | `parkio_parking_migrator` | it owns the table Flyway created |
| Flyway baseline + V2…V40 | `parkio_parking_migrator` | owns database and `public` |
| application runtime | `parkio_parking` | DML only; never granted `azure_pg_admin` |

No privilege is granted by any part of this mechanism.

## Tests

- `ManagedParkingFlywayBaselineIT` — real PostgreSQL + PostGIS, unprivileged role, Azure gate
  emulated; states A–E, explicit baseline, idempotency, fail-closed cases, strategy end-to-end.
- `ManagedFlywayBaselineDecisionTest` — the decision table, Docker-free.
- `ManagedFlywayBaselineConfigurationTest` — the strategy is off unless the managed profile arms it.
- `ParkingMigrationV1ImmutabilityTest` — V1 bytes and checksum are frozen.
- `scripts/test-managed-parking-flyway-baseline.sh` — the preparation tool's fail-closed contract,
  with `az`/`psql`/`getent` faked.
