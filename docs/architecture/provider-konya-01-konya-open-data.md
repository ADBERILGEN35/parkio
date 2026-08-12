# PROVIDER-KONYA-01 — Konya Büyükşehir Belediyesi Open Data

## Official dataset

| Field | Value |
|-------|-------|
| Dataset | [Otopark Bilgileri](https://acikveri.konya.bel.tr/tr/dataset/otopark-bilgileri) |
| Resource ID | `707d3cb9-1264-49dc-98a0-b9863766ab39` |
| Access | CKAN `datastore_search` JSON API |
| License | Creative Commons Attribution 3.0 (`cc-by`) |
| Last modified (upstream) | 2022-06-28 |

## Provider identity

| Concept | Value |
|---------|-------|
| `ParkingDataProviderId` | `KONYA` |
| Source key | `konya-bb-otopark-bilgileri` |
| Display label | Konya Büyükşehir Belediyesi |
| Capability | `FACILITY_INVENTORY` only |
| Reconciliation | `UPSERT_ONLY` |

## Source grain

Each CKAN row represents a **bay/peron** within a parking **zone** (`bolgeadi`).

| Field | Semantics |
|-------|-----------|
| `bolgeadi` | Zone name (aggregation key) |
| `bolgeadresi` | Zone address |
| `bolgekapasite` | Zone total capacity (repeated per bay row) |
| `peronkapasite` | Bay capacity (sums to zone total) |
| `peronkoordinat` | Bay geometry (point/line/polygon JSON string) |
| `peronacilissaati` / `peronkapanissaati` | Opening hours as HHMM integers |
| `_id` | Datastore row identity only — **not** canonical facility ID |

## Aggregation strategy

1. Group rows by normalized `bolgeadi` (trim, collapse whitespace, NFKC + uppercase for identity).
2. Collect valid coordinates from all bay rows in the zone.
3. Compute deterministic centroid (mean of valid points).
4. Use `bolgekapasite` as zone capacity — **do not sum** `peronkapasite` (would double-count when zone total is repeated).
5. Zones with zero valid coordinates are **not published** (unmappable).

## External identity

Deterministic SHA-256 prefix:

```
externalId = "konya-zone-" + sha256("konya-bolge:" + normalizedZoneName)[0:16]
```

Survives row reorder, harmless whitespace changes, and datastore reload.

## Coordinate parsing & geo gate

`peronkoordinat` is parsed as nested JSON arrays with `[lng, lat]` pairs.

Konya bounding envelope (conservative):

- Latitude: 37.4 – 38.6
- Longitude: 31.8 – 34.2

Approximately 20% of upstream rows contain copy-pasted coordinates around latitude ~39.88; these are excluded from centroid input. Discovery live probe (2026-08-12): 27 suspicious / 108 valid lat ~37.x rows.

## Occupancy

No live occupancy. `normalizeOccupancy()` returns empty. Capacity is inventory metadata only.

## Configuration

| Property | Default |
|----------|---------|
| `PARKIO_MUNICIPAL_KONYA_ENABLED` | `false` |
| `PARKIO_MUNICIPAL_KONYA_SCHEDULER_ENABLED` | `false` |
| `PARKIO_MUNICIPAL_KONYA_FIXED_DELAY_MS` | `86400000` (24h) |

Slow cadence because dataset is static/stale (last modified 2022).

## Legal / attribution

Catalog records `CC-BY-3.0` with attribution to Konya Büyükşehir Belediyesi Açık Veri.
`production_approved=false` in migration (consistent with İZUM/İSPARK catalog convention).
Catalog `productionEligible=true` (Level-B CC BY evidence sufficient for technical readiness).

## Rollback

1. Set `PARKIO_MUNICIPAL_KONYA_SCHEDULER_ENABLED=false`
2. Set `PARKIO_MUNICIPAL_KONYA_ENABLED=false`
3. No hard delete of ingested facilities (UPSERT_ONLY rows remain until future governance)

## Quality risks

- Stale source (2022); UPSERT_ONLY does not deactivate missing rows
- Incomplete zone coverage possible
- Copy-pasted coordinates require ongoing geo gate
- No publisher stable facility UUID
- **Hosted-beta egress (2026-08-12):** Azure VM (`francecentral`) receives Cloudflare
  challenge HTML (`403 Just a moment...`) for `acikveri.konya.bel.tr` datastore API.
  Browser/User-Agent changes did not bypass. Local/non-datacenter clients still receive
  HTTP 200. Live hosted-beta sync therefore fails with `errorCategory=authentication`
  until publisher allowlisting or an approved egress path exists. Implementation remains
  default-OFF and ready once access is restored.
