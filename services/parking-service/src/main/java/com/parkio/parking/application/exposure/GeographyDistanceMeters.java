package com.parkio.parking.application.exposure;

/** WGS84 haversine distance in meters — consistent with PostGIS geography semantics. */
public final class GeographyDistanceMeters {

    private static final double EARTH_RADIUS_METERS = 6_371_000.0;

    private GeographyDistanceMeters() {
    }

    public static int haversineMeters(double lat1, double lon1, double lat2, double lon2) {
        double lat1Rad = Math.toRadians(lat1);
        double lat2Rad = Math.toRadians(lat2);
        double deltaLat = Math.toRadians(lat2 - lat1);
        double deltaLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(deltaLat / 2) * Math.sin(deltaLat / 2)
                + Math.cos(lat1Rad) * Math.cos(lat2Rad) * Math.sin(deltaLon / 2) * Math.sin(deltaLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        long meters = Math.round(EARTH_RADIUS_METERS * c);
        if (meters > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) meters;
    }
}