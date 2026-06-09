# 05 — Database Guidelines

## Ownership

- **Database-per-service.** Each service has its own PostgreSQL database/schema.
- **No cross-service DB access.** Never query or join another service's tables.
  No cross-service foreign keys. Reference foreign data by ID; sync via events.
- Only `infrastructure` touches the database. `domain` and `application` stay
  persistence-agnostic (work through repository ports defined inward).

## PostgreSQL / PostGIS

- Use **PostGIS** for spatial data in `parking-service`:
  - Store location as `geography(Point, 4326)`.
  - Index with **GiST** for radius/nearest queries.
  - Do distance filtering in the DB (`ST_DWithin`), not in app memory.
- Use appropriate types: `uuid` PKs (recommended), `timestamptz` for time,
  `numeric` for money/points where exactness matters, enums as `varchar` + check
  or native enum (prefer varchar + app-side enum for easy evolution).

## Migrations

- All schema changes via versioned migrations (**Flyway** recommended), checked in
  under the owning service. Never auto-generate/alter schema at runtime in prod
  (`spring.jpa.hibernate.ddl-auto=validate`, not `update`).
- Migrations are forward-only and reviewed; one logical change per migration.

## Persistence patterns

- JPA/Hibernate entities live in `infrastructure` and are **mapped to/from** domain
  objects — they are not the domain model.
- Use **optimistic locking** (`@Version`) for spot status transitions and other
  concurrent updates.
- Each service that publishes events has an **outbox table** in the same DB;
  consumers maintain an **inbox/processed-messages** table (see `06`).
- Service-local retention jobs delete only published outbox rows after 7 days
  and processed inbox rows after 30 days by default. Cleanup is bounded and
  configurable; unpublished outbox rows are never retention-deleted.

## Caching

- **Redis** for: hot read caches, distributed locks (e.g. claim contention),
  idempotency-key storage, rate limiting, short-lived geo result caches.
- Cache is never the source of truth; design for cache loss.

## Data hygiene

- Soft-delete or status fields where history matters (spots use status, not hard
  delete).
- Store only IDs/URLs for media; bytes live in MinIO/S3 (`media-service`).
- PII minimization: keep personal data in `user`/`auth` only; other services keep
  IDs.
