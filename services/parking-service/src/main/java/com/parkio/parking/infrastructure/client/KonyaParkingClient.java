package com.parkio.parking.infrastructure.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.parkio.parking.infrastructure.config.MunicipalSourceProperties;
import java.net.SocketTimeoutException;
import java.util.HashSet;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Read-only CKAN datastore client for Konya Büyükşehir Belediyesi open data.
 * Fetches all pages safely; partial pagination failure fails the whole fetch.
 */
@Component
public class KonyaParkingClient {
    public static final Set<String> REQUIRED_FIELDS = Set.of(
            "_id",
            "bolgeadi",
            "bolgeadresi",
            "bolgekapasite",
            "peronadi",
            "peronadres",
            "peronkapasite",
            "peronkoordinat",
            "peronacilissaati",
            "peronkapanissaati");

    private final RestClient client;
    private final ObjectMapper objectMapper;
    private final MunicipalSourceProperties.Konya config;

    public KonyaParkingClient(
            RestClient.Builder builder, ObjectMapper objectMapper, MunicipalSourceProperties properties) {
        this.objectMapper = objectMapper;
        this.config = properties.getKonya();
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
        ArrayNode allRecords = objectMapper.createArrayNode();
        int offset = 0;
        int pageSize = Math.max(1, config.getPageSize());
        int maxPages = Math.max(1, config.getMaxPages());
        Integer expectedTotal = null;
        Set<Integer> seenOffsets = new HashSet<>();

        for (int page = 0; page < maxPages; page++) {
            if (!seenOffsets.add(offset)) {
                throw new IllegalStateException("Konya CKAN pagination repeated offset " + offset);
            }
            JsonNode response = fetchPage(offset, pageSize);
            JsonNode result = response.path("result");
            if (!response.path("success").asBoolean(false)) {
                throw new IllegalStateException("Konya CKAN datastore_search failed");
            }
            if (expectedTotal == null) {
                expectedTotal = result.path("total").asInt(-1);
                if (expectedTotal < 0) {
                    throw new IllegalStateException("Konya CKAN response missing total");
                }
                if (expectedTotal > config.getMaxRecords()) {
                    throw new IllegalStateException("Konya CKAN total exceeds configured max-records");
                }
            }
            JsonNode records = result.path("records");
            if (!records.isArray()) {
                throw new IllegalStateException("Konya CKAN records payload is not an array");
            }
            records.forEach(allRecords::add);
            if (records.size() < pageSize || allRecords.size() >= expectedTotal) {
                break;
            }
            offset += pageSize;
        }

        if (expectedTotal != null && allRecords.size() != expectedTotal) {
            throw new IllegalStateException(
                    "Konya CKAN pagination incomplete: fetched=" + allRecords.size() + " expected=" + expectedTotal);
        }
        return allRecords;
    }

    private JsonNode fetchPage(int offset, int limit) {
        String uri = UriComponentsBuilder.fromPath(config.getPath())
                .queryParam("resource_id", config.getResourceId())
                .queryParam("limit", limit)
                .queryParam("offset", offset)
                .build()
                .toUriString();
        int attempt = 0;
        while (true) {
            try {
                return client.get().uri(uri).retrieve().body(JsonNode.class);
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
            }
        }
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
