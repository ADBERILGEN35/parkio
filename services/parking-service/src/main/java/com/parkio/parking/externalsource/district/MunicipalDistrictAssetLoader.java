package com.parkio.parking.externalsource.district;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.parking.externalsource.osm.IzmirBoundaryAssetValidator;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Loads and validates the WP-08 İzmir district FeatureCollection into immutable geometries.
 * Does not write files or mutate the source asset.
 */
public final class MunicipalDistrictAssetLoader {
    private final ObjectMapper mapper;
    private final IzmirBoundaryAssetValidator validator;

    public MunicipalDistrictAssetLoader(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.validator = new IzmirBoundaryAssetValidator(mapper);
    }

    public LoadedAsset load(Path path, String expectedSha256, String nameProperty, int expectedCount)
            throws Exception {
        Objects.requireNonNull(path, "path");
        return load(Files.readAllBytes(path), expectedSha256, nameProperty, expectedCount);
    }

    public LoadedAsset load(byte[] bytes, String expectedSha256, String nameProperty, int expectedCount) {
        Objects.requireNonNull(bytes, "bytes");
        var validation = validator.validate(bytes, expectedSha256);
        if (!validation.accepted()) {
            return LoadedAsset.invalid(validation.sourceSha256());
        }
        try {
            JsonNode root = mapper.readTree(new String(bytes, StandardCharsets.UTF_8));
            String resolvedName = nameProperty == null || nameProperty.isBlank()
                    ? MunicipalDistrictCoveragePolicy.DEFAULT_NAME_PROPERTY
                    : nameProperty.trim();
            List<MunicipalDistrictGeometry> districts = new ArrayList<>();
            for (JsonNode feature : root.path("features")) {
                String rawName = feature.path("properties").path(resolvedName).asText("").trim();
                if (rawName.isEmpty()) {
                    return LoadedAsset.invalid(validation.sourceSha256());
                }
                List<MunicipalDistrictGeometry.RingSet> polygons =
                        parsePolygons(feature.path("geometry"));
                if (polygons.isEmpty()) {
                    return LoadedAsset.invalid(validation.sourceSha256());
                }
                districts.add(new MunicipalDistrictGeometry(
                        rawName,
                        IzmirBoundaryAssetValidator.foldDistrictName(rawName),
                        polygons));
            }
            districts.sort(Comparator.comparing(MunicipalDistrictGeometry::foldedName));
            if (expectedCount > 0 && districts.size() != expectedCount) {
                return LoadedAsset.invalid(validation.sourceSha256());
            }
            return LoadedAsset.valid(validation.sourceSha256(), List.copyOf(districts));
        } catch (Exception ex) {
            return LoadedAsset.invalid(validation.sourceSha256());
        }
    }

    private static List<MunicipalDistrictGeometry.RingSet> parsePolygons(JsonNode geometry) {
        List<MunicipalDistrictGeometry.RingSet> out = new ArrayList<>();
        if (geometry == null || geometry.isMissingNode() || geometry.isNull()) {
            return out;
        }
        String type = geometry.path("type").asText();
        JsonNode coordinates = geometry.path("coordinates");
        if ("Polygon".equals(type)) {
            out.addAll(expandPolygonCoords(coordinates));
        } else if ("MultiPolygon".equals(type)) {
            for (JsonNode poly : coordinates) {
                out.addAll(expandPolygonCoords(poly));
            }
        }
        return out;
    }

    /**
     * Exterior is ring 0. Subsequent rings inside the exterior are holes; rings whose sample
     * point lies outside the exterior are promoted to separate exterior polygons (WP-08 island
     * repair).
     */
    static List<MunicipalDistrictGeometry.RingSet> expandPolygonCoords(JsonNode polygonCoords) {
        List<MunicipalDistrictGeometry.RingSet> out = new ArrayList<>();
        if (polygonCoords == null || !polygonCoords.isArray() || polygonCoords.isEmpty()) {
            return out;
        }
        double[][] exterior = toRing(polygonCoords.get(0));
        if (exterior == null || exterior.length < 4) {
            return out;
        }
        List<double[][]> holes = new ArrayList<>();
        for (int ri = 1; ri < polygonCoords.size(); ri++) {
            double[][] ring = toRing(polygonCoords.get(ri));
            if (ring == null || ring.length < 4) {
                continue;
            }
            double[] sample = ring[ring.length / 2];
            if (!MunicipalDistrictGeometry.pointInRingStrict(sample[0], sample[1], exterior)
                    && !MunicipalDistrictGeometry.pointOnBoundary(sample[0], sample[1], exterior)) {
                out.add(new MunicipalDistrictGeometry.RingSet(ring, List.of()));
            } else {
                holes.add(ring);
            }
        }
        out.add(0, new MunicipalDistrictGeometry.RingSet(exterior, holes));
        return out;
    }

    private static double[][] toRing(JsonNode ring) {
        if (ring == null || !ring.isArray() || ring.size() < 4) {
            return null;
        }
        double[][] out = new double[ring.size()][2];
        for (int i = 0; i < ring.size(); i++) {
            JsonNode c = ring.get(i);
            if (c == null || !c.isArray() || c.size() < 2) {
                return null;
            }
            double x = c.get(0).asDouble(Double.NaN);
            double y = c.get(1).asDouble(Double.NaN);
            if (!Double.isFinite(x) || !Double.isFinite(y)) {
                return null;
            }
            out[i][0] = x;
            out[i][1] = y;
        }
        return out;
    }

    public record LoadedAsset(boolean valid, String sourceSha256, List<MunicipalDistrictGeometry> districts) {
        public static LoadedAsset valid(String sha, List<MunicipalDistrictGeometry> districts) {
            return new LoadedAsset(true, sha, List.copyOf(districts));
        }

        public static LoadedAsset invalid(String sha) {
            return new LoadedAsset(false, sha == null ? "" : sha, List.of());
        }
    }
}
