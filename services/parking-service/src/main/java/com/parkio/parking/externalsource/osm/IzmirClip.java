package com.parkio.parking.externalsource.osm;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Versioned İzmir clip used as a secondary safety filter on OSM parking GeoJSON.
 *
 * <p>Primary geographic clip is the operator osmium polygon extract
 * ({@link #ADMIN_CLIP_VERSION}). {@link #BBOX_CLIP_VERSION} remains available for
 * rollback. When a MultiPolygon boundary is configured, point-in-polygon is used;
 * otherwise the verified administrative envelope is used.
 */
public final class IzmirClip {
    public static final String ADMIN_CLIP_VERSION = "izmir-admin-izbb-2024-10-18-v1";
    /** Legacy metropolitan bbox clip retained for rollback only. */
    public static final String BBOX_CLIP_VERSION = "izmir-bbox-v1";
    public static final String CLIP_VERSION = ADMIN_CLIP_VERSION;

    /** Verified admin-boundary envelope (EPSG:4326) from İZBB ilceler dissolve. */
    public static final double ADMIN_SOUTH = 37.815253;
    public static final double ADMIN_NORTH = 39.3854527;
    public static final double ADMIN_WEST = 26.2302474;
    public static final double ADMIN_EAST = 28.4930441;

    /** Legacy temporary metropolitan bbox. */
    public static final double SOUTH = 37.85;
    public static final double NORTH = 39.05;
    public static final double WEST = 26.20;
    public static final double EAST = 28.45;

    private static volatile Membership membership = Membership.adminEnvelope();

    private IzmirClip() {}

    public static synchronized void configure(Membership next) {
        membership = Objects.requireNonNull(next, "membership");
    }

    public static synchronized void resetToAdminEnvelope() {
        membership = Membership.adminEnvelope();
    }

    public static synchronized void resetToLegacyBbox() {
        membership = Membership.legacyBbox();
    }

    public static boolean contains(double lat, double lng) {
        return membership.contains(lat, lng);
    }

    public static Membership currentMembership() {
        return membership;
    }

    /** Immutable clip membership strategy. */
    public static final class Membership {
        private final String mode;
        private final double south;
        private final double north;
        private final double west;
        private final double east;
        private final List<double[][]> polygons;

        private Membership(
                String mode,
                double south,
                double north,
                double west,
                double east,
                List<double[][]> polygons) {
            this.mode = mode;
            this.south = south;
            this.north = north;
            this.west = west;
            this.east = east;
            this.polygons = polygons;
        }

        public static Membership legacyBbox() {
            return new Membership("legacy-bbox", SOUTH, NORTH, WEST, EAST, List.of());
        }

        public static Membership adminEnvelope() {
            return new Membership(
                    "admin-envelope", ADMIN_SOUTH, ADMIN_NORTH, ADMIN_WEST, ADMIN_EAST, List.of());
        }

        public static Membership polygon(List<double[][]> polygons) {
            if (polygons == null || polygons.isEmpty()) {
                throw new IllegalArgumentException("polygons required");
            }
            List<double[][]> copy = List.copyOf(polygons);
            double s = Double.POSITIVE_INFINITY;
            double n = Double.NEGATIVE_INFINITY;
            double w = Double.POSITIVE_INFINITY;
            double e = Double.NEGATIVE_INFINITY;
            for (double[][] ring : copy) {
                for (double[] c : ring) {
                    w = Math.min(w, c[0]);
                    e = Math.max(e, c[0]);
                    s = Math.min(s, c[1]);
                    n = Math.max(n, c[1]);
                }
            }
            return new Membership("polygon", s, n, w, e, copy);
        }

        public String mode() {
            return mode;
        }

        public boolean contains(double lat, double lng) {
            if (!Double.isFinite(lat) || !Double.isFinite(lng)) {
                return false;
            }
            if (lat < south || lat > north || lng < west || lng > east) {
                return false;
            }
            if (polygons.isEmpty()) {
                return true;
            }
            for (double[][] ring : polygons) {
                if (pointInRing(lng, lat, ring)) {
                    return true;
                }
            }
            return false;
        }

        private static boolean pointInRing(double x, double y, double[][] ring) {
            boolean inside = false;
            for (int i = 0, j = ring.length - 1; i < ring.length; j = i++) {
                double xi = ring[i][0];
                double yi = ring[i][1];
                double xj = ring[j][0];
                double yj = ring[j][1];
                boolean intersect = ((yi > y) != (yj > y))
                        && (x < (xj - xi) * (y - yi) / (yj - yi + 0.0) + xi);
                if (intersect) {
                    inside = !inside;
                }
            }
            return inside;
        }
    }

    /** Collect exterior rings from a GeoJSON Polygon or MultiPolygon node tree. */
    public static List<double[][]> exteriorRingsFromCoordinates(com.fasterxml.jackson.databind.JsonNode geometry) {
        List<double[][]> rings = new ArrayList<>();
        if (geometry == null || geometry.isMissingNode() || geometry.isNull()) {
            return rings;
        }
        String type = geometry.path("type").asText();
        com.fasterxml.jackson.databind.JsonNode coordinates = geometry.path("coordinates");
        if ("Polygon".equals(type)) {
            addExterior(rings, coordinates);
        } else if ("MultiPolygon".equals(type)) {
            for (com.fasterxml.jackson.databind.JsonNode poly : coordinates) {
                addExterior(rings, poly);
            }
        }
        return rings;
    }

    private static void addExterior(
            List<double[][]> rings, com.fasterxml.jackson.databind.JsonNode polygonCoords) {
        if (polygonCoords == null || !polygonCoords.isArray() || polygonCoords.isEmpty()) {
            return;
        }
        com.fasterxml.jackson.databind.JsonNode exterior = polygonCoords.get(0);
        if (exterior == null || !exterior.isArray() || exterior.size() < 4) {
            return;
        }
        double[][] ring = new double[exterior.size()][2];
        for (int i = 0; i < exterior.size(); i++) {
            ring[i][0] = exterior.get(i).get(0).asDouble();
            ring[i][1] = exterior.get(i).get(1).asDouble();
        }
        rings.add(ring);
    }
}
