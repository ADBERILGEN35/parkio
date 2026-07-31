#!/usr/bin/env python3
"""Build derived İzmir admin boundary from official ilceler GeoJSON.

Transparent repair: Polygon rings that are islands incorrectly encoded as holes
(Hole lies outside shell) are promoted to MultiPolygon components.
Source file is never modified.
"""
from __future__ import annotations

import hashlib
import json
import sys
from pathlib import Path

from osgeo import ogr, osr

ogr.UseExceptions()

CLIP_VERSION = "izmir-admin-izbb-2024-10-18-v1"


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def promote_islands(geom: ogr.Geometry, name: str):
    """Promote island rings encoded as exterior holes into MultiPolygon parts."""
    if geom.GetGeometryName() != "POLYGON":
        return geom.Clone(), None
    if geom.GetGeometryCount() <= 1:
        return geom.Clone(), None
    if geom.IsValid():
        return geom.Clone(), None

    mp = ogr.Geometry(ogr.wkbMultiPolygon)
    for i in range(geom.GetGeometryCount()):
        ring = geom.GetGeometryRef(i)
        poly = ogr.Geometry(ogr.wkbPolygon)
        poly.AddGeometry(ring.Clone())
        if not poly.IsValid():
            fixed = poly.Buffer(0)
            if fixed is None or fixed.IsEmpty():
                raise RuntimeError(f"{name}: cannot repair ring {i}")
            if fixed.GetGeometryName() == "POLYGON":
                poly = fixed
            elif fixed.GetGeometryName() == "MULTIPOLYGON":
                for j in range(fixed.GetGeometryCount()):
                    mp.AddGeometry(fixed.GetGeometryRef(j).Clone())
                continue
            else:
                raise RuntimeError(f"{name}: unexpected fix type {fixed.GetGeometryName()}")
        mp.AddGeometry(poly)

    if not mp.IsValid():
        mp = mp.Buffer(0)

    # Invalid Polygon-with-exterior-hole reports GetArea() as shell minus "hole".
    # Compare against the sum of each ring treated as its own exterior instead.
    ring_area_sum = 0.0
    for i in range(geom.GetGeometryCount()):
        ring_poly = ogr.Geometry(ogr.wkbPolygon)
        ring_poly.AddGeometry(geom.GetGeometryRef(i).Clone())
        ring_area_sum += abs(ring_poly.GetArea())
    area_after = mp.GetArea()
    ratio = abs(area_after - ring_area_sum) / ring_area_sum if ring_area_sum else 0.0
    info = {
        "feature": name,
        "originalInvalidity": "Hole lies outside shell (island rings encoded as Polygon holes)",
        "repairMethod": "Promote exterior island rings to MultiPolygon components",
        "reportedInvalidPolygonAreaDeg2": geom.GetArea(),
        "ringAreaSumDeg2": ring_area_sum,
        "areaAfterDeg2": area_after,
        "areaDeltaRatioVsRingSum": ratio,
        "accepted": bool(ratio < 0.01 and mp.IsValid()),
    }
    if not info["accepted"]:
        raise RuntimeError(f"{name}: repair rejected {info}")
    return mp, info


def round_geom(g: ogr.Geometry, nd: int = 7) -> ogr.Geometry:
    payload = json.loads(g.ExportToJson())

    def rnd(c):
        if isinstance(c[0], (int, float)):
            return [round(float(c[0]), nd), round(float(c[1]), nd)]
        return [rnd(x) for x in c]

    payload["coordinates"] = rnd(payload["coordinates"])
    return ogr.CreateGeometryFromJson(json.dumps(payload))


def as_multipolygon(g: ogr.Geometry) -> ogr.Geometry:
    if g.GetGeometryName() == "MULTIPOLYGON":
        return g
    if g.GetGeometryName() == "POLYGON":
        mp = ogr.Geometry(ogr.wkbMultiPolygon)
        mp.AddGeometry(g)
        return mp
    fixed = g.Buffer(0)
    return as_multipolygon(fixed)


def write_poly(path: Path, mp: ogr.Geometry, name: str) -> None:
    lines = [name]
    part = 1
    geoms = []
    if mp.GetGeometryName() == "MULTIPOLYGON":
        for i in range(mp.GetGeometryCount()):
            geoms.append(mp.GetGeometryRef(i))
    else:
        geoms.append(mp)
    for poly in geoms:
        for ri in range(poly.GetGeometryCount()):
            ring = poly.GetGeometryRef(ri)
            lines.append(str(part if ri == 0 else -part))
            for j in range(ring.GetPointCount()):
                x, y, _z = ring.GetPoint(j)
                lines.append(f"   {x:.7f}   {y:.7f}")
            lines.append("END")
            if ri == 0:
                part += 1
    lines.append("END")
    path.write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")


def main() -> int:
    out_dir = Path(sys.argv[1] if len(sys.argv) > 1 else "/out")
    src_path = Path(sys.argv[2] if len(sys.argv) > 2 else "/data/ilceler.geojson")
    out_geojson = out_dir / "izmir-admin-boundary.geojson"
    out_poly = out_dir / "izmir-admin-boundary.poly"
    report_path = out_dir / "validation-report.json"

    ds = ogr.Open(str(src_path))
    if ds is None:
        raise RuntimeError(f"cannot open {src_path}")
    lyr = ds.GetLayer(0)

    repairs = []
    districts = []
    union = None
    for ft in lyr:
        name = ft.GetField("adi")
        g = ft.GetGeometryRef()
        repaired, info = promote_islands(g, name)
        if info:
            repairs.append(info)
            print("REPAIRED", name, info, flush=True)
        if not repaired.IsValid():
            raise RuntimeError(f"{name} still invalid after repair")
        districts.append((name, repaired))
        union = repaired.Clone() if union is None else union.Union(repaired)

    union = as_multipolygon(union)
    # Deterministic coordinate quantization, then GEOS cleanup for collapsed rings.
    union = round_geom(union, 7)
    if not union.IsValid():
        cleaned = union.Buffer(0)
        if cleaned is None or cleaned.IsEmpty() or not cleaned.IsValid():
            raise RuntimeError("derived union invalid after quantization")
        before = union.GetArea()
        after = cleaned.GetArea()
        delta = abs(after - before) / before if before else 0.0
        print("QUANTIZE_CLEANUP", {"areaDeltaRatio": delta, "type": cleaned.GetGeometryName()}, flush=True)
        if delta > 0.001:
            raise RuntimeError(f"quantize cleanup altered area too much: {delta}")
        union = cleaned
    union = as_multipolygon(union)
    if not union.IsValid():
        raise RuntimeError("derived union invalid")

    env = union.GetEnvelope()  # minX maxX minY maxY
    print("FINAL_TYPE", union.GetGeometryName(), "VALID", union.IsValid(), "PARTS", union.GetGeometryCount(), flush=True)
    print("BBOX", env, flush=True)

    src_srs = osr.SpatialReference()
    src_srs.ImportFromEPSG(4326)
    src_srs.SetAxisMappingStrategy(osr.OAMS_TRADITIONAL_GIS_ORDER)
    dst_srs = osr.SpatialReference()
    try:
        dst_srs.ImportFromEPSG(5637)
    except Exception:
        dst_srs.ImportFromEPSG(32635)
    dst_srs.SetAxisMappingStrategy(osr.OAMS_TRADITIONAL_GIS_ORDER)
    ct = osr.CoordinateTransformation(src_srs, dst_srs)
    union_p = union.Clone()
    union_p.Transform(ct)
    area_km2 = union_p.GetArea() / 1e6
    print("AREA_KM2", area_km2, flush=True)

    points = {
        "Konak": (27.1285, 38.4192),
        "Karsiyaka": (27.1167, 38.4550),
        "Bornova": (27.2200, 38.4700),
        "Cesme": (26.3058, 38.3220),
        "Bergama": (27.1800, 39.1200),
        "Selcuk": (27.3680, 37.9500),
        "Manisa_center": (27.4280, 38.6191),
    }
    sanity = {}
    for label, (lon, lat) in points.items():
        pt = ogr.Geometry(ogr.wkbPoint)
        pt.AddPoint(lon, lat)
        inside = bool(union.Contains(pt) or union.Touches(pt))
        sanity[label] = {
            "lon": lon,
            "lat": lat,
            "containsOrTouches": inside,
            "intersects": bool(union.Intersects(pt)),
        }
        print("POINT", label, sanity[label], flush=True)

    required_inside = ["Konak", "Karsiyaka", "Bornova", "Cesme", "Bergama", "Selcuk"]
    for label in required_inside:
        if not sanity[label]["containsOrTouches"]:
            raise RuntimeError(f"sanity failed: {label} not inside boundary")
    if sanity["Manisa_center"]["containsOrTouches"]:
        raise RuntimeError("sanity failed: Manisa_center unexpectedly inside")

    feature = {
        "type": "Feature",
        "properties": {
            "clipVersion": CLIP_VERSION,
            "provider": "Izmir Buyuksehir Belediyesi",
            "derivedFrom": "ilceler.geojson resource c4b1da96-c547-4cca-a9a7-4053d0fee54f",
            "license": "CC-BY-4.0",
            "attribution": (
                "Contains public sector information licensed under CC BY 4.0 by "
                "Izmir Metropolitan Municipality (Izmir Sehir Haritasi / ilceler)."
            ),
        },
        "geometry": json.loads(union.ExportToJson()),
    }
    fc = {"type": "FeatureCollection", "features": [feature]}
    geo_text = json.dumps(fc, ensure_ascii=False, separators=(",", ":")) + "\n"
    out_geojson.write_text(geo_text, encoding="utf-8", newline="\n")
    write_poly(out_poly, union, CLIP_VERSION)

    coverage_missing = []
    for name, g in districts:
        c = g.Centroid()
        if not (union.Contains(c) or union.Intersects(c)):
            coverage_missing.append(name)

    poly_count = union.GetGeometryCount()
    ring_count = sum(union.GetGeometryRef(i).GetGeometryCount() for i in range(poly_count))
    src_area = sum(g.GetArea() for _, g in districts)
    der_area = union.GetArea()

    report = {
        "clipVersion": CLIP_VERSION,
        "sourcePath": str(src_path),
        "sourceSha256": sha256_file(src_path),
        "sourceFeatureCount": len(districts),
        "districtCount": len(districts),
        "districtNameField": "adi",
        "districtNamesSorted": sorted(n for n, _ in districts),
        "repairs": repairs,
        "unionValid": bool(union.IsValid()),
        "geometryType": union.GetGeometryName(),
        "componentCount": poly_count,
        "ringCount": ring_count,
        "bbox": {"west": env[0], "east": env[1], "south": env[2], "north": env[3]},
        "areaKm2Projected": area_km2,
        "sourceAreaDeg2Sum": src_area,
        "derivedAreaDeg2": der_area,
        "sourceVsDerivedAreaDeltaRatio": abs(der_area - src_area) / src_area if src_area else None,
        "districtCoverageMissingCentroids": coverage_missing,
        "sanityPoints": sanity,
        "islandsCoastal": {
            "urlaIslandPromoted": any(r["feature"] == "URLA" for r in repairs),
            "dikiliIslandPromoted": any(r["feature"] == "DİKİLİ" for r in repairs),
            "componentCountIncludesIslands": poly_count >= 2,
        },
        "derivedGeojsonSha256": sha256_file(out_geojson),
        "derivedPolySha256": sha256_file(out_poly),
        "boundaryEdgePolicy": "Contains OR Touches (points exactly on boundary are included)",
        "validationStatus": "ACCEPTED",
        "approvedForParkioDataWp08": True,
    }
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("REPORT_WRITTEN", flush=True)
    print(json.dumps({
        "unionValid": report["unionValid"],
        "componentCount": report["componentCount"],
        "areaKm2Projected": report["areaKm2Projected"],
        "derivedGeojsonSha256": report["derivedGeojsonSha256"],
        "derivedPolySha256": report["derivedPolySha256"],
        "districtCoverageMissingCentroids": report["districtCoverageMissingCentroids"],
        "bbox": report["bbox"],
    }, indent=2), flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
