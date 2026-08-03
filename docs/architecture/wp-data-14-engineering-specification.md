# DATA-WP-14 — Field Provenance Reconciliation and Stale Selection Cleanup

> **Naming:** This package is **DATA-WP-14** (municipal/parking data). Document
> filenames follow `wp-data-14-*`. Unrelated to repository WP-14 if any.

## 1. Status

**Implementation complete (this package).** DATA-WP-14A (hosted-beta reimport cleanup
gate) is **not started**.

## 2. Executive summary

DATA-WP-13 correctly stopped writing `NAME` provenance for OSM fallback labels, but
existing same-source `NAME` rows were left in place when a later successful ingest
no longer selected a name-bearing tag. Public publication then projected stale
`NAME` for facilities whose display label was already `Otopark` / type fallback.

**DATA-WP-14** adds deterministic, source-scoped provenance reconciliation inside
`FieldProvenanceApplicationService` on every successful municipal facility ingest:

1. Upsert/select fields present in the current accepted source record (unchanged).
2. Withdraw allow-listed fields previously selected by **the same source** that are
   absent / no longer eligible in the new record.
3. Never delete another source’s selection.
4. Keep facility update, source-link update, and provenance reconciliation in the
   same per-facility transaction (`MunicipalFacilityIngestWriter`).

No Flyway migration. V32 `UNIQUE (facility_id, field_name)` supports hard delete
and later re-insert/upsert.

## 3. Stale selection definition

A provenance row is **stale** when:

- it is owned by the ingesting `source_key`, and
- the current accepted ingest record does **not** include that field in its
  supplied allow-listed set (absent, invalid, rejected, or fallback-only for NAME).

## 4. Source-scoped withdrawal rule

Withdrawal runs only when **all** are true:

1. Same canonical facility and same `source_key` as the ingest.
2. Field is in the ingest allow-list (`NAME`, `COORDINATES`, `ADDRESS`, `OPERATOR`,
   `FACILITY_TYPE`, `STATIC_CAPACITY`, `ATTRIBUTION`).
3. Field is not in the current supplied set.
4. Existing row’s `source_key` equals the ingest source (blank/null →
   `skipped_ambiguous`, no delete).
5. The facility ingest unit commits successfully (reconcile is inside the writer TX).

## 5. OSM NAME transitions

| Transition | NAME provenance |
|------------|-----------------|
| real → real (same/different) | keep / update |
| real → operator/brand/type/neutral fallback | **withdraw** |
| fallback → real | create |
| fallback → fallback | absent |
| invalid/technical → fallback | absent (withdraw if stale) |

Display-label precedence remains DATA-WP-13 (`osm-label-v1`). Reconciliation does
not change label selection.

## 6. Other fields

- `OPERATOR` / `STATIC_CAPACITY` / `ADDRESS`: withdraw only when this source previously
  owned them and the current record no longer supplies them.
- `ATTRIBUTION` / `COORDINATES`: remain selected while OSM/İZUM ingest still supplies them.
- Foreign ownership: `skipped_other_source` (no delete).
- Ambiguous blank `source_key`: `skipped_ambiguous` (no delete).

## 7. Transaction / failure safety

- İZUM: `@Transactional` per facility in `MunicipalFacilityIngestWriter`.
- OSM: `REQUIRES_NEW` per facility in the same writer.
- Failure rolls back facility/link/value and any provenance withdrawal together.
- Failed upstream İZUM fetch / failed OSM parse before the facility loop creates no
  reconciliation mutations.
- Retry is idempotent: already-withdrawn rows produce no further deletes.

## 8. Cleanup / backfill

**Preferred:** complete OSM reimport under `osm-label-v1` (DATA-WP-14A). No startup
cleanup job. No generic mass-delete admin endpoint in this package.

## 9. Feature flags

Reuse `parkio.municipal.registry.provenance-ingest-write-enabled`
(`PARKIO_MUNICIPAL_REGISTRY_PROVENANCE_INGEST_WRITE_ENABLED`, default **true**).

When false: no selection **and** no withdrawal. No separate reconciliation flag.

Independent of provenance publication and linking flags.

## 10. Metrics

Counter `parkio.municipal.registry.provenance` with bounded labels only:

- `field_name`
- `outcome` — `selected` | `updated` | `unchanged` | `withdrawn_stale` |
  `skipped_other_source` | `skipped_ambiguous` | `skipped_disabled` | `failed`
- `source_family` — `osm` | `izum` | …
- `policy_version` — `ingest-provenance-v1`
- `operation` — `select` | `reconcile`

Never label with facility ID, external ID, field value, name/operator text,
coordinates, run ID, or exception text.

## 11. Public DTO

No shape change. After reconciliation, fallback-only OSM facilities must not project
`NAME` in `selectedFieldProvenanceSummary`. `ATTRIBUTION` and
`contributingSourceKeys` remain valid. `registryConfidenceOrReviewStatus` remains null.

## 12. DATA-WP-14A hosted-beta procedure (outline)

1. Deploy this commit (separate gate).
2. Dry-run then mutating reimport of the approved polygon GeoJSON.
3. Confirm the three stale rows (`way/380281246`, `way/438298564`, `way/438298565`)
   lose OSM `NAME` provenance while display stays fallback.
4. Second import idempotent; OSM availability null; linking/İZELMAN unchanged.
5. Leave `osm-label-v1` and provenance flags as today.

## 13. Rollback

- Code: redeploy previous parking-service image.
- Data: optional reimport does not recreate withdrawn stale NAME unless name-bearing
  tags return. No DB deletion scripts required for rollback of this package.

## 14. Out of scope

DATA-WP-14A deploy/gate, linking, İZELMAN publication, label precedence changes,
DTO shape changes, Flyway migrations, production deploy.
