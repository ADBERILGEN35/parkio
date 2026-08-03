# DATA-WP-19 — İzmir District Geometry Topology Reconciliation

**Status:** IMPLEMENTED (not deployed; DATA-WP-19A leave-on gate not started)  
**Policy:** `izmir-district-topology-v1`  
**Normalized asset:** `izmir-districts-izbb-2024-10-18-topology-v1`

## Root cause (proven)

Live DATA-WP-18A observed `overlapAnomalyCount=746` and Karşıyaka=0 while PostGIS
`ST_Covers` after `ST_MakeValid` on the same official asset produced:

- 1420 uniquely covered active facilities
- **0** multi-cover facilities
- Karşıyaka=151, Çiğli=108, intersection facilities=0
- material pairwise overlap area ≈ 0 (boundary residual ~1e-9 deg²)

Classification:

| Code | Finding |
|------|---------|
| **B** | Valid GeoJSON interpreted incorrectly by Parkio |
| **H** | Custom even-odd ray casting (no JTS) produced false-positive `covers` |
| **D** | URLA / DİKİLİ: invalid “Hole lies outside shell” (island-as-hole) |

**Not E** for Karşıyaka/Çiğli: official interiors do not materially overlap under OGC covers.

Folded-name alphabetical tie-break then assigned shadowed Karşıyaka points to Çiğli
(`CIGLI` < `KARSIYAKA`), producing a numerically complete but semantically wrong report.

## Source immutability

- Official `izmir-ilceler-source.geojson` SHA-256
  `6f4f43e4ce8139ddca4606582d903f047cb7c73810f8b876541a1ec3994ffd89` is never modified.
- Derived operator asset: `izmir-districts-normalized.geojson`  
  SHA-256 `0c7457122d13fa02eba1258b6cda5cc28bfb7d64150e4e7db131f40611a655ec`
- Operator layout: `/opt/parkio/ops/data-wp-19/district-topology/` (and `D:\parkio-ops\data-wp-19\district-topology\`)

## Normalization rules

1. Decode Polygon/MultiPolygon per GeoJSON.
2. `ST_MakeValid` / JTS `GeometryFixer` → MultiPolygon.
3. Promote island rings encoded as exterior holes (URLA, DİKİLİ).
4. Do not alphabetical-tie-break material multi-interior matches.
5. Shared-boundary intersection (area ≈ 0) is not material overlap.

Regenerate:

```bash
./scripts/data-wp-19/export-normalized-districts.sh
```

## Assignment (topology mode)

| Case | Classification | District totals |
|------|----------------|-----------------|
| Exactly one cover | ASSIGNED | counted |
| Multi, all boundary-only | BOUNDARY_AMBIGUOUS | excluded |
| Multi, any interior | TOPOLOGY_AMBIGUOUS | excluded |
| Outside | UNASSIGNED | — |
| Bad coordinates | INVALID_COORDINATES | — |

## Configuration (default off)

```
PARKIO_MUNICIPAL_OPS_DISTRICT_COVERAGE_TOPOLOGY_POLICY_ENABLED=false
PARKIO_MUNICIPAL_OPS_DISTRICT_COVERAGE_NORMALIZED_ASSET_PATH=
PARKIO_MUNICIPAL_OPS_DISTRICT_COVERAGE_NORMALIZED_ASSET_SHA256=0c7457122d13fa02eba1258b6cda5cc28bfb7d64150e4e7db131f40611a655ec
```

Legacy DATA-WP-18 interpretation remains when topology policy is disabled (rollback).

## Province clip

Existing WP-08 province clip (`izmir-admin-izbb-2024-10-18-v1`) remains unchanged.
MakeValid district union is compatible; no DATA-WP-08B required from this evidence.

## Report contract (additive)

- `topologyPolicyVersion`, `normalizedAssetVersion`, `topologyStatus`
- `boundaryAmbiguousCount`, `topologyAmbiguousCount`
- `overlapAnomalyCount` remains for WP-18 compatibility (material / topology ambiguous)

## DATA-WP-19A procedure (not started)

1. Mount `/opt/parkio/ops/data-wp-19/district-topology` read-only.
2. Enable topology policy + normalized path/SHA on hosted-beta only.
3. Reconcile Karşıyaka > 0, multi ≈ 0, 30 districts, no leaks.
4. Leave topology enabled only after gate ACCEPT.

## Rollback

1. `PARKIO_MUNICIPAL_OPS_DISTRICT_COVERAGE_TOPOLOGY_POLICY_ENABLED=false` (legacy PIP + tie-break).
2. Or disable district coverage entirely.
3. Application rollback to pre-WP-19 image if needed. No DB rollback (no persistence).
