package com.parkio.parking.externalsource.osm;

/**
 * Versioned Izmir clip. Temporary documented bbox fallback until an admin-boundary
 * polygon is licensed into the repository.
 *
 * <p>clipVersion={@value #CLIP_VERSION}
 */
public final class IzmirClip {
    public static final String CLIP_VERSION = "izmir-bbox-v1";
    /** Conservative Izmir metropolitan bounding box (WGS84). */
    public static final double SOUTH = 37.85;
    public static final double NORTH = 39.05;
    public static final double WEST = 26.20;
    public static final double EAST = 28.45;

    private IzmirClip() {}

    public static boolean contains(double lat, double lng) {
        return Double.isFinite(lat) && Double.isFinite(lng)
                && lat >= SOUTH && lat <= NORTH
                && lng >= WEST && lng <= EAST;
    }
}