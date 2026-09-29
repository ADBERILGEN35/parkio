#!/usr/bin/env bash
# DATA-WP-08 — polygon extract OSM parking for İzmir (atomic promote).
# Reuses DATA-WP-02 osmium Docker workflow; does not call Overpass.
set -euo pipefail

OPS_DIR="${PARKIO_OSM_OPS_DIR:-/opt/parkio/ops/data-wp-02b}"
BOUNDARY_DIR="${PARKIO_OSM_BOUNDARY_DIR:-/opt/parkio/ops/data-wp-08/boundary}"
CLIP_VERSION="${PARKIO_MUNICIPAL_OSM_CLIP_VERSION:-izmir-admin-izbb-2024-10-18-v1}"
OSMIUM_IMAGE="${PARKIO_OSMIUM_IMAGE:-iboates/osmium:latest}"
TURKEY_PBF="${1:-}"
EXPECTED_TURKEY_SHA="${PARKIO_TURKEY_PBF_SHA256:-}"
EXPECTED_POLY_SHA="${PARKIO_MUNICIPAL_OSM_BOUNDARY_POLY_SHA256:-5b20558b28e93c1fb7f2bcda2e36142b186846e63a7151285dae76bb19f5d7b1}"

if [[ -z "${TURKEY_PBF}" ]]; then
  echo "usage: $0 /path/to/turkey-YYYYMMDD.osm.pbf" >&2
  exit 2
fi

sha256_file() {
  local f="$1"
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum -- "$f" | awk '{print $1}'
  else
    shasum -a 256 -- "$f" | awk '{print $1}'
  fi
}

quote_docker_path() {
  # Paths are passed as Docker volume sources; callers must supply absolute paths.
  printf '%s' "$1"
}

BOUNDARY_POLY="${BOUNDARY_DIR}/izmir-admin-boundary.poly"
if [[ ! -f "${BOUNDARY_POLY}" ]]; then
  echo "missing boundary poly: ${BOUNDARY_POLY}" >&2
  exit 1
fi
POLY_SHA="$(sha256_file "${BOUNDARY_POLY}")"
if [[ -n "${EXPECTED_POLY_SHA}" && "${POLY_SHA}" != "${EXPECTED_POLY_SHA}" ]]; then
  echo "boundary poly checksum mismatch: got ${POLY_SHA} expected ${EXPECTED_POLY_SHA}" >&2
  exit 1
fi

if [[ ! -f "${TURKEY_PBF}" ]]; then
  echo "missing turkey pbf: ${TURKEY_PBF}" >&2
  exit 1
fi
TURKEY_SHA="$(sha256_file "${TURKEY_PBF}")"
if [[ -n "${EXPECTED_TURKEY_SHA}" && "${TURKEY_SHA}" != "${EXPECTED_TURKEY_SHA}" ]]; then
  echo "turkey pbf checksum mismatch: got ${TURKEY_SHA} expected ${EXPECTED_TURKEY_SHA}" >&2
  exit 1
fi

mkdir -p -- "${OPS_DIR}"
TMP_DIR="$(mktemp -d "${OPS_DIR}/tmp-extract.XXXXXX")"
cleanup() {
  rm -rf -- "${TMP_DIR}"
}
trap cleanup EXIT

TURKEY_BASENAME="$(basename -- "${TURKEY_PBF}")"
OPS_ABS="$(cd -- "${OPS_DIR}" && pwd)"
BOUNDARY_ABS="$(cd -- "${BOUNDARY_DIR}" && pwd)"
TMP_ABS="$(cd -- "${TMP_DIR}" && pwd)"

# Stage turkey pbf into ops mount if needed
if [[ "$(cd -- "$(dirname -- "${TURKEY_PBF}")" && pwd)/${TURKEY_BASENAME}" != "${OPS_ABS}/${TURKEY_BASENAME}" ]]; then
  cp -f -- "${TURKEY_PBF}" "${OPS_ABS}/${TURKEY_BASENAME}"
fi

echo "extract_start clipVersion=${CLIP_VERSION} turkeySha=${TURKEY_SHA} polySha=${POLY_SHA}"

docker run --rm \
  -v "$(quote_docker_path "${OPS_ABS}"):/data" \
  -v "$(quote_docker_path "${BOUNDARY_ABS}"):/boundary:ro" \
  -v "$(quote_docker_path "${TMP_ABS}"):/tmpout" \
  "${OSMIUM_IMAGE}" extract \
  -p /boundary/izmir-admin-boundary.poly \
  -s complete_ways --set-bounds \
  -o "/tmpout/${CLIP_VERSION}.osm.pbf" \
  "/data/${TURKEY_BASENAME}"

docker run --rm \
  -v "$(quote_docker_path "${TMP_ABS}"):/tmpout" \
  "${OSMIUM_IMAGE}" tags-filter \
  -o /tmpout/izmir-parking-objects.osm.pbf \
  "/tmpout/${CLIP_VERSION}.osm.pbf" \
  nwr/amenity=parking

docker run --rm \
  -v "$(quote_docker_path "${TMP_ABS}"):/tmpout" \
  "${OSMIUM_IMAGE}" getid -r \
  -I /tmpout/izmir-parking-objects.osm.pbf \
  -o /tmpout/izmir-parking-complete.osm.pbf \
  "/tmpout/${CLIP_VERSION}.osm.pbf"

EXPORT_CFG="${OPS_ABS}/osmium-export-parkio.json"
if [[ ! -f "${EXPORT_CFG}" ]]; then
  echo "missing export config: ${EXPORT_CFG}" >&2
  exit 1
fi

docker run --rm \
  -v "$(quote_docker_path "${OPS_ABS}"):/data:ro" \
  -v "$(quote_docker_path "${TMP_ABS}"):/tmpout" \
  "${OSMIUM_IMAGE}" export \
  -c /data/osmium-export-parkio.json -a type,id -u type_id \
  --geometry-types=point,polygon -e -O -f geojson \
  -o /tmpout/izmir-parking-osmium.geojson \
  /tmpout/izmir-parking-complete.osm.pbf

# Promote only after successful validation of non-empty outputs
for f in "${CLIP_VERSION}.osm.pbf" izmir-parking-complete.osm.pbf izmir-parking-osmium.geojson; do
  if [[ ! -s "${TMP_ABS}/${f}" ]]; then
    echo "refusing promote: empty or missing ${f}" >&2
    exit 1
  fi
done

# Preserve previous known-good artifacts
preserve_if_exists() {
  local target="$1"
  if [[ -f "${OPS_ABS}/${target}" ]]; then
    cp -f -- "${OPS_ABS}/${target}" "${OPS_ABS}/${target}.prev"
  fi
}
preserve_if_exists "${CLIP_VERSION}.osm.pbf"
preserve_if_exists "izmir-parking-complete.osm.pbf"
preserve_if_exists "izmir-parking-osmium.geojson"
# Never delete legacy bbox extract
preserve_if_exists "izmir-bbox-v1.osm.pbf"

mv -f -- "${TMP_ABS}/${CLIP_VERSION}.osm.pbf" "${OPS_ABS}/${CLIP_VERSION}.osm.pbf"
mv -f -- "${TMP_ABS}/izmir-parking-complete.osm.pbf" "${OPS_ABS}/izmir-parking-complete.osm.pbf"
mv -f -- "${TMP_ABS}/izmir-parking-osmium.geojson" "${OPS_ABS}/izmir-parking-osmium.geojson"

{
  echo "${TURKEY_SHA}  ${TURKEY_BASENAME}"
  echo "${POLY_SHA}  izmir-admin-boundary.poly"
  echo "$(sha256_file "${OPS_ABS}/${CLIP_VERSION}.osm.pbf")  ${CLIP_VERSION}.osm.pbf"
  echo "$(sha256_file "${OPS_ABS}/izmir-parking-complete.osm.pbf")  izmir-parking-complete.osm.pbf"
  echo "$(sha256_file "${OPS_ABS}/izmir-parking-osmium.geojson")  izmir-parking-osmium.geojson"
  echo "clipVersion=${CLIP_VERSION}"
  echo "toolImage=${OSMIUM_IMAGE}"
  echo "extractedAt=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
} > "${OPS_ABS}/${CLIP_VERSION}.extract-meta.txt"

echo "extract_complete clipVersion=${CLIP_VERSION} meta=${OPS_ABS}/${CLIP_VERSION}.extract-meta.txt"
