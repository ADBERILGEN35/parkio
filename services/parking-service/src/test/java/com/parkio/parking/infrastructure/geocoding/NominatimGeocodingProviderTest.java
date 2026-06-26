package com.parkio.parking.infrastructure.geocoding;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.parking.application.geocoding.GeocodeResult;
import com.parkio.parking.infrastructure.config.GeocodingProperties;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * Wire-level tests for {@link NominatimGeocodingProvider} against a local HTTP
 * server: the request carries the descriptive User-Agent and the expected query
 * params, and the response is mapped to the frontend {@code GeocodeResult}
 * contract exactly (primary/secondary/id derivation, invalid coords skipped).
 *
 * <p>Constructed directly (no Spring), so the Resilience4j annotations are not in
 * play here — that wiring is exercised by the running service; these tests pin the
 * HTTP contract and the mapping.
 */
class NominatimGeocodingProviderTest {

    private HttpServer server;
    private final AtomicReference<String> recordedQuery = new AtomicReference<>();
    private final AtomicReference<String> recordedUserAgent = new AtomicReference<>();
    private volatile String responseBody = "[]";

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/search", exchange -> {
            recordedQuery.set(exchange.getRequestURI().getQuery());
            recordedUserAgent.set(exchange.getRequestHeaders().getFirst("User-Agent"));
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private NominatimGeocodingProvider provider() {
        GeocodingProperties props = new GeocodingProperties();
        props.getProvider().setBaseUrl("http://localhost:" + server.getAddress().getPort());
        props.getProvider().setConnectTimeout(Duration.ofSeconds(1));
        props.getProvider().setReadTimeout(Duration.ofSeconds(2));
        return new NominatimGeocodingProvider(RestClient.builder(), props);
    }

    @Test
    void mapsResultUsingNameAsPrimaryAndCityDistrictPlusCityAsSecondary() {
        responseBody = """
                [{"place_id":123,"name":"Konak Pier",
                  "display_name":"Konak Pier, Konak, İzmir, Türkiye",
                  "lat":"38.42","lon":"27.14",
                  "address":{"city_district":"Konak","city":"İzmir"}}]
                """;

        List<GeocodeResult> results = provider().search("Konak Pier", 5);

        assertThat(results).hasSize(1);
        GeocodeResult result = results.get(0);
        assertThat(result.id()).isEqualTo("123");
        assertThat(result.primary()).isEqualTo("Konak Pier");
        assertThat(result.secondary()).isEqualTo("Konak, İzmir");
        assertThat(result.displayName()).isEqualTo("Konak Pier, Konak, İzmir, Türkiye");
        assertThat(result.lat()).isEqualTo(38.42);
        assertThat(result.lng()).isEqualTo(27.14);
    }

    @Test
    void fallsBackToRoadAsPrimaryWhenNameIsAbsent() {
        responseBody = """
                [{"place_id":5,"display_name":"Atatürk Caddesi, Bornova, İzmir",
                  "lat":"38.46","lon":"27.21",
                  "address":{"road":"Atatürk Caddesi","city_district":"Bornova","city":"İzmir"}}]
                """;

        List<GeocodeResult> results = provider().search("Ataturk", 5);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).primary()).isEqualTo("Atatürk Caddesi");
        assertThat(results.get(0).secondary()).isEqualTo("Bornova, İzmir");
    }

    @Test
    void skipsItemsWithNonNumericCoordinates() {
        responseBody = """
                [{"place_id":1,"display_name":"Bad","lat":"not-a-number","lon":"27.0"},
                 {"place_id":2,"name":"Good","display_name":"Good","lat":"38.0","lon":"27.0"}]
                """;

        List<GeocodeResult> results = provider().search("mix", 5);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).id()).isEqualTo("2");
    }

    @Test
    void emptyProviderResponseYieldsEmptyList() {
        responseBody = "[]";
        assertThat(provider().search("nothing", 5)).isEmpty();
    }

    @Test
    void sendsDescriptiveUserAgentAndExpectedQueryParams() {
        responseBody = "[]";

        provider().search("Konak Pier", 7);

        assertThat(recordedUserAgent.get()).contains("Parkio");
        String query = recordedQuery.get();
        assertThat(query).contains("format=jsonv2");
        assertThat(query).contains("addressdetails=1");
        assertThat(query).contains("limit=7");
        assertThat(query).contains("countrycodes=tr");
    }
}
