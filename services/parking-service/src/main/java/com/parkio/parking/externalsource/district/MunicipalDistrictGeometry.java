package com.parkio.parking.externalsource.district;

import java.util.List;
import java.util.Objects;

/**
 * Immutable district geometry for point-in-polygon assignment (DATA-WP-18).
 *
 * <p>Membership uses {@code covers}: interior or boundary of any exterior ring, excluding
 * true holes. Island rings incorrectly nested as holes are promoted to exterior components
 * (same repair semantics as WP-08 dissolve).
 */
public final class MunicipalDistrictGeometry {
    private final String districtName;
    private final String foldedName;
    private final List<RingSet> polygons;
    private final double south;
    private final double north;
    private final double west;
    private final double east;

    public MunicipalDistrictGeometry(String districtName, String foldedName, List<RingSet> polygons) {
        this.districtName = Objects.requireNonNull(districtName, "districtName");
        this.foldedName = Objects.requireNonNull(foldedName, "foldedName");
        this.polygons = List.copyOf(Objects.requireNonNull(polygons, "polygons"));
        if (this.polygons.isEmpty()) {
            throw new IllegalArgumentException("polygons required");
        }
        double s = Double.POSITIVE_INFINITY;
        double n = Double.NEGATIVE_INFINITY;
        double w = Double.POSITIVE_INFINITY;
        double e = Double.NEGATIVE_INFINITY;
        for (RingSet poly : this.polygons) {
            for (double[] c : poly.exterior()) {
                w = Math.min(w, c[0]);
                e = Math.max(e, c[0]);
                s = Math.min(s, c[1]);
                n = Math.max(n, c[1]);
            }
        }
        this.south = s;
        this.north = n;
        this.west = w;
        this.east = e;
    }

    public String districtName() {
        return districtName;
    }

    public String foldedName() {
        return foldedName;
    }

    public boolean covers(double longitude, double latitude) {
        if (!Double.isFinite(longitude) || !Double.isFinite(latitude)) {
            return false;
        }
        if (latitude < south || latitude > north || longitude < west || longitude > east) {
            return false;
        }
        for (RingSet poly : polygons) {
            if (poly.covers(longitude, latitude)) {
                return true;
            }
        }
        return false;
    }

    /** One polygon: exterior + holes (holes that are islands outside the shell are promoted). */
    public record RingSet(double[][] exterior, List<double[][]> holes) {
        public RingSet {
            Objects.requireNonNull(exterior, "exterior");
            holes = holes == null ? List.of() : List.copyOf(holes);
        }

        boolean covers(double x, double y) {
            if (!pointOnOrInRing(x, y, exterior)) {
                return false;
            }
            for (double[][] hole : holes) {
                // Hole interior is outside the district; hole boundary remains covered (OGC-style).
                if (pointInRingStrict(x, y, hole) && !pointOnBoundary(x, y, hole)) {
                    return false;
                }
            }
            return true;
        }
    }

    static boolean pointOnOrInRing(double x, double y, double[][] ring) {
        return pointOnBoundary(x, y, ring) || pointInRingStrict(x, y, ring);
    }

    static boolean pointInRingStrict(double x, double y, double[][] ring) {
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

    static boolean pointOnBoundary(double x, double y, double[][] ring) {
        final double eps = 1e-12;
        for (int i = 0, j = ring.length - 1; i < ring.length; j = i++) {
            if (pointOnSegment(x, y, ring[j][0], ring[j][1], ring[i][0], ring[i][1], eps)) {
                return true;
            }
        }
        return false;
    }

    private static boolean pointOnSegment(
            double x, double y, double x1, double y1, double x2, double y2, double eps) {
        double cross = (x - x1) * (y2 - y1) - (y - y1) * (x2 - x1);
        if (Math.abs(cross) > eps) {
            return false;
        }
        double dot = (x - x1) * (x2 - x1) + (y - y1) * (y2 - y1);
        if (dot < -eps) {
            return false;
        }
        double lenSq = (x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1);
        return dot <= lenSq + eps;
    }
}
