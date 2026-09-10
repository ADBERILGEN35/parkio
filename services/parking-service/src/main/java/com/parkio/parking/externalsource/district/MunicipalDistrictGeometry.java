package com.parkio.parking.externalsource.district;

import java.util.List;
import java.util.Objects;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.prep.PreparedGeometry;

/**
 * Immutable district geometry for point-in-polygon assignment.
 *
 * <p>DATA-WP-19 topology mode uses JTS {@link PreparedGeometry#covers}. Legacy DATA-WP-18 mode
 * retains even-odd ring membership (known to false-positive on some valid İzBB polygons).
 */
public final class MunicipalDistrictGeometry {
    private final String districtName;
    private final String foldedName;
    private final List<RingSet> polygons;
    private final PreparedGeometry prepared;
    private final boolean topologyMode;
    private final double south;
    private final double north;
    private final double west;
    private final double east;

    /** Legacy ray-casting constructor (DATA-WP-18 rollback path). */
    public MunicipalDistrictGeometry(String districtName, String foldedName, List<RingSet> polygons) {
        this(districtName, foldedName, polygons, null, false);
    }

    /** Topology constructor backed by prepared JTS geometry (DATA-WP-19). */
    public MunicipalDistrictGeometry(
            String districtName, String foldedName, PreparedGeometry prepared) {
        this(districtName, foldedName, List.of(), Objects.requireNonNull(prepared, "prepared"), true);
    }

    private MunicipalDistrictGeometry(
            String districtName,
            String foldedName,
            List<RingSet> polygons,
            PreparedGeometry prepared,
            boolean topologyMode) {
        this.districtName = Objects.requireNonNull(districtName, "districtName");
        this.foldedName = Objects.requireNonNull(foldedName, "foldedName");
        this.polygons = List.copyOf(Objects.requireNonNull(polygons, "polygons"));
        this.prepared = prepared;
        this.topologyMode = topologyMode;
        if (topologyMode) {
            var env = prepared.getGeometry().getEnvelopeInternal();
            this.south = env.getMinY();
            this.north = env.getMaxY();
            this.west = env.getMinX();
            this.east = env.getMaxX();
        } else {
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
    }

    public String districtName() {
        return districtName;
    }

    public String foldedName() {
        return foldedName;
    }

    public boolean topologyMode() {
        return topologyMode;
    }

    public boolean covers(double longitude, double latitude) {
        if (!Double.isFinite(longitude) || !Double.isFinite(latitude)) {
            return false;
        }
        if (latitude < south || latitude > north || longitude < west || longitude > east) {
            return false;
        }
        if (topologyMode) {
            Point p = MunicipalDistrictJtsFactory.point(longitude, latitude);
            return prepared.covers(p);
        }
        for (RingSet poly : polygons) {
            if (poly.covers(longitude, latitude)) {
                return true;
            }
        }
        return false;
    }

    /** True when the point lies on the district boundary (topology mode only; else false). */
    public boolean onBoundaryOnly(double longitude, double latitude) {
        if (!topologyMode || !covers(longitude, latitude)) {
            return false;
        }
        Point p = MunicipalDistrictJtsFactory.point(longitude, latitude);
        return prepared.getGeometry().getBoundary().distance(p) <= 1e-12
                || prepared.getGeometry().touches(p);
    }

    /** One polygon: exterior + holes (legacy path). */
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
