# DATA-WP-08 boundary asset contract (operator-managed geometry)

## Operator paths

| Environment | Path |
|-------------|------|
| Windows prep | `D:/parkio-ops/data-wp-08/boundary/` |
| Hosted-beta Linux | `/opt/parkio/ops/data-wp-08/boundary/` |

Do not treat Windows absolute paths as canonical runtime configuration.

## Required files

| File | Role | Git |
|------|------|-----|
| `izmir-ilceler-source.geojson` | Bit-identical official download | operator-managed |
| `izmir-admin-boundary.geojson` | Dissolved MultiPolygon (EPSG:4326) | operator-managed |
| `izmir-admin-boundary.poly` | osmium extract polygon | operator-managed |
| `MANIFEST.yml` | Verified source metadata | operator-managed (example in repo) |
| `CHECKSUMS.sha256` | Source + derived hashes | operator-managed (values in repo contract) |
| `validation-report.json` | Geometry/district validation evidence | operator-managed |

## Checksums (verified 2026-07-31)

```
6f4f43e4ce8139ddca4606582d903f047cb7c73810f8b876541a1ec3994ffd89  izmir-ilceler-source.geojson
ddd5664064a6bad22920d64a9f83c5c11c3ba85e4fb0e55a17bd3a26c31d2b61  izmir-admin-boundary.geojson
5b20558b28e93c1fb7f2bcda2e36142b186846e63a7151285dae76bb19f5d7b1  izmir-admin-boundary.poly
```

## Clip version

`izmir-admin-izbb-2024-10-18-v1`

Legacy rollback clip: `izmir-bbox-v1`

## License

CC BY 4.0 via İzmir Büyükşehir Belediyesi Açık Veri Lisansı.

Attribution: Contains public sector information licensed under Attribution 4.0 International (CC BY 4.0) by İzmir Metropolitan Municipality (İzmir Şehir Haritası / ilceler).

## Scripts

- `scripts/data-wp-08/build-izmir-admin-boundary.py`
- `scripts/data-wp-08/validate-boundary-asset.sh`
- `scripts/data-wp-08/extract-izmir-osm-polygon.sh`
