# DATA-WP-10 — Municipal Field Provenance Selection on Ingest

> **Naming:** This package is **DATA-WP-10** (municipal/parking data). It is unrelated to
> repository WP-05 / WP-06 / WP-07. Document filenames follow `wp-data-10-*`.

## 1. Executive summary

DATA-WP-09 published bounded public provenance on nearby/detail DTOs, but hosted-beta
had **zero** `municipal_facility_field_provenance` rows because nothing wrote them.

**DATA-WP-10** wires allow-listed field provenance selection into successful **IZUM sync**
and **OSM import** using the existing V32 schema and `FieldProvenanceApplicationService`.

It does **not** enable public provenance publication, linking, IZELMAN publication, or
DATA-WP-08 boundary work. DATA-WP-10A (hosted-beta gate) is separate and not started.

## 2. Goals

1. On successful IZUM facility persist, select provenance for supplied allow-listed fields.
2. On successful OSM facility persist, select provenance for supplied allow-listed fields.
3. Keep writes idempotent under `(facility_id, field_name)`.
4. Never overwrite another source's provenance selection.
5. Never guess multi-source field ownership (no primary_source_key backfill).

## 3. Field allow-list

Same as public publication allow-list:

`NAME`, `COORDINATES`, `ADDRESS`, `OPERATOR`, `FACILITY_TYPE`, `STATIC_CAPACITY`, `ATTRIBUTION`

## 4. Source-key mapping

| Ingest path | `source_key` |
|-------------|--------------|
| IZUM sync | `izmir-izum-otoparklar` |
| OSM import | `osm-geofabrik-turkey` |

`source_record_id` = facility external id (IZUM `ufid` / OSM element id).

## 5. Supply rules

- Write only when the ingest path actually supplied the field value.
- OSM synthetic names (`OSM parking ...`) do **not** claim `NAME`.
- OSM never claims `ADDRESS`.
- Null/blank operator, address, or capacity are skipped.
- Live occupancy is **not** field provenance (separate snapshot stream).

## 6. Write / transaction behavior

- IZUM: per-facility `@Transactional` unit via `MunicipalFacilityIngestWriter`
  (facility + link + occupancy + provenance). Sync orchestration itself is not one big TX.
- OSM: per-facility `REQUIRES_NEW` unit via the same writer (facility + link + provenance),
  so a swallowed mid-import failure cannot commit a facility without its provenance.
- Soft-deactivation still runs only after a complete successful import (unchanged).
- Existing provenance with a **different** `source_key` -> skip (`skipped_other_source`).
- Same source + same `source_record_id` -> unchanged (no row growth).
- Failed upstream IZUM fetch does not add provenance; prior successful facilities keep theirs.

## 7. Backfill

**Omitted.** `primary_source_key` is not set by IZUM/OSM ingest, so a historical backfill
would guess field ownership. Live ingest writes are sufficient; operators can re-run sync/import.

## 8. Feature flags

| Flag | Default | Role |
|------|---------|------|
| `parkio.municipal.registry.provenance-ingest-write-enabled` | **true** | Kill-switch for ingest writes |
| `parkio.municipal.registry.provenance-publication-enabled` | **false** | Public DTO enrichment (unchanged) |

Env:

- `PARKIO_MUNICIPAL_REGISTRY_PROVENANCE_INGEST_WRITE_ENABLED` (default true)
- `PARKIO_MUNICIPAL_REGISTRY_PROVENANCE_PUBLICATION_ENABLED` (default false)

These flags are independent. Ingest write does not enable publication.

## 9. Metrics

`parkio.municipal.registry.provenance` labels: `field_name`, `outcome`

Outcomes: `selected`, `updated`, `unchanged`, `skipped_other_source`, `skipped_disabled`

## 10. Non-goals

- Enabling provenance publication or DATA-WP-10A
- Automatic / reviewed linking
- IZELMAN publication
- DATA-WP-08 workarounds
- Flyway migration
- Silent startup backfill

## 11. Rollback

Set `provenance-ingest-write-enabled=false`. Existing provenance rows remain; no schema rollback.
