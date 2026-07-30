package com.parkio.parking.externalsource.osm;

import java.util.Locale;

public enum OsmElementType {
    NODE, WAY, RELATION;

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static OsmElementType fromWire(String value) {
        return OsmElementType.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}