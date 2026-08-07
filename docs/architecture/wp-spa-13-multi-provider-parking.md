# WP-SPA-13 — Multi-Provider Parking Architecture

Provider-ready ingestion architecture. Does **not** onboard a new municipality, call
new external APIs, add payments/reservations/pricing, change ranking weights, or
redesign municipal UI.

## Concepts

| Concept | Meaning | Example |
|---------|---------|---------|
| **Provider** | Organisation/system supplying data | İZUM, OpenStreetMap |
| **Source / feed** | Concrete dataset/endpoint | `izmir-izum-otoparklar` |
| **Facility** | Canonical Parkio parking location | Hatay Katlı Pazaryeri |
| **Source link** | `(source, externalId) → facility` | İZUM ufid → facility UUID |
| **Occupancy** | Optional dynamic state | LIVE / AGING / STALE / UNAVAILABLE |

Do not conflate provider identity with raw ETL keys or UI labels.

## Target flow

```
External provider
→ Provider Adapter (MunicipalParkingSourceAdapter)
→ Validation / Normalization
→ Canonical Facility + Source Link + Occupancy Snapshot
→ Existing Parking APIs
→ Existing Recommendations / Ranking / Web+Mobile UX
```

Provider-specific branches must **not** appear in `RecommendationApplicationService`
or ranking scorers.

## Capabilities

Declared on each source via `ParkingProviderCatalog` / adapter:

- `FACILITY_INVENTORY`
- `LIVE_OCCUPANCY`

Current:

| Source | Provider | Capabilities | Reconciliation |
|--------|----------|--------------|----------------|
| `izmir-izum-otoparklar` | IZUM | inventory + live occupancy | `AUTHORITATIVE_FULL_SET` |
| `osm-geofabrik-turkey` | OPENSTREETMAP | inventory only | `UPSERT_ONLY` (file import path) |
| `izelman-*` | IZELMAN | inventory only | `UPSERT_ONLY` (file import path) |
| `parkio-fake-test-provider` | FAKE_TEST | inventory + live occupancy | `AUTHORITATIVE_FULL_SET` (tests only) |

No PAYMENTS / RESERVATIONS / EV / DYNAMIC_PRICING capabilities in this package.

## Adapter boundary

`MunicipalParkingSourceAdapter` produces:

- normalized facilities
- normalized occupancy (optional)
- external identity
- source observation metadata (via occupancy timestamps)

Adapters must **not**: persist, manipulate other sources, call recommendation/ranking,
or format user-facing copy.

OSM and İZELMAN remain on dedicated file-import orchestration. Forcing them into the
JSON `fetch()` adapter would obscure their real transport model.

## Identity

External identity remains namespaced:

`(provider/source, externalId) → canonical facility`

- No global externalId uniqueness
- No fuzzy cross-provider merge
- Reappearance reactivates the same source link
- Same externalId under two sources is safe

## Cross-provider fusion

**NOT IMPLEMENTED.**

Two providers may describe the same physical place. WP-SPA-13 does not merge by
name, coordinates, or address. Future fusion is an explicit later boundary.

## Reconciliation modes

`AUTHORITATIVE_FULL_SET`:

- only after fully successful, non-empty, duplicate-free accepted set
- soft-deactivate missing links for **that source only**
- never on fetch/parse failure, partial success, or empty set

`UPSERT_ONLY`:

- no mass deactivation from the live-adapter sync path

## Sync orchestration (live adapters)

1. fetch
2. validate contract
3. normalize facilities + occupancy
4. persist/upsert (`persistLiveAdapterFacility`)
5. occupancy snapshots
6. authoritative reconciliation (policy-gated)
7. sync run result + metrics

## Sync result

`MunicipalSyncResult`: status, received/accepted/rejected, inserted/updated/unchanged,
occupancyInserted, deactivated, reactivated, activeLinkCount, error category/summary.

No facility IDs in summary metrics labels.

## Failure semantics

| Outcome | Destructive reconcile? |
|---------|------------------------|
| FETCH_FAILED / FAILED | No |
| VALIDATION / parse-wide failure | No |
| PARTIAL_SUCCESS | No |
| SUCCESS + authoritative + non-empty | Yes (source-scoped) |
| Suspicious large shrink | Warn only |

## Occupancy

Canonical freshness: LIVE / AGING / STALE / UNAVAILABLE (and INVALID where classified).

Capability `LIVE_OCCUPANCY` gates contribution. Supporting occupancy does **not** imply
every facility currently has a live observation.

Public product copy for İZUM/OSM is unchanged.

## Public API

Unchanged:

- `GET /api/v1/parking/facilities/nearby`
- `GET /api/v1/parking/facilities/{id}`
- `POST /api/v1/parking/recommendations`

No provider-specific public endpoints. `availabilitySource` derives from live-occupancy
authority among publishable linked keys (İZUM for current production data).

## Recommendations / ranking / favourites / parked-car

Remain provider-neutral on canonical `MUNICIPAL_FACILITY` + facility UUID.

No provider preference boost. Ranking still uses distance / freshness / capacity /
confidence / favourite. Favourites and RecentParking continue to reference canonical
facility IDs.

## Source presentation

Public labels:

- İZUM → İzmir Büyükşehir Belediyesi / İZUM (frontend) / `Izmir Buyuksehir Belediyesi / IZUM` (API ASCII)
- OSM → OpenStreetMap

Never expose raw keys `izmir-izum-otoparklar` / `osm-geofabrik-turkey` in UI.

Catalog holds display metadata; clients continue using existing presentation helpers.

## Configuration

```yaml
parkio.municipal.fake-test:
  enabled: false          # never true in production
  publication-enabled: false
```

Existing İZUM/OSM/İZELMAN flags unchanged. No new `spa.analytics` or payment flags.

## Observability

Reuse `parkio.municipal.sync.*` counters tagged by configured `source_key`
(bounded cardinality). Do not label by external facility IDs.
WP-SPA-12 product analytics unchanged.

## Fake provider acceptance

`FakeTestMunicipalParkingAdapter` (`parkio-fake-test-provider`) proves:

1. registration
2. normalize + sync
3. occupancy insert
4. namespaced source links
5. authoritative soft-deactivate + reactivate
6. isolation from İZUM on failure
7. recommendation mapper consumes `MUNICIPAL_FACILITY` without provider branches

Never appears in production UI.

## How to add Provider X

1. Define provider/source config (enabled defaults off)
2. Implement `MunicipalParkingSourceAdapter` (fetch/validate/normalize only)
3. Add catalog descriptor (capabilities + reconciliation + display metadata)
4. Add parser/validation tests + fixtures
5. Wire Spring bean behind provider/source flag
6. Seed `municipal_data_sources` row (Flyway or ops)
7. Run fake-provider-style contract + isolation tests for the new source
8. Verify facility API / recommendation / ranking golden tests unchanged
9. Roll out behind provider flag; keep client labels via catalog/presentation helpers
10. Do **not** add provider branches to recommendation or ranking

## Non-goals

- Real new municipality onboarding
- External new provider API calls
- Payments, reservations, required pricing
- Cross-provider facility fusion
- Ranking weight changes
- UI redesign
- Replacing canonical municipal tables for naming aesthetics
- WP-SPA-14
