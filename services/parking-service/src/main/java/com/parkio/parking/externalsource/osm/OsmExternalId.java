package com.parkio.parking.externalsource.osm;

/** Stable OSM external IDs: node/123, way/456, relation/789 — never bare numeric IDs. */
public final class OsmExternalId {
    private OsmExternalId() {}

    public static String of(OsmElementType type, long osmId) {
        if (osmId <= 0) {
            throw new IllegalArgumentException("osmId must be positive");
        }
        return type.wire() + "/" + osmId;
    }

    public static OsmElementType typeOf(String externalId) {
        int slash = externalId.indexOf('/');
        if (slash <= 0) {
            throw new IllegalArgumentException("invalid OSM external id: " + externalId);
        }
        return OsmElementType.fromWire(externalId.substring(0, slash));
    }

    public static long idOf(String externalId) {
        int slash = externalId.indexOf('/');
        if (slash <= 0 || slash == externalId.length() - 1) {
            throw new IllegalArgumentException("invalid OSM external id: " + externalId);
        }
        return Long.parseLong(externalId.substring(slash + 1));
    }
}