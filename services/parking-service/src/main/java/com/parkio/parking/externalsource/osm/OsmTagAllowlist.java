package com.parkio.parking.externalsource.osm;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Bounded allowlisted OSM tags retained for audit/remap. */
public final class OsmTagAllowlist {
    public static final Set<String> KEYS = Set.of(
            "amenity", "name", "operator", "brand", "parking", "capacity", "capacity:disabled",
            "fee", "access", "opening_hours", "maxstay", "park_ride", "supervised", "covered",
            "underground", "layer", "building", "wheelchair", "source", "note");

    private OsmTagAllowlist() {}

    public static Map<String, String> filter(Map<String, String> tags) {
        Map<String, String> out = new LinkedHashMap<>();
        if (tags == null) {
            return out;
        }
        for (String key : KEYS) {
            String value = tags.get(key);
            if (value != null && !value.isBlank() && value.length() <= 512) {
                out.put(key, value.trim());
            }
        }
        return out;
    }
}