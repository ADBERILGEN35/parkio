# 00 — Baseline ve Mimari Harita

**Paket:** PARKIO-MUNICIPAL-MULTI-PROVIDER-PUBLIC-EXPLORE-01  
**UTC kanıt:** `20260917T132655Z`  
**Baseline SHA:** `06ee749323c2cdaf03255b2d7fb21bfb8157875e`  
**Dal:** `api`  
**PR #44:** OPEN / DRAFT / UNMERGED (dokunulmaz)  
**master:** değişmez

## Kimlik kapısı

| Kontrol | Sonuç |
|---------|--------|
| `git rev-parse HEAD` | `06ee749…` |
| Dal | `api` → `origin/api` |
| Production mutasyon | 0 (bu faz) |

## Mimari sahipler (kaynak doğrulandı)

| Rol | Sahiplik |
|-----|----------|
| MUNICIPAL PROVIDER SPI | `MunicipalParkingSourceAdapter` |
| PROVIDER ADAPTERS | IZUM, ISPARK, ANPARK, KONYA, KAYSERI, OSM Geofabrik, IZELMAN (gated), FakeTest |
| PROVIDER FAMILY / ENUM | `ParkingDataProviderId` + `MunicipalSourceIdentity` family sabitleri |
| SOURCE KEY OWNER | Adapter `SOURCE_KEY` + `ParkingProviderCatalog` |
| SYNC ORCHESTRATOR | `MunicipalParkingSyncService` / uygulamadaki sync orkestrasyon |
| MANUAL SYNC CONTROLLER | Mevcut municipal admin/manual sync uçları |
| SCHEDULER OWNER | Provider-bazlı `PARKIO_MUNICIPAL_*_SCHEDULER_ENABLED` |
| FACILITY ENTITY / REPO | `municipal_parking_facilities` + `MunicipalFacilityRepositoryAdapter` |
| OCCUPANCY | `municipal_occupancy_snapshots` + `latestForFacilityAndSourceKey` |
| PUBLIC EXPLORE CONTROLLER | `PublicExploreController` → `GET /api/v1/public/explore/facilities` |
| PUBLIC EXPLORE QUERY | `PublicExploreQueryService` (**IZUM-only, refaktör hedefi**) |
| PUBLIC EXPLORE PROPERTIES | `PublicExploreProperties` (**yalnız IZUM kabul, refaktör hedefi**) |
| ATTRIBUTION / LABEL | `ParkingProviderCatalog` (query şu an IZUM sabitleri hard-code) |
| FRESHNESS | `OccupancyFreshnessPolicy` + facility aging/stale saniye |
| COMMUNITY COUNT | `ParkingSpotRepository.countNearbyVisible` + eşik 3 |
| GATEWAY | Public explore anonim rota (mevcut PA-01/14 sözleşmesi) |
| WEB | `PublicExplorePage` / Vite `VITE_PUBLIC_EXPLORE_ENABLED` |
| MOBILE | mobile-v2 public explore istemcisi |

## Kritik mevcut sorunlar

1. `PublicExploreProperties` yalnızca `IZUM` kabul eder; `ISPARK` startup fail.
2. Repository SQL `source_key='izmir-izum-otoparklar'` hard-code.
3. Metod adları `publicExploreIzum*`.
4. Query occupancy/attribution yalnızca `MunicipalSourceIdentity.IZUM`.

## Şema notu

`source_key`, `primary_source_key`, facility↔source link, occupancy↔facility+source mevcut.  
**DB MIGRATION beklenen = 0.**

## Sonraki faz

Provider capability matrix + publication policy tasarımı + generic repository.
