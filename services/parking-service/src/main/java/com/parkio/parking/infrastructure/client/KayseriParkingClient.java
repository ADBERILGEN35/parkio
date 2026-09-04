package com.parkio.parking.infrastructure.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.parkio.parking.infrastructure.config.MunicipalSourceProperties;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Read-only GeoJSON client for Kayseri Büyükşehir Belediyesi open-data parking inventory.
 *
 * <p>Prefers the official UTF-8 GeoJSON resource (CSV export mangles Turkish characters).
 * Flattens FeatureCollection features into a JSON array of property objects for the adapter.
 */
@Component
public class KayseriParkingClient {
    public static final Set<String> REQUIRED_FIELDS = Set.of("CBNO", "ADI", "lat_DD", "lon_DD");

    private final RestClient client;
    private final ObjectMapper objectMapper;
    private final MunicipalSourceProperties.Kayseri config;

    public KayseriParkingClient(
            RestClient.Builder builder, ObjectMapper objectMapper, MunicipalSourceProperties properties) {
        this.objectMapper = objectMapper;
        this.config = properties.getKayseri();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(config.getConnectTimeout());
        factory.setReadTimeout(config.getReadTimeout());
        this.client = builder.baseUrl(config.getBaseUrl())
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.USER_AGENT, config.getUserAgent())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    public JsonNode fetch() {
        int attempt = 0;
        while (true) {
            try {
                byte[] body = client.get().uri(config.getPath()).retrieve().body(byte[].class);
                if (body == null || body.length == 0) {
                    throw new IllegalStateException("Kayseri GeoJSON response empty");
                }
                // Explicit UTF-8 decode — never rely on platform default (CSV mojibake risk).
                JsonNode root = objectMapper.readTree(new String(body, StandardCharsets.UTF_8));
                return flattenFeatures(root);
            } catch (HttpClientErrorException ex) {
                throw ex;
            } catch (RestClientException ex) {
                if (attempt++ >= config.getMaxRetries() || !transientFailure(ex)) {
                    throw ex;
                }
                try {
                    Thread.sleep(Math.min(1000L, 100L * attempt));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw ex;
                }
            } catch (java.io.IOException ex) {
                throw new IllegalStateException("Kayseri GeoJSON parse failed", ex);
            }
        }
    }

    /**
     * Convert FeatureCollection → flat property array. Coordinates prefer {@code lat_DD}/{@code lon_DD};
     * when absent, Point geometry {@code [lon, lat]} is promoted into those fields.
     */
    public JsonNode flattenFeatures(JsonNode root) {
        if (root == null || !root.isObject()) {
            throw new IllegalStateException("Kayseri payload is not a GeoJSON object");
        }
        JsonNode features = root.path("features");
        if (!features.isArray()) {
            throw new IllegalStateException("Kayseri GeoJSON missing features array");
        }
        if (features.size() > config.getMaxRecords()) {
            throw new IllegalStateException("Kayseri GeoJSON feature count exceeds configured max-records");
        }
        ArrayNode out = objectMapper.createArrayNode();
        for (JsonNode feature : features) {
            if (feature == null || !feature.isObject()) {
                continue;
            }
            JsonNode properties = feature.path("properties");
            ObjectNode row = properties.isObject()
                    ? ((ObjectNode) properties).deepCopy()
                    : objectMapper.createObjectNode();
            if (!row.hasNonNull("lat_DD") || !row.hasNonNull("lon_DD")) {
                JsonNode geometry = feature.path("geometry");
                if (geometry.path("type").asText("").equalsIgnoreCase("Point")
                        && geometry.path("coordinates").isArray()
                        && geometry.path("coordinates").size() >= 2) {
                    JsonNode coords = geometry.path("coordinates");
                    // GeoJSON Point is [longitude, latitude]
                    if (!row.hasNonNull("lon_DD")) {
                        row.put("lon_DD", coords.get(0).asDouble());
                    }
                    if (!row.hasNonNull("lat_DD")) {
                        row.put("lat_DD", coords.get(1).asDouble());
                    }
                }
            }
            out.add(row);
        }
        return out;
    }

    private static boolean transientFailure(RestClientException ex) {
        if (ex instanceof RestClientResponseException response) {
            return response.getStatusCode().is5xxServerError();
        }
        Throwable cause = ex;
        while (cause != null) {
            if (cause instanceof SocketTimeoutException) {
                return true;
            }
            cause = cause.getCause();
        }
        return true;
    }
}
