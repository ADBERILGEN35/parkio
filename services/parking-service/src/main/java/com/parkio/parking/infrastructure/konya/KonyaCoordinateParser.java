package com.parkio.parking.infrastructure.konya;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Parses {@code peronkoordinat} strings from the Konya CKAN feed.
 *
 * <p>Observed upstream shapes:
 * <ul>
 *   <li>Point: {@code [lng, lat]}</li>
 *   <li>Line endpoints: {@code [[lng, lat], [lng, lat]]}</li>
 *   <li>Polygon ring: {@code [[[lng, lat], ...]]}</li>
 * </ul>
 * All numeric pairs are treated as WGS-84 with longitude first.
 */
@Component
public final class KonyaCoordinateParser {
    private final ObjectMapper objectMapper;

    public KonyaCoordinateParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<KonyaCoordinatePoint> parsePoints(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            JsonNode node = objectMapper.readTree(raw.trim());
            List<KonyaCoordinatePoint> points = new ArrayList<>();
            collectPoints(node, points);
            return List.copyOf(points);
        } catch (Exception ex) {
            return List.of();
        }
    }

    private static void collectPoints(JsonNode node, List<KonyaCoordinatePoint> out) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isArray()) {
            if (isCoordinatePair(node)) {
                double lng = node.get(0).asDouble();
                double lat = node.get(1).asDouble();
                out.add(new KonyaCoordinatePoint(lat, lng));
                return;
            }
            for (JsonNode child : node) {
                collectPoints(child, out);
            }
        }
    }

    private static boolean isCoordinatePair(JsonNode node) {
        return node.size() == 2 && node.get(0).isNumber() && node.get(1).isNumber();
    }

    public record KonyaCoordinatePoint(double latitude, double longitude) {}
}
