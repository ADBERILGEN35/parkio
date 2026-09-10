package com.parkio.parking.externalsource.district;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.PrecisionModel;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.locationtech.jts.geom.util.GeometryFixer;

/**
 * Builds OGC-faithful district geometries with JTS MakeValid / island promotion (DATA-WP-19).
 */
public final class MunicipalDistrictJtsFactory {
    private static final GeometryFactory GF = new GeometryFactory(new PrecisionModel(), 4326);

    private MunicipalDistrictJtsFactory() {}

    public static GeometryFactory geometryFactory() {
        return GF;
    }

    /**
     * Build a district MultiPolygon from GeoJSON polygon coordinate arrays.
     * Ring 0 is the exterior; subsequent rings are holes unless they lie outside the shell
     * (island-as-hole), in which case they are promoted to separate polygon components.
     */
    public static PreparedGeometry prepareDistrict(List<List<double[][]>> polygons) {
        Objects.requireNonNull(polygons, "polygons");
        List<Polygon> parts = new ArrayList<>();
        for (List<double[][]> polyRings : polygons) {
            if (polyRings == null || polyRings.isEmpty()) {
                continue;
            }
            parts.addAll(expandPolygon(polyRings));
        }
        if (parts.isEmpty()) {
            throw new IllegalArgumentException("empty district geometry");
        }
        Geometry geom;
        if (parts.size() == 1) {
            geom = parts.get(0);
        } else {
            geom = GF.createMultiPolygon(parts.toArray(Polygon[]::new));
        }
        Geometry fixed = GeometryFixer.fix(geom);
        if (fixed == null || fixed.isEmpty()) {
            throw new IllegalArgumentException("geometry fixer produced empty geometry");
        }
        Geometry normalized = asMultiPolygon(fixed).buffer(0);
        if (normalized == null || normalized.isEmpty()) {
            normalized = asMultiPolygon(fixed);
        }
        return PreparedGeometryFactory.prepare(asMultiPolygon(normalized));
    }

    static List<Polygon> expandPolygon(List<double[][]> rings) {
        List<Polygon> out = new ArrayList<>();
        double[][] exteriorCoords = rings.get(0);
        LinearRing shell = toRing(exteriorCoords);
        if (shell == null) {
            return out;
        }
        List<LinearRing> holes = new ArrayList<>();
        Polygon shellOnly = GF.createPolygon(shell);
        for (int i = 1; i < rings.size(); i++) {
            LinearRing hole = toRing(rings.get(i));
            if (hole == null) {
                continue;
            }
            Polygon holePoly = GF.createPolygon(hole);
            // Island-as-hole: hole not covered by shell → promote (WP-08 / PostGIS "Hole lies outside shell").
            if (!shellOnly.covers(holePoly.getCentroid()) && !shellOnly.contains(holePoly)) {
                out.add(holePoly);
            } else {
                holes.add(hole);
            }
        }
        out.add(0, GF.createPolygon(shell, holes.toArray(LinearRing[]::new)));
        return out;
    }

    private static LinearRing toRing(double[][] coords) {
        if (coords == null || coords.length < 4) {
            return null;
        }
        Coordinate[] c = new Coordinate[coords.length];
        for (int i = 0; i < coords.length; i++) {
            c[i] = new Coordinate(coords[i][0], coords[i][1]);
        }
        if (!c[0].equals2D(c[c.length - 1])) {
            Coordinate[] closed = new Coordinate[c.length + 1];
            System.arraycopy(c, 0, closed, 0, c.length);
            closed[c.length] = new Coordinate(c[0]);
            c = closed;
        }
        return GF.createLinearRing(c);
    }

    private static Geometry asMultiPolygon(Geometry g) {
        if (g instanceof MultiPolygon) {
            return g;
        }
        if (g instanceof Polygon polygon) {
            return GF.createMultiPolygon(new Polygon[] {polygon});
        }
        List<Polygon> polys = new ArrayList<>();
        for (int i = 0; i < g.getNumGeometries(); i++) {
            Geometry part = g.getGeometryN(i);
            if (part instanceof Polygon polygon) {
                polys.add(polygon);
            } else if (part instanceof MultiPolygon mp) {
                for (int j = 0; j < mp.getNumGeometries(); j++) {
                    polys.add((Polygon) mp.getGeometryN(j));
                }
            }
        }
        if (polys.isEmpty()) {
            return GF.createMultiPolygon();
        }
        return GF.createMultiPolygon(polys.toArray(Polygon[]::new));
    }

    public static Point point(double lon, double lat) {
        return GF.createPoint(new Coordinate(lon, lat));
    }
}
