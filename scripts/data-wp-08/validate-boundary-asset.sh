#!/usr/bin/env bash
# Validate DATA-WP-08 operator boundary asset checksums + manifest presence.
set -euo pipefail

BOUNDARY_DIR="${1:-${PARKIO_OSM_BOUNDARY_DIR:-/opt/parkio/ops/data-wp-08/boundary}}"
EXPECTED_SOURCE_SHA="${PARKIO_MUNICIPAL_OSM_BOUNDARY_SOURCE_SHA256:-6f4f43e4ce8139ddca4606582d903f047cb7c73810f8b876541a1ec3994ffd89}"
EXPECTED_GEOJSON_SHA="${PARKIO_MUNICIPAL_OSM_BOUNDARY_GEOJSON_SHA256:-ddd5664064a6bad22920d64a9f83c5c11c3ba85e4fb0e55a17bd3a26c31d2b61}"
EXPECTED_POLY_SHA="${PARKIO_MUNICIPAL_OSM_BOUNDARY_POLY_SHA256:-5b20558b28e93c1fb7f2bcda2e36142b186846e63a7151285dae76bb19f5d7b1}"

sha256_file() {
  local f="$1"
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum -- "$f" | awk '{print $1}'
  else
    shasum -a 256 -- "$f" | awk '{print $1}'
  fi
}

require_file() {
  local f="$1"
  if [[ ! -f "$f" ]]; then
    echo "MISSING $f" >&2
    exit 1
  fi
}

require_file "${BOUNDARY_DIR}/MANIFEST.yml"
require_file "${BOUNDARY_DIR}/CHECKSUMS.sha256"
require_file "${BOUNDARY_DIR}/validation-report.json"
require_file "${BOUNDARY_DIR}/izmir-ilceler-source.geojson"
require_file "${BOUNDARY_DIR}/izmir-admin-boundary.geojson"
require_file "${BOUNDARY_DIR}/izmir-admin-boundary.poly"

check() {
  local path="$1"
  local expected="$2"
  local actual
  actual="$(sha256_file "${path}")"
  if [[ "${actual}" != "${expected}" ]]; then
    echo "CHECKSUM_FAIL $(basename -- "$path") got=${actual} expected=${expected}" >&2
    exit 1
  fi
  echo "OK $(basename -- "$path") ${actual}"
}

check "${BOUNDARY_DIR}/izmir-ilceler-source.geojson" "${EXPECTED_SOURCE_SHA}"
check "${BOUNDARY_DIR}/izmir-admin-boundary.geojson" "${EXPECTED_GEOJSON_SHA}"
check "${BOUNDARY_DIR}/izmir-admin-boundary.poly" "${EXPECTED_POLY_SHA}"

# Confirm clip version marker in poly header
header="$(head -n 1 "${BOUNDARY_DIR}/izmir-admin-boundary.poly")"
if [[ "${header}" != "izmir-admin-izbb-2024-10-18-v1" ]]; then
  echo "poly header clip version mismatch: ${header}" >&2
  exit 1
fi

echo "boundary_asset_validation_passed dir=${BOUNDARY_DIR}"
