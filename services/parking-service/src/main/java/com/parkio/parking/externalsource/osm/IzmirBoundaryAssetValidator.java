package com.parkio.parking.externalsource.osm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Validates official İzmir district boundary GeoJSON before dissolve/clip use.
 * Does not mutate the source bytes.
 */
public final class IzmirBoundaryAssetValidator {
    public static final Set<String> EXPECTED_DISTRICTS = Set.of(
            "ALIAGA", "BALCOVA", "BAYINDIR", "BAYRAKLI", "BERGAMA", "BEYDAG", "BORNOVA", "BUCA",
            "CESME", "CIGLI", "DIKILI", "FOCA", "GAZIEMIR", "GUZELBAHCE", "KARABAGLAR", "KARABURUN",
            "KARSIYAKA", "KEMALPASA", "KINIK", "KIRAZ", "KONAK", "MENDERES", "MENEMEN", "NARLIDERE",
            "ODEMIS", "SEFERIHISAR", "SELCUK", "TIRE", "TORBALI", "URLA");

    public static final double PLAUSIBLE_WEST = 26.0;
    public static final double PLAUSIBLE_EAST = 28.7;
    public static final double PLAUSIBLE_SOUTH = 37.7;
    public static final double PLAUSIBLE_NORTH = 39.5;

    private final ObjectMapper mapper;

    public IzmirBoundaryAssetValidator(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    public ValidationResult validate(byte[] sourceBytes, String expectedSha256) {
        Objects.requireNonNull(sourceBytes, "sourceBytes");
        List<String> errors = new ArrayList<>();
        String sha = sha256(sourceBytes);
        if (expectedSha256 != null && !expectedSha256.isBlank()
                && !expectedSha256.equalsIgnoreCase(sha)) {
            errors.add("checksum_mismatch");
            return ValidationResult.rejected(sha, errors, List.of(), Map.of());
        }
        final JsonNode root;
        try {
            String text = new String(sourceBytes, StandardCharsets.UTF_8);
            root = mapper.readTree(text);
        } catch (Exception ex) {
            errors.add("invalid_json");
            return ValidationResult.rejected(sha, errors, List.of(), Map.of());
        }
        if (root == null || !"FeatureCollection".equals(root.path("type").asText())) {
            errors.add("expected_feature_collection");
            return ValidationResult.rejected(sha, errors, List.of(), Map.of());
        }
        JsonNode features = root.path("features");
        if (!features.isArray() || features.isEmpty()) {
            errors.add("empty_feature_collection");
            return ValidationResult.rejected(sha, errors, List.of(), Map.of());
        }

        String nameField = detectNameField(features);
        if (nameField == null) {
            errors.add("missing_district_name_field");
        }

        List<String> names = new ArrayList<>();
        Map<String, Integer> duplicates = new LinkedHashMap<>();
        Set<String> seenFolded = new LinkedHashSet<>();
        List<String> geometryIssues = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int featureCount = 0;

        for (JsonNode feature : features) {
            featureCount++;
            JsonNode geometry = feature.path("geometry");
            JsonNode props = feature.path("properties");
            String rawName = nameField == null ? "" : props.path(nameField).asText("").trim();
            if (rawName.isEmpty()) {
                errors.add("missing_district_name");
            } else {
                names.add(rawName);
                String folded = foldDistrictName(rawName);
                if (!seenFolded.add(folded)) {
                    duplicates.merge(rawName, 1, Integer::sum);
                    errors.add("duplicate_district:" + rawName);
                }
            }
            validateGeometry(
                    geometry,
                    rawName.isEmpty() ? ("feature#" + featureCount) : rawName,
                    geometryIssues,
                    warnings);
        }
        errors.addAll(geometryIssues);

        Set<String> actualFolded = new TreeSet<>();
        for (String n : names) {
            actualFolded.add(foldDistrictName(n));
        }
        List<String> missing = new ArrayList<>();
        for (String expected : EXPECTED_DISTRICTS) {
            if (!actualFolded.contains(expected)) {
                missing.add(expected);
            }
        }
        List<String> extra = new ArrayList<>();
        for (String actual : actualFolded) {
            if (!EXPECTED_DISTRICTS.contains(actual)) {
                extra.add(actual);
                errors.add("foreign_or_unexpected_district:" + actual);
            }
        }
        if (!missing.isEmpty()) {
            errors.add("missing_expected_districts:" + String.join(",", missing));
        }
        if (featureCount != EXPECTED_DISTRICTS.size()) {
            errors.add("district_count_mismatch:expected="
                    + EXPECTED_DISTRICTS.size() + ",actual=" + featureCount);
        }

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("featureCount", featureCount);
        details.put("districtNameField", nameField == null ? "" : nameField);
        details.put("districtNamesSorted", names.stream().sorted(String::compareTo).toList());
        details.put("missing", missing);
        details.put("extra", extra);
        details.put("duplicates", duplicates);
        details.put("geometryIssueCount", geometryIssues.size());
        details.put("warnings", warnings);

        if (!errors.isEmpty()) {
            return ValidationResult.rejected(sha, errors, names, details);
        }
        return ValidationResult.accepted(sha, names, details);
    }

    public ValidationResult validateFile(Path path, String expectedSha256) throws IOException {
        return validate(Files.readAllBytes(path), expectedSha256);
    }

    private static String detectNameField(JsonNode features) {
        for (String candidate : List.of("adi", "name", "NAME", "ilce_adi", "ILCE_ADI")) {
            boolean allPresent = true;
            for (JsonNode feature : features) {
                JsonNode v = feature.path("properties").path(candidate);
                if (v.isMissingNode() || v.isNull() || v.asText("").isBlank()) {
                    allPresent = false;
                    break;
                }
            }
            if (allPresent) {
                return candidate;
            }
        }
        return null;
    }

    private static void validateGeometry(
            JsonNode geometry, String label, List<String> issues, List<String> warnings) {
        if (geometry == null || geometry.isMissingNode() || geometry.isNull()) {
            issues.add("empty_geometry:" + label);
            return;
        }
        String type = geometry.path("type").asText();
        if (!"Polygon".equals(type) && !"MultiPolygon".equals(type)) {
            issues.add("non_polygon_geometry:" + label + ":" + type);
            return;
        }
        JsonNode coordinates = geometry.path("coordinates");
        if (!coordinates.isArray() || coordinates.isEmpty()) {
            issues.add("empty_geometry:" + label);
            return;
        }
        List<double[][]> rings = new ArrayList<>();
        if ("Polygon".equals(type)) {
            collectPolygonRings(coordinates, rings, label, issues, warnings);
        } else {
            for (JsonNode poly : coordinates) {
                collectPolygonRings(poly, rings, label, issues, warnings);
            }
        }
        if (rings.isEmpty()) {
            issues.add("empty_geometry:" + label);
        }
    }

    private static void collectPolygonRings(
            JsonNode polygonCoords,
            List<double[][]> out,
            String label,
            List<String> issues,
            List<String> warnings) {
        if (polygonCoords == null || !polygonCoords.isArray() || polygonCoords.isEmpty()) {
            issues.add("empty_geometry:" + label);
            return;
        }
        for (int ri = 0; ri < polygonCoords.size(); ri++) {
            JsonNode ring = polygonCoords.get(ri);
            if (ring == null || ring.size() < 4) {
                issues.add("invalid_ring:" + label + ":ring" + ri);
                continue;
            }
            double[] first = coord(ring.get(0));
            double[] last = coord(ring.get(ring.size() - 1));
            if (first == null || last == null) {
                issues.add("non_finite_coordinate:" + label);
                continue;
            }
            if (first[0] != last[0] || first[1] != last[1]) {
                issues.add("ring_not_closed:" + label + ":ring" + ri);
            }
            for (JsonNode c : ring) {
                double[] xy = coord(c);
                if (xy == null) {
                    issues.add("non_finite_coordinate:" + label);
                    break;
                }
                if (xy[0] < PLAUSIBLE_WEST || xy[0] > PLAUSIBLE_EAST
                        || xy[1] < PLAUSIBLE_SOUTH || xy[1] > PLAUSIBLE_NORTH) {
                    issues.add("implausible_crs_or_bounds:" + label);
                    break;
                }
            }
            // Repairable source defect: island rings incorrectly nested as Polygon holes.
            if (ri > 0) {
                double[] sample = coord(ring.get(ring.size() / 2));
                double[][] exterior = toRing(polygonCoords.get(0));
                if (sample != null && exterior != null && !pointInRing(sample[0], sample[1], exterior)) {
                    warnings.add("island_ring_encoded_as_hole:" + label + ":ring" + ri);
                }
            }
            out.add(toRing(ring));
        }
    }

    private static double[] coord(JsonNode c) {
        if (c == null || !c.isArray() || c.size() < 2) {
            return null;
        }
        double x = c.get(0).asDouble(Double.NaN);
        double y = c.get(1).asDouble(Double.NaN);
        if (!Double.isFinite(x) || !Double.isFinite(y)) {
            return null;
        }
        return new double[] {x, y};
    }

    private static double[][] toRing(JsonNode ring) {
        if (ring == null || !ring.isArray()) {
            return null;
        }
        double[][] out = new double[ring.size()][2];
        for (int i = 0; i < ring.size(); i++) {
            double[] xy = coord(ring.get(i));
            if (xy == null) {
                return null;
            }
            out[i] = xy;
        }
        return out;
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

    /** Turkish-aware uppercase then ASCII fold for comparison only. */
    public static String foldDistrictName(String raw) {
        String upper = raw == null ? "" : raw.trim().toUpperCase(Locale.forLanguageTag("tr-TR"));
        StringBuilder sb = new StringBuilder(upper.length());
        for (int i = 0; i < upper.length(); i++) {
            char ch = upper.charAt(i);
            switch (ch) {
                case 'İ', 'I', 'ı' -> sb.append('I');
                case 'Ğ', 'ğ' -> sb.append('G');
                case 'Ü', 'ü' -> sb.append('U');
                case 'Ş', 'ş' -> sb.append('S');
                case 'Ö', 'ö' -> sb.append('O');
                case 'Ç', 'ç' -> sb.append('C');
                case 'Â', 'â' -> sb.append('A');
                case 'Î', 'î' -> sb.append('I');
                case 'Û', 'û' -> sb.append('U');
                default -> sb.append(ch);
            }
        }
        return sb.toString().replaceAll("\\s+", "");
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    public record ValidationResult(
            boolean accepted,
            String sourceSha256,
            List<String> errors,
            List<String> districtNames,
            Map<String, Object> details) {
        public static ValidationResult accepted(String sha, List<String> names, Map<String, Object> details) {
            return new ValidationResult(true, sha, List.of(), List.copyOf(names), Map.copyOf(details));
        }

        public static ValidationResult rejected(
                String sha, List<String> errors, List<String> names, Map<String, Object> details) {
            return new ValidationResult(false, sha, List.copyOf(errors), List.copyOf(names), Map.copyOf(details));
        }
    }
}
