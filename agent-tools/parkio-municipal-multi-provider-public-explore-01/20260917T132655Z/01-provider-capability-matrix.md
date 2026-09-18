# 01 — Provider Capability Matrix

**SHA baseline:** `06ee749…` (kaynak doğrulama)  
**Kanıt:** `20260917T132655Z`

| FAMILY | SOURCE_KEY | FACILITY_INVENTORY | LIVE_OCCUPANCY | STATIC_ONLY | MANUAL_SYNC | SCHEDULER | PUBLICATION_REVIEW | LEGAL/POLICY | SAFE ANON PUBLIC |
|--------|------------|--------------------|----------------|-------------|-------------|-----------|--------------------|--------------|------------------|
| IZUM | `izmir-izum-otoparklar` | YES | YES | NO | YES | `parkio.municipal.izum.scheduler-enabled` | REVIEWED | CLEAR (mevcut prod) | YES (allowlist) |
| ISPARK | `istanbul-ispark-parks` | YES | YES | NO | YES | `parkio.municipal.ispark.scheduler-enabled` | REVIEWED | CLEAR (açık veri) | YES (allowlist; ingest≠publish) |
| ANPARK | `ankara-anpark-parks` | YES | NO | YES | YES | anpark scheduler | BLOCKED / unreviewed | BLOCKED (catalog: LEGAL REVIEW) | NO |
| KONYA | `konya-bb-otopark-bilgileri` | YES | NO | YES | YES | konya scheduler | UNKNOWN | UNKNOWN | NOT YET |
| KAYSERI | `kayseri-bb-otoparklar` | YES | NO | YES | YES | kayseri scheduler | UNKNOWN | UNKNOWN | NOT YET |
| IZELMAN | çoklu `izelman-*` | YES | NO | YES | gated | izelman props | BLOCKED | BLOCKED / unpublished | NO |
| OSM | `osm-geofabrik-turkey` | YES | NO | YES | import path | N/A (import) | NOT municipal public family | OSM lisans ayrı | NO (otomatik publish yok) |

**ATTRIBUTION:** `ParkingProviderCatalog` source-owned (`displayName` / `attribution`).

**Ingest ≠ publication:** `PARKIO_MUNICIPAL_*_ENABLED` yayınlamaz; yalnızca `PARKIO_PUBLIC_EXPLORE_ALLOWED_SOURCE_FAMILIES` + reviewed policy.
