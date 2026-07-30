package com.parkio.parking.externalsource.osm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.parking.externalsource.MunicipalAccessClassification;
import com.parkio.parking.externalsource.MunicipalFacilityType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Parses Parkio normalized OSM parking GeoJSON interchange (not PBF). */
public class OsmGeoJsonParkingParser {
    public static final String IMPORT_FORMAT_VERSION = "osm-parking-geojson-v1";

    private final ObjectMapper mapper;

    public OsmGeoJsonParkingParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public List<OsmParkingFeature> parse(byte[] bytes) {
        try {
            JsonNode root = mapper.readTree(bytes);
            if (root == null || !"FeatureCollection".equals(root.path("type").asText())) {
                throw new IllegalArgumentException("expected FeatureCollection");
            }
            String version = root.path("parkioImportVersion").asText(IMPORT_FORMAT_VERSION);
            if (!IMPORT_FORMAT_VERSION.equals(version)) {
                throw new IllegalArgumentException("unsupported import format: " + version);
            }
            List<OsmParkingFeature> out = new ArrayList<>();
            for (JsonNode feature : root.path("features")) {
                out.add(parseFeature(feature));
            }
            return out;
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("invalid OSM GeoJSON: " + ex.getMessage(), ex);
        }
    }

    private OsmParkingFeature parseFeature(JsonNode feature) {
        JsonNode props = feature.path("properties");
        Map<String, String> tags = readTags(props);
        try {
            OsmElementType type = resolveType(feature, props);
            long osmId = resolveId(feature, props, type);
            String externalId = OsmExternalId.of(type, osmId);
            String amenity = first(tags.get("amenity"), props.path("amenity").asText(""));
            if ("parking_space".equalsIgnoreCase(amenity)) {
                return rejected(externalId, type, osmId, "parking_space_not_facility");
            }
            if (!"parking".equalsIgnoreCase(amenity)) {
                return rejected(externalId, type, osmId, "not_amenity_parking");
            }
            double[] centroid = centroid(feature.path("geometry"));
            if (centroid == null) {
                return rejected(externalId, type, osmId, "geometry_invalid");
            }
            double lng = centroid[0];
            double lat = centroid[1];
            if (!IzmirClip.contains(lat, lng)) {
                return rejected(externalId, type, osmId, "outside_izmir_clip");
            }
            MunicipalAccessClassification access = OsmAccessMapper.map(tags.get("access"));
            Integer capacity = OsmCapacityParser.parse(tags.get("capacity"));
            Boolean fee = parseFee(tags.get("fee"));
            MunicipalFacilityType facilityType =
                    OsmParkingTypeMapper.map(tags.get("parking"), tags.get("building"));
            Map<String, String> allowlisted = OsmTagAllowlist.filter(tags);
            String hash = sha256(externalId + "|" + allowlisted + "|" + lat + "|" + lng + "|" + capacity);
            return new OsmParkingFeature(
                    externalId, type, osmId,
                    blankToNull(tags.get("name")),
                    blankToNull(tags.get("operator")),
                    blankToNull(tags.get("brand")),
                    facilityType, access, capacity, fee,
                    blankToNull(tags.get("opening_hours")),
                    lat, lng, feature.path("geometry").path("type").asText("Unknown"),
                    allowlisted, hash, true, null);
        } catch (RuntimeException ex) {
            return rejected("unknown", OsmElementType.NODE, 0, "identity_invalid");
        }
    }

    private static OsmElementType resolveType(JsonNode feature, JsonNode props) {
        if (props.hasNonNull("osmType")) {
            return OsmElementType.fromWire(props.path("osmType").asText());
        }
        String id = feature.path("id").asText("");
        if (id.contains("/")) {
            return OsmElementType.fromWire(id.substring(0, id.indexOf('/')));
        }
        throw new IllegalArgumentException("osmType missing");
    }

    private static long resolveId(JsonNode feature, JsonNode props, OsmElementType type) {
        if (props.hasNonNull("osmId")) {
            return props.path("osmId").asLong();
        }
        String id = feature.path("id").asText("");
        if (id.contains("/")) {
            return OsmExternalId.idOf(id);
        }
        throw new IllegalArgumentException("osmId missing");
    }

    private OsmParkingFeature rejected(String externalId, OsmElementType type, long osmId, String reason) {
        return new OsmParkingFeature(externalId, type, osmId, null, null, null,
                MunicipalFacilityType.UNKNOWN, MunicipalAccessClassification.UNKNOWN,
                null, null, null, 0, 0, "Invalid", Map.of(), "", false, reason);
    }

    private static Map<String, String> readTags(JsonNode props) {
        Map<String, String> tags = new LinkedHashMap<>();
        JsonNode tagsNode = props.path("tags");
        if (tagsNode.isObject()) {
            Iterator<String> names = tagsNode.fieldNames();
            while (names.hasNext()) {
                String key = names.next();
                tags.put(key, tagsNode.path(key).asText(""));
            }
        }
        for (String key : OsmTagAllowlist.KEYS) {
            if (props.hasNonNull(key) && !tags.containsKey(key)) {
                tags.put(key, props.path(key).asText(""));
            }
        }
        return tags;
    }

    private static double[] centroid(JsonNode geometry) {
        if (geometry == null || geometry.isMissingNode()) {
            return null;
        }
        String type = geometry.path("type").asText();
        JsonNode coords = geometry.path("coordinates");
        return switch (type) {
            case "Point" -> point(coords);
            case "Polygon" -> polygonCentroid(coords);
            case "MultiPolygon" -> (!coords.isArray() || coords.isEmpty()) ? null : polygonCentroid(coords.get(0));
            default -> null;
        };
    }

    private static double[] point(JsonNode coords) {
        if (!coords.isArray() || coords.size() < 2) {
            return null;
        }
        double lng = coords.get(0).asDouble();
        double lat = coords.get(1).asDouble();
        if (!Double.isFinite(lat) || !Double.isFinite(lng) || Math.abs(lat) > 90 || Math.abs(lng) > 180) {
            return null;
        }
        return new double[] {lng, lat};
    }

    private static double[] polygonCentroid(JsonNode polygon) {
        if (!polygon.isArray() || polygon.isEmpty()) {
            return null;
        }
        JsonNode ring = polygon.get(0);
        if (!ring.isArray() || ring.size() < 3) {
            return null;
        }
        double sumLat = 0;
        double sumLng = 0;
        int n = 0;
        for (JsonNode c : ring) {
            double[] p = point(c);
            if (p == null) {
                return null;
            }
            sumLng += p[0];
            sumLat += p[1];
            n++;
        }
        return n == 0 ? null : new double[] {sumLng / n, sumLat / n};
    }

    private static Boolean parseFee(String fee) {
        if (fee == null || fee.isBlank()) {
            return null;
        }
        return switch (fee.trim().toLowerCase(Locale.ROOT)) {
            case "yes", "true" -> Boolean.TRUE;
            case "no", "false" -> Boolean.FALSE;
            default -> null;
        };
    }

    private static String first(String a, String b) {
        return a == null || a.isBlank() ? b : a;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}