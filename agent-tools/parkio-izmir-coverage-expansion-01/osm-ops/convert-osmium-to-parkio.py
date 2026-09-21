#!/usr/bin/env python3
"""Convert osmium GeoJSON export → Parkio osm-parking-geojson-v1 interchange."""
from __future__ import annotations

import json
import sys
from pathlib import Path


def element_type_and_id(feature: dict) -> tuple[str, int] | None:
    props = feature.get("properties") or {}
    fid = feature.get("id")
    if isinstance(fid, str) and "/" in fid:
        kind, raw = fid.split("/", 1)
        try:
            return kind.lower(), int(raw)
        except ValueError:
            return None
    osm_type = props.get("osmType") or props.get("type") or props.get("@type")
    osm_id = props.get("osmId") or props.get("id") or props.get("@id")
    if osm_type is None or osm_id is None:
        return None
    try:
        return str(osm_type).lower(), int(osm_id)
    except (TypeError, ValueError):
        return None


def convert(src: Path, dest: Path) -> int:
    data = json.loads(src.read_text(encoding="utf-8"))
    if data.get("type") != "FeatureCollection":
        raise SystemExit("expected FeatureCollection")
    out_features = []
    for feature in data.get("features") or []:
        parsed = element_type_and_id(feature)
        if parsed is None:
            continue
        osm_type, osm_id = parsed
        props = dict(feature.get("properties") or {})
        # Flatten nested tags if present
        tags = props.pop("tags", None)
        if isinstance(tags, dict):
            for k, v in tags.items():
                props.setdefault(k, v)
        amenity = str(props.get("amenity") or "").lower()
        if amenity != "parking":
            continue
        props["osmType"] = osm_type
        props["osmId"] = osm_id
        props["amenity"] = "parking"
        out_features.append(
            {
                "type": "Feature",
                "id": f"{osm_type}/{osm_id}",
                "geometry": feature.get("geometry"),
                "properties": props,
            }
        )
    out = {
        "type": "FeatureCollection",
        "parkioImportVersion": "osm-parking-geojson-v1",
        "provenance": {
            "license": "ODbL-1.0",
            "attribution": "© OpenStreetMap contributors",
            "source": "Geofabrik turkey-latest via osmium İzmir admin clip",
            "note": "Converted for Parkio isolated import; not municipal endorsement",
        },
        "features": out_features,
    }
    dest.write_text(json.dumps(out, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    return len(out_features)


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit(f"usage: {sys.argv[0]} <osmium.geojson> <parkio.geojson>")
    n = convert(Path(sys.argv[1]), Path(sys.argv[2]))
    print(f"converted_features={n}")
