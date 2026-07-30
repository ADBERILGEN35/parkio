# DATA-WP-01 — Municipal Parking Source Foundation (Izmir / IZUM)

Verification date: 2026-07-30

## Architecture

Municipal parking inventory and occupancy live **inside parking-service**.
No new microservice. City adapters implement a shared contract; canonical
domain models stay city-agnostic.

```
Official source (IZUM Open API)
        |
   RestClient + timeouts/retries
        |
   Source DTO (IzumParkingRecordDto)
        |
   Contract validation + per-record validation
        |
   Normalize -> NormalizedMunicipalFacility / Occupancy
        |
   Idempotent persistence
        |-- municipal_parking_facilities
        |-- municipal_facility_source_links
        |-- municipal_occupancy_snapshots
        |-- municipal_source_sync_runs
        |
   OccupancyFreshnessPolicy
        |
   MunicipalFacilityQueryService (hides stale free spaces)
        |
   GET /api/v1/parking/facilities/**
```

## Verified official sources

### IZUM live occupancy + locations

| Field | Value |
|-------|-------|
| Canonical URL | `https://openapi.izmir.bel.tr/api/ibb/izum/otoparklar` |
| Publisher | Izmir Buyuksehir Belediyesi / IZUM |
| Dataset | Otopark Doluluk ve Lokasyon Bilgileri |
| Portal | https://acikveri.bizizmir.com/dataset/otopark-doluluk-ve-lokasyon-bilgileri |
| Access | Anonymous Open API (GET, JSON) |
| HTTP (2026-07-30) | 200, `application/json; charset=utf-8` |
| License | Izmir Metropolitan Municipality Open Data License (CC BY 4.0) |
| Attribution | Includes public sector information from Izmir Buyuksehir Belediyesi Acik Veri Portali licensed under Attribution 4.0 International (CC BY 4.0). Parkio is not affiliated with or endorsed by Izmir Municipality or IZELMAN A.S. |
| External ID | `ufid` |
| Occupancy | `occupancy.total.free`, `occupancy.total.occupied` (+ optional `disabled`) |
| Observation timestamp | **Not provided** — Parkio uses fetch time (`FETCH` provenance) |
| Pagination | None observed (array payload) |
| source_key | `izmir-izum-otoparklar` |

### IZELMAN inventory / tariffs (discovery only in WP-01)

| Field | Value |
|-------|-------|
| Portal dataset | https://acikveri.bizizmir.com/dataset/izelman-otopark-lokasyon-kapasite-ve-calisma-saati-verisi |
| Publisher | IZELMAN A.S. Otoparklar Mudurlugu |
| Format | CSV resources (roadside / covered / open / subscriber) |
| Last updated (portal) | 2022-11-28 |
| Update frequency | irregular |
| Status in WP-01 | **Importer disabled** — discover and document only |
| Reason | Age + irregular refresh; must not be presented as guaranteed current tariffs without product/legal gate |

## Stale-data policy

Freshness states: `LIVE`, `AGING`, `STALE`, `UNAVAILABLE`, `INVALID`.

Defaults for IZUM (DB seed + overridable): aging 300s, stale 900s.

When freshness is not `LIVE` or `AGING`, public responses set `availableSpaces` to `null`
while retaining facility metadata (location, capacity, attribution).

## Idempotency

- Unique `(source_id, external_id)` on source links
- Facility upsert via source link lookup (canonical UUID separate from external id)
- Occupancy dedupe unique `(source_link_id, fetched_at, raw_record_hash)`
- At most one `RUNNING` sync run per source (partial unique index)

## Schema drift

`SchemaFingerprint` hashes sorted top-level JSON keys. Missing required keys
(`ufid`, `lat`, `lng`, `occupancy`) fail the whole run (`FAILED` / contract).
Malformed individual records are rejected; others may still persist (`PARTIAL_SUCCESS`).

## Configuration

```yaml
parkio.municipal.enabled: false                 # master gate
parkio.municipal.manual-sync-enabled: false
parkio.municipal.izum.enabled: false            # live HTTP off by default
parkio.municipal.izum.base-url: https://openapi.izmir.bel.tr
parkio.municipal.izum.path: /api/ibb/izum/otoparklar
parkio.municipal.izum.scheduler-enabled: false
parkio.municipal.izum.fixed-delay-ms: 120000
parkio.municipal.izum.connect-timeout / read-timeout / max-retries / user-agent
```

CI never calls the live municipal endpoint. Use fixtures + HttpServer for tests.
Live contract probes must be opt-in, timeout-bounded, and separate from CI.

## Public API (additive)

- `GET /api/v1/parking/facilities/nearby?lat=&lng=&radiusMeters=&limit=`
- `GET /api/v1/parking/facilities/{id}`
- Manual sync (when enabled): municipal manual sync controller under `/api/v1/parking/municipal/...`

Existing `/api/v1/parking/spots/**` contracts are unchanged.

## Adding the next municipality (checklist)

1. Verify official machine-readable source + license + attribution
2. Seed `municipal_data_sources` row with new `source_key`
3. Implement `MunicipalParkingSourceAdapter` in `infrastructure/<city>/`
4. Add DTO/validator/normalizer + fixture JSON
5. Wire properties under `parkio.municipal.<city>.*` (default disabled)
6. Add unit + HttpServer integration tests (no live CI dependency)
7. Document source verification date and limitations
8. Do **not** change canonical tables/enums for city-specific fields

## Known limitations

- IZUM provides no source observation timestamp (fetch-based freshness)
- IZELMAN CSV inventory/tariffs not imported in WP-01
- Single-instance overlap safety via DB unique RUNNING index (no ShedLock)
- `production_approved` seed is false until ops promotes
- No fuzzy facility conflation across sources

## Health details

Actuator component `municipalSources` always reports overall UP. Detail `izumStatus` is one of: `disabled`, `never_synced`, `healthy`, `aging`, `stale`, `failing`, `schema_mismatch`, `source_missing`, `probe_error`.
