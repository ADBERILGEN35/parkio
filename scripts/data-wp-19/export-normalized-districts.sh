#!/usr/bin/env bash
# DATA-WP-19 — export normalized İzmir district FeatureCollection via PostGIS MakeValid.
# Does not modify the official source asset. Operator-managed output only (not committed).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SRC="${PARKIO_DISTRICT_SOURCE:-/opt/parkio/ops/data-wp-08/boundary/izmir-ilceler-source.geojson}"
OUT_DIR="${PARKIO_DISTRICT_TOPOLOGY_DIR:-/opt/parkio/ops/data-wp-19/district-topology}"
EXPECTED_SRC_SHA="${PARKIO_DISTRICT_SOURCE_SHA256:-6f4f43e4ce8139ddca4606582d903f047cb7c73810f8b876541a1ec3994ffd89}"
PSQL=(docker exec -i parkio-postgres-parking psql -U parkio_parking -d parkio_parking -v ON_ERROR_STOP=1)

mkdir -p "$OUT_DIR"
test -f "$SRC"
SRC_SHA="$(sha256sum "$SRC" | awk '{print $1}')"
if [[ "$SRC_SHA" != "$EXPECTED_SRC_SHA" ]]; then
  echo "source checksum mismatch: $SRC_SHA" >&2
  exit 2
fi
cp -f "$SRC" "$OUT_DIR/izmir-ilceler-source.geojson"

"${PSQL[@]}" <<'SQL'
DROP TABLE IF EXISTS wp19_districts_export;
CREATE TABLE wp19_districts_export (
  adi text PRIMARY KEY,
  geom geometry(MultiPolygon, 4326)
);
SQL

python3 - <<PY
import json, subprocess
from pathlib import Path
src = Path("$SRC")
fc = json.loads(src.read_text(encoding="utf-8"))

def rings_to_wkt_polygon(coords):
    parts = []
    for ring in coords:
        pts = ", ".join(f"{c[0]} {c[1]}" for c in ring)
        parts.append(f"({pts})")
    return "POLYGON(" + ", ".join(parts) + ")"

rows = []
for f in fc["features"]:
    adi = f["properties"]["adi"].replace("'", "''")
    g = f["geometry"]
    if g["type"] != "Polygon":
        raise SystemExit(f"unexpected type {g['type']}")
    wkt = ("SRID=4326;" + rings_to_wkt_polygon(g["coordinates"])).replace("'", "''")
    rows.append(
        "INSERT INTO wp19_districts_export(adi,geom) VALUES "
        f"('{adi}', ST_Multi(ST_MakeValid('{wkt}'::geometry)));"
    )
subprocess.run(
    ["docker", "exec", "-i", "parkio-postgres-parking", "psql", "-U", "parkio_parking",
     "-d", "parkio_parking", "-v", "ON_ERROR_STOP=1"],
    input="\n".join(rows), text=True, check=True)
print("loaded", len(rows))
PY

"${PSQL[@]}" -At -c "
SELECT jsonb_build_object(
  'type', 'FeatureCollection',
  'features', (
    SELECT jsonb_agg(
      jsonb_build_object(
        'type', 'Feature',
        'properties', jsonb_build_object('adi', adi),
        'geometry', ST_AsGeoJSON(geom)::jsonb
      ) ORDER BY adi
    )
    FROM wp19_districts_export
  )
);" > "$OUT_DIR/izmir-districts-normalized.geojson"

NORM_SHA="$(sha256sum "$OUT_DIR/izmir-districts-normalized.geojson" | awk '{print $1}')"
printf '%s  izmir-ilceler-source.geojson\n%s  izmir-districts-normalized.geojson\n' \
  "$SRC_SHA" "$NORM_SHA" > "$OUT_DIR/CHECKSUMS.sha256"
echo "normalized_sha=$NORM_SHA"
echo "OK — set PARKIO_MUNICIPAL_OPS_DISTRICT_COVERAGE_NORMALIZED_ASSET_SHA256=$NORM_SHA for DATA-WP-19A"
