# IZELMAN official CSV — bbox validation (2026-09-22)

Approximate İzmir envelope: lat [37.8, 39.0], lng [26.2, 28.5]

| Parkio source key | Portal file | Rows | In bbox | Out |
| --- | --- | --- | --- | --- |
| izelman-roadside-parking | izelman-yol-kenari-otoparklar.csv | 48 | 48 | 0 |
| izelman-closed-parking-facilities | izelman-kapalialan-otoparklar.csv | 23 | 23 | 0 |
| izelman-open-parking-facilities | izelman-yolkenari-disinda-acikalan-otoparklar.csv | 11 | 11 | 0 |
| izelman-barrier-parking-facilities | izelman-aboneli-otopark.csv | 17 | 17 | 0 |

**Historical recovery names present in closed CSV:** KONAK KATLI, HATAY PAZAR YERI KATLI (encoding-safe match).

**Not unique total:** 48+23+11+17 = 99 raw rows across sources — do not sum as unique facilities.

Checksums: sources/izelman/SHAS.txt
Named copies for importer: sources/izelman/parkio-named/*.csv
