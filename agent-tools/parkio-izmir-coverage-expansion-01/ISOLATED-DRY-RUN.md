# Isolated IZELMAN official CSV dry-run (2026-09-22)

**Environment:** local Testcontainers PostGIS via `OfficialIzelmanCsvIsolatedDryRunIT`  
**Publication flags:** all false  
**Occupancy written:** no (dry-run)  
**Source age:** `HISTORICAL` (CONTENT_DATES Nov 2022)

## Dry-run counts (not unique facilities)

| Source key | raw | accepted | rejected | duplicates |
| --- | ---: | ---: | ---: | ---: |
| izelman-open-parking-facilities | 11 | 11 | 0 | 0 |
| izelman-closed-parking-facilities | 23 | 23 | 0 | 0 |
| izelman-barrier-parking-facilities | 17 | 17 | 0 | 0 |
| izelman-roadside-parking | 48 | 48 | 0 | 0 |
| **Sum of source rows** | **99** | **99** | 0 | 0 |

Do **not** treat 99 as unique city-wide facilities — pairs across open/closed/roadside/barrier can describe related inventory; cross-source dedupe is review-gated and not applied in this dry-run.

## Geographic check

All 99 points fall inside approx İzmir envelope (see `IZELMAN-BBOX-VALIDATION.md`).

## Historical recovery candidates (closed inventory)

Names present in `izelman-closed-parking-facilities.csv`:

- KONAK KATLI
- HATAY PAZAR YERI KATLI

These support recovery path B in `HATAY-KONAK-RECOVERY.md` (link/review — not invent coords).

## IZUM live probe (same window)

- URL: `https://openapi.izmir.bel.tr/api/ibb/izum/otoparklar`
- Count: **6**
- SHA-256: `b4bb23d2abe072a35f4c6fdbf002a1ce2e75c18084cbdad4b6434f0f419e9bf8`

## Next isolated steps (still non-prod)

1. ~~Non-dry import into the same Testcontainers DB (publication still false); assert idempotent second pass.~~ **Done** for closed inventory: first_inserted=23, second_unchanged_or_updated=23.
2. OSM clipped GeoJSON dry-run when operator asset available.
3. Coverage center probes after isolated publishable set is prepared — **not** against production.
