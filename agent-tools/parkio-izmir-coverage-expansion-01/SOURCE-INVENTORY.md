# İzmir parking data — source inventory (Parkio coverage expansion)

**Research date:** 2026-09-22 (local probe window ~00:00 UTC+3)  
**Repo:** `C:\Users\ADBERILGEN\Documents\parkio-w01a`  
**Scope:** Official and open datasets relevant to municipal parking inventory, occupancy, tariffs, and complementary OSM coverage for İzmir Metropolitan Municipality (İZBB) / İZELMAN-operated facilities.

**Important:** Portal UI sometimes concatenates format labels with **file sizes in bytes** (e.g. `CSV10329` = CSV + 10 329 bytes), **not** record counts. Row counts below were measured by downloading CSV resources on the research date unless noted.

---

## Executive summary

| Layer | Best current sources | Freshness | Parkio status |
| --- | --- | --- | --- |
| Live inventory + occupancy | IZUM Open API | Live (when API responds); **partial snapshots observed** | Adapter + sync implemented; default **dark** |
| Static İZELMAN inventory | Bizizmir CSV bundle (4 files) | **Stale (Nov 2022)** | CSV import implemented; publication **gated off** |
| Tariffs (no geometry) | Bizizmir `otopark-ucretleri` | Package metadata **Feb 2026**; CSV resource metadata **Sep 2024** | Import path exists; publication **gated off** |
| Broad “other parking” | OSM via Geofabrik Turkey PBF → İzmir clip | Weekly extract | Import + conflation implemented; anonymous publish **not reviewed** |

Only **three** datasets are tagged `otopark` on [acikveri.bizizmir.com](https://acikveri.bizizmir.com/en/dataset?tags=otopark). No separate ilçe (district) municipality parking inventories were found on Bizizmir during this scan.

---

## 1. İZELMAN location / capacity / hours (Bizizmir bundle)

### 1a. Dataset (parent)

| Field | Value |
| --- | --- |
| **URL** | https://acikveri.bizizmir.com/dataset/izelman-otopark-lokasyon-kapasite-ve-calisma-saati-verisi |
| **Publisher** | İzmir Büyükşehir Belediyesi (portal) / **İZELMAN A.Ş. Otoparklar Müdürlüğü** (author) |
| **License / usage** | **İzmir Büyükşehir Belediyesi Açık Veri Lisansı** (İzBB Open Data License; Parkio registry: `IZMIR-OPEN-DATA` / “İzmir Metropolitan Municipality Open Data License”) |
| **Update date (portal)** | **Son güncelleme: 28 Kasım 2022, 10:00 (+03)**; güncelleme sıklığı: **düzensiz** |
| **Geographic scope** | İZELMAN-operated facilities across İzmir metropolitan area (ilçe/mahalle columns in CSV) |
| **Record count** | **~99 facility rows total** across 4 CSVs (see sub-resources); **not** the byte sizes shown in portal badges |
| **Coordinates / geometry** | **Point**: `ENLEM`, `BOYLAM` (WGS84); no polygon/GeoJSON in portal |
| **Access / restriction fields** | **No dedicated access column**; barrier/subscriber semantics implied by **file split** (MK Sahil barrier file → Parkio maps to `RESTRICTED`) |
| **Parkio import support** | **Yes** — `IzelmanImportController`, `IzelmanCsvReader`, mappers; registry keys in `IzelmanSourceKeys` |
| **Publication status (coded)** | `production_approved=false` (Flyway V30); `parkio.municipal.izelman.*-publication-enabled` default **false**; family **not** in `PublicExplorePublicationPolicy.ReviewedPublicFamily` (IZELMAN is known-unreviewed); `content_freshness_class=HISTORICAL` |

**Stale metadata flag:** Treat all four inventory CSVs as **historical (Nov 2022)** for coverage expansion planning; do not assume current operational inventory without IZUM or operator confirmation.

---

### 1b. Yol kenarı (roadside)

| Field | Value |
| --- | --- |
| **Portal resource URL** | https://acikveri.bizizmir.com/dataset/izelman-otopark-lokasyon-kapasite-ve-calisma-saati-verisi/resource/a982c5d9-931d-4a75-a61d-23127d8ddad2 |
| **Download URL (CSV)** | https://acikveri.bizizmir.com/dataset/bbd99177-529e-401a-85b1-9d369dae0148/resource/a982c5d9-931d-4a75-a61d-23127d8ddad2/download/izelman-yol-kenari-otoparklar.csv |
| **Publisher** | İZELMAN A.Ş. via İzBB Açık Veri |
| **License** | İzBB Açık Veri Lisansı |
| **Update date (portal)** | Veri/üst veri: **25 Kasım 2022** (resource page) |
| **Geographic scope** | İzmir — roadside (`yol kenarı`) |
| **Record count** | **~48 data rows** (10 329 bytes; 49 lines incl. header on 2026-09-22) |
| **Geometry** | Point (`ENLEM`, `BOYLAM`) |
| **Access fields** | None explicit |
| **Parkio source key** | `izelman-roadside-parking` |
| **Import** | `POST /api/v1/parking/municipal/sources/izelman-roadside-parking/izelman-import` (requires `parkio.municipal.izelman.enabled` + admin role) |
| **Publication** | `roadside-publication-enabled` default **false**; separate from facility inventory gates |

**Columns:** `OTOPARK_ADI`, `ILCE`, `MAHALLE`, `ADRES_VEYA_TARIF`, `ACILIS_SAATI`, `KAPANIS_SAATI`, `KAPASITE`, `ENLEM`, `BOYLAM`

---

### 1c. Kapalı alan (covered / off-street structures)

| Field | Value |
| --- | --- |
| **Portal resource URL** | https://acikveri.bizizmir.com/tr/dataset/izelman-otopark-lokasyon-kapasite-ve-calisma-saati-verisi/resource/6ad4ad67-5923-49ec-8725-3f44f6f72aec |
| **Download URL (CSV)** | https://acikveri.bizizmir.com/dataset/bbd99177-529e-401a-85b1-9d369dae0148/resource/6ad4ad67-5923-49ec-8725-3f44f6f72aec/download/izelman-kapalialan-otoparklar.csv |
| **Update date (portal)** | **25 Kasım 2022** |
| **Record count** | **~23 data rows** (3 269 bytes) |
| **Geometry** | Point |
| **Access fields** | None explicit |
| **Parkio source key** | `izelman-closed-parking-facilities` |
| **Publication** | Gated (`facility-publication-enabled` default false) |

**Columns:** `OTOPARK_ADI`, `ILCE`, `MAHALLE`, `ADRES`, `EK_BILGI`, `ACILIS_SAATI`, `KAPANIS_SAATI`, `KAPASITE`, `ENLEM`, `BOYLAM`

---

### 1d. Yol kenarı dışı açık alan (open off-street)

| Field | Value |
| --- | --- |
| **Portal resource URL** | https://acikveri.bizizmir.com/tr/dataset/izelman-otopark-lokasyon-kapasite-ve-calisma-saati-verisi/resource/959c08c4-3e62-4e20-9e45-c334b0df31b1 |
| **Download URL (CSV)** | https://acikveri.bizizmir.com/dataset/bbd99177-529e-401a-85b1-9d369dae0148/resource/959c08c4-3e62-4e20-9e45-c334b0df31b1/download/izelman-yolkenari-disinda-acikalan-otoparklar.csv |
| **Update date (portal)** | **28 Kasım 2022** |
| **Record count** | **~11 data rows** (1 861 bytes) |
| **Geometry** | Point |
| **Parkio source key** | `izelman-open-parking-facilities` |

**Columns:** `OTOPARK_ADI`, `ILCE`, `MAHALLE`, `ADRES`, `YER_TARIFI`, `ACILIS_SAATI`, `KAPANIS_SAATI`, `KAPASITE`, `ENLEM`, `BOYLAM`

---

### 1e. Mustafa Kemal Sahil Bulvarı — bariyerli abone (barrier / subscriber)

| Field | Value |
| --- | --- |
| **Portal resource URL** | https://acikveri.bizizmir.com/dataset/izelman-otopark-lokasyon-kapasite-ve-calisma-saati-verisi/resource/22cf1829-b59b-4366-9169-8542f39de992 |
| **Download URL (CSV)** | https://acikveri.bizizmir.com/dataset/bbd99177-529e-401a-85b1-9d369dae0148/resource/22cf1829-b59b-4366-9169-8542f39de992/download/izelman-aboneli-otopark.csv |
| **Update date (portal)** | **28 Kasım 2022** |
| **Geographic scope** | **Mustafa Kemal Sahil Bulvarı** barrier subscriber blocks (not city-wide) |
| **Record count** | **~17 data rows** (2 509 bytes) |
| **Geometry** | Point |
| **Access fields** | Semantics via dataset type; Parkio sets **`RESTRICTED`** for `izelman-barrier-parking-facilities` |
| **Parkio source key** | `izelman-barrier-parking-facilities` |

**Columns:** `BLOK_ADI`, `ILCE`, `MAHALLE`, `ADRES_VEYA_TARIF`, `ACILIS_SAATI`, `KAPANIS_SAATI`, `KAPASITE`, `ENLEM`, `BOYLAM`

---

## 2. IZUM live Open API (occupancy + location)

| Field | Value |
| --- | --- |
| **Canonical URL** | https://openapi.izmir.bel.tr/api/ibb/izum/otoparklar |
| **Misleading / dead URL** | https://openapi.izmir.bel.tr/izum/otoparklar → **404** (not the documented path) |
| **Portal catalog URL** | https://acikveri.bizizmir.com/dataset/otopark-doluluk-ve-lokasyon-bilgileri |
| **Portal API resource** | https://acikveri.bizizmir.com/dataset/otopark-doluluk-ve-lokasyon-bilgileri/resource/273e5c89-9eca-4ba2-a267-c722e309b18d |
| **Publisher** | İzBB **İzmir Ulaşım Merkezi (İZUM)** Şube Müdürlüğü |
| **License / usage** | İzBB Açık Veri Lisansı (Parkio migration V28 also cites **CC BY 4.0**-style attribution text for registry) |
| **Update date (portal)** | Dataset package: **9 Şubat 2024**; API *resource* metadata still shows **30 Ağustos 2020** (**stale catalog metadata**) |
| **Geographic scope** | İzmir metropolitan parking connected to İZUM/NEDAP-style IDs (`ufid` prefix e.g. `NEDAP-TR-IZM-*`) |
| **Record count** | **Live-dependent.** Probe on **2026-09-22:** **6** JSON objects (5× `OnStreet`, 1× `OffStreet`). Ops notes in-repo: SUCCESS payloads have **oscillated ~5–8 rows** — **not a reliable full inventory snapshot**. |
| **Coordinates / geometry** | **Point**: `lat`, `lng` |
| **Access / restriction fields (payload)** | `accessories.covered`, `accessories.barrier`, `accessories.cctv`; `accessibility.*`; `isPaid`; `payment.*`; `type` (`OnStreet` / `OffStreet`); `status` (`Opened` / `Closed`) — **Parkio normalizer currently stores access as `PUBLIC` for all IZUM rows** (accessories not mapped to `MunicipalAccessClassification` yet) |
| **Other useful fields** | `ufid`, `name`, `provider`, `occupancy.total.free/occupied`, `openingHours`, `address`, `poi.*`, `entrances` / `exits` |
| **Parkio import support** | **Yes** — `IzumMunicipalParkingAdapter` (`izmir-izum-otoparklar`), `IzumParkingClient`, scheduled sync; capabilities: **FACILITY_INVENTORY + LIVE_OCCUPANCY**; reconciliation: **`AUTHORITATIVE_FULL_SET`** (risky if feed is partial) |
| **Publication status (coded)** | `production_approved=false`; `parkio.municipal.izum.enabled` default **false**; only **IZUM** is a `ReviewedPublicFamily` for anonymous Public Explore (still requires `PARKIO_PUBLIC_EXPLORE_ALLOWED_SOURCE_FAMILIES` config) |

**National mirror:** https://ulasav.csb.gov.tr/dataset/35-otopark-doluluk-ve-lokasyon-bilgileri (same API URL; different metadata dates — **Aug 2023** meta refresh).

**Operational note:** Timeouts and partial feeds have caused production incidents (see `agent-tools/.../HATAY-DISAPPEARANCE.md`). Coverage expansion should treat IZUM as **live occupancy + sample locations**, not complete static inventory, unless row counts stabilize at full-catalog levels.

---

## 3. Otopark ücretleri (tariffs — no coordinates)

| Field | Value |
| --- | --- |
| **URL** | https://acikveri.bizizmir.com/tr/dataset/otopark-ucretleri |
| **Publisher** | İZELMAN A.Ş. Bilgi İşlem ve Ar-Ge Müdürlüğü |
| **License** | İzBB Açık Veri Lisansı |
| **Update date (portal)** | Package: **16 Şubat 2026**; CSV resource “son güncellenen veri”: **2 Eylül 2024** (**metadata mismatch — flag**) |
| **Geographic scope** | İZELMAN-priced facilities (name-keyed rows, **no lat/lng**) |
| **Record count** | Not measured (CSV ~3 KB); tariff table by parking name |
| **Geometry** | **None** (`Coğrafi Veri İçeriyor mu?` = hayır) |
| **Access fields** | N/A (pricing includes abonelik columns) |
| **Download URLs** | CSV: https://acikveri.bizizmir.com/dataset/8863674c-c082-4f55-ab1d-dc75219aca4f/resource/8dca3fb5-b7fe-4f16-91af-d8248da59f87/download/otopark-ucretleri.csv — XLSX: https://acikveri.bizizmir.com/dataset/8863674c-c082-4f55-ab1d-dc75219aca4f/resource/b45d2e9f-f258-476e-a12d-d0ff62471ee0/download/izelman-otopark-ucretleri.xlsx |
| **Parkio import support** | **Yes** — `izelman-parking-tariffs`; `tariff-import-enabled` / `tariff-publication-enabled` default **false**; registry `content_freshness_class=AGING` |
| **Publication** | Tariff publication gated separately from facility inventory |

---

## 4. Geofabrik Turkey OSM (`amenity=parking`)

| Field | Value |
| --- | --- |
| **URL** | https://download.geofabrik.de/europe/turkey.html |
| **Direct PBF download** | https://download.geofabrik.de/europe/turkey-latest.osm.pbf (~617 MB; OSM data through **2026-09-20** per page on research date) |
| **Publisher** | **OpenStreetMap contributors** (extract by **Geofabrik GmbH**) |
| **License / usage** | **ODbL 1.0** — share-alike / attribution obligations; Parkio registry notes **legal review** before bulk third-party exposure |
| **Update date** | **Daily** regional rebuild (Geofabrik); not İzmir-specific |
| **Geographic scope** | **Whole Turkey**; Parkio clips to **İzmir** offline (`scripts/data-wp-08/extract-izmir-osm-polygon.sh`, `IzmirClip`, admin boundary runbook) |
| **Record count** | **Clip-dependent** (all `amenity=parking` nodes/ways/relations in İzmir polygon after extract); not fixed |
| **Coordinates / geometry** | **Point, polygon, multipolygon** from OSM; pipeline can emit **GeoJSON** (`izmir-parking-osmium.geojson` → Parkio `osm-parking-geojson-v1`) |
| **Access / restriction fields** | OSM tags: `access`, `fee`, `parking`, `capacity`, `capacity:disabled`, `opening_hours`, `maxstay`, `park_ride`, `supervised`, `covered`, etc. (`OsmTagAllowlist`, `OsmAccessMapper`) |
| **Parkio import support** | **Yes** — `OsmImportController` / `OsmImportApplicationService`; source key **`osm-geofabrik-turkey`** |
| **Publication status (coded)** | `production_approved=false`; OSM family **not** reviewed for anonymous Public Explore; `OPERATOR_IMPORTED` mode when enabled; label policy configurable (`parkio.municipal.osm.label-policy`) |

**GeoJSON:** No official Geofabrik “İzmir parking-only GeoJSON” URL — GeoJSON is an **operator-produced** artifact after PBF clip + osmium export (see `docs/operations/municipal-parking-source-runbook.md`).

**Sub-region:** Geofabrik does **not** publish a standalone İzmir `.osm.pbf`; clip from Turkey extract only.

---

## 5. Other İzBB / district open datasets (scan results)

| Source | URL | Finding |
| --- | --- | --- |
| Bizizmir tag search `otopark` | https://acikveri.bizizmir.com/en/dataset?tags=otopark | **3 datasets only:** İZELMAN inventory bundle, IZUM doluluk API, otopark ücretleri |
| Ulaşım Dairesi mobility group | https://acikveri.bizizmir.com/tr/dataset?groups=hareketlilik&organization=ulasim-dairesi-baskanligi | Lists **Otopark Doluluk ve Lokasyon** among mobility datasets; no additional static parking shapefiles found in search snippets |
| İlçe belediyeleri | Web search | **No dedicated ilçe open-data parking inventories** identified (Konak/Bornova/etc. appear only as columns inside İZBB/İZELMAN datasets) |
| ULASAV national catalog | https://ulasav.csb.gov.tr/dataset/35-otopark-doluluk-ve-lokasyon-bilgileri | Mirror/metadata for IZUM API only |

If district-specific expansion is required, expect **manual outreach** or **OSM/community** sources unless new datasets are published on Bizizmir.

---

## 6. Parkio repo cross-reference (import + publication)

| Source key / family | Import mechanism | Default enable flags | DB `production_approved` | Public explore |
| --- | --- | --- | --- | --- |
| `izmir-izum-otoparklar` | Scheduled municipal sync (`IzumMunicipalParkingAdapter`) | `PARKIO_MUNICIPAL_IZUM_ENABLED=false` | `false` (V28) | **Reviewed** family `IZUM` (config-gated) |
| `izelman-open-parking-facilities` | Admin CSV import | `PARKIO_MUNICIPAL_IZELMAN_*=false` | `false` (V30) | **Not reviewed** |
| `izelman-closed-parking-facilities` | Same | Same | `false` | **Not reviewed** |
| `izelman-barrier-parking-facilities` | Same (MK Sahil CSV) | Same | `false` | **Not reviewed** |
| `izelman-roadside-parking` | Same | Same | `false` | **Not reviewed** |
| `izelman-parking-tariffs` | Same | Same | `false` | **Not reviewed** |
| `osm-geofabrik-turkey` | `POST .../osm-geofabrik-turkey/import` | Municipal master flag | `false` (V29) | **Not reviewed** |

**Registry paths (representative):**

- `services/parking-service/src/main/java/com/parkio/parking/infrastructure/izum/IzumMunicipalParkingAdapter.java`
- `services/parking-service/src/main/java/com/parkio/parking/externalsource/izelman/IzelmanSourceKeys.java`
- `services/parking-service/src/main/resources/db/migration/V30__izelman_inventory_tariffs.sql`
- `services/parking-service/src/main/resources/db/migration/V28__municipal_parking_sources.sql` (IZUM)
- `services/parking-service/src/main/resources/db/migration/V29__osm_izmir_import_conflation.sql` (OSM)

---

## 7. Coverage expansion implications (data-only)

1. **Static inventory gap:** ~**99** Nov-2022 İZELMAN CSV rows vs **6** live IZUM rows (2026-09-22) implies large portions of real inventory are **not** in any single open feed today.
2. **Prefer conflation:** Parkio already supports **IZUM ↔ OSM** discovery pairs (`IZUM_OSM`); İZELMAN CSV imports are suited to **backfill / historical** linkage, not live occupancy.
3. **Do not trust IZUM as full-set** without cardinality guards (incomplete SUCCESS snapshots documented in-repo).
4. **Tariffs** can enrich UX but require **name matching** to facilities (no geometry).
5. **OSM** remains the practical path for **non-İZELMAN** and **district-adjacent** parking POIs, subject to ODbL review and İzmir clip QA.

---

## 8. Methods

- Portal pages fetched via web tools and CKAN `package_show` API (`acikveri.bizizmir.com/api/3/action/package_show`).
- CSV row counts: direct HTTPS download, line count minus header.
- IZUM: `GET https://openapi.izmir.bel.tr/api/ibb/izum/otoparklar` (HTTP 200, JSON array length measured).
- Repo: ripgrep for `izelman`, `izum`, `osm-geofabrik`, Flyway municipal migrations, `application.yml` defaults.

**No parking locations are listed in this document** (inventory counts and field names only).
