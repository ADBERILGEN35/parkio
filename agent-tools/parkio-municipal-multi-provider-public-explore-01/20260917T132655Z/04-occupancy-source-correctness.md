# 04 — Occupancy Kaynak Doğruluğu

## Bağlantı

`MunicipalOccupancySnapshotRepository.latestForFacilityAndSourceKey(facilityId, publishingSourceKey)`

`publishingSourceKey` = public query join’den gelen (allowed) source key; IZUM sabiti değil.

## İzolasyon

- IZUM facility → yalnızca IZUM snapshot
- ISPARK facility → yalnızca ISPARK snapshot
- Cross-link yok (unit test doğruladı)

## Freshness

Mevcut `OccupancyFreshnessPolicy` + facility aging/stale saniye korunur.

| Durum | availableSpaces |
|-------|-----------------|
| LIVE / AGING | expose |
| STALE / INVALID / UNAVAILABLE | null (mask) |

Inventory-only provider: snapshot yok → UNAVAILABLE; sıfır uydurma yok.

## N+1 notu

Mevcut desen korunur (facility satırı başına latest snapshot). Performans indeksi ayrı paket.
