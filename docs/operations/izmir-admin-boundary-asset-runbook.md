# Runbook — İzmir administrative boundary asset (DATA-WP-08)

## Purpose

Prepare and validate the operator-managed İzmir admin boundary used for OSM polygon
clipping. Does not deploy, does not reimport (see DATA-WP-08A).

## Official download

```text
https://acikveri.bizizmir.com/dataset/9292e9ab-3832-45a7-99e6-b1c5c6e35264/resource/c4b1da96-c547-4cca-a9a7-4053d0fee54f/download/ilceler.geojson
```

License: CC BY 4.0 (portal: İzmir Büyükşehir Belediyesi Açık Veri Lisansı).  
Attribution required.

Expected source SHA-256:

```text
6f4f43e4ce8139ddca4606582d903f047cb7c73810f8b876541a1ec3994ffd89
```

## Operator directories

| Environment | Path |
|-------------|------|
| Windows | `D:\parkio-ops\data-wp-08\boundary\` |
| Hosted-beta | `/opt/parkio/ops/data-wp-08/boundary/` |

## Prepare

1. Copy the downloaded file bit-identically to `izmir-ilceler-source.geojson`.
2. Verify SHA-256 matches the contract.
3. Build derived assets with GDAL image:

```bash
docker run --rm \
  -v "$BOUNDARY_DIR:/out" \
  -v "$REPO/scripts/data-wp-08:/scripts:ro" \
  -v "$SOURCE_DIR:/data:ro" \
  ghcr.io/osgeo/gdal:ubuntu-small-3.8.3 \
  python3 /scripts/build-izmir-admin-boundary.py /out /data/ilceler.geojson
```

4. Write/update `MANIFEST.yml` and `CHECKSUMS.sha256` from the contract example.
5. Validate:

```bash
./scripts/data-wp-08/validate-boundary-asset.sh "$BOUNDARY_DIR"
```

## Polygon OSM extract

```bash
export PARKIO_OSM_OPS_DIR=/opt/parkio/ops/data-wp-02b
export PARKIO_OSM_BOUNDARY_DIR=/opt/parkio/ops/data-wp-08/boundary
./scripts/data-wp-08/extract-izmir-osm-polygon.sh /path/to/turkey-YYYYMMDD.osm.pbf
```

Then convert osmium GeoJSON → Parkio `osm-parking-geojson-v1` interchange (existing DATA-WP-02 step),
dry-run import, then mutating import.

Failure behavior: temp outputs are discarded; previous extracts including
`izmir-bbox-v1.osm.pbf` remain.

## Rollback to bbox

1. `PARKIO_MUNICIPAL_OSM_CLIP_VERSION=izmir-bbox-v1`
2. Restore previous bbox-derived Parkio GeoJSON as `local-input-path`
3. Dry-run + import
4. Do not delete admin boundary assets

## Checklist

- [ ] Source SHA matches contract
- [ ] Derived GeoJSON / poly SHA match contract
- [ ] `MANIFEST.yml` license + attribution filled
- [ ] Validate script passes
- [ ] Extract uses `-p` polygon (not `-b` bbox)
- [ ] Dry-run import before mutating import
- [ ] Linking / İZELMAN publication remain disabled
- [ ] No PBF / full parking GeoJSON committed
