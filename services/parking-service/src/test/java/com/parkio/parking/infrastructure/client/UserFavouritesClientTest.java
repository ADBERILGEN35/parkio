package com.parkio.parking.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.parking.application.recommendation.ranking.RankingMetrics;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class UserFavouritesClientTest {

    private static final String SECRET = "test-internal-secret";
    private static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID FACILITY = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

    private HttpServer server;
    private final AtomicReference<RecordedRequest> recorded = new AtomicReference<>();
    private volatile int responseStatus = 200;
    private volatile String responseBody = "{}";

    private record RecordedRequest(String path, String query, String gatewayAuth, String userId) {}

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            recorded.set(new RecordedRequest(
                    exchange.getRequestURI().getPath(),
                    exchange.getRequestURI().getRawQuery(),
                    exchange.getRequestHeaders().getFirst("X-Gateway-Auth"),
                    exchange.getRequestHeaders().getFirst("X-User-Id")));
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus, body.length);
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

    private UserFavouritesClient client() {
        return new UserFavouritesClient(
                RestClient.builder(),
                new RankingMetrics(new SimpleMeterRegistry()),
                "http://localhost:" + server.getAddress().getPort(),
                SECRET,
                Duration.ofSeconds(1),
                Duration.ofSeconds(2));
    }

    @Test
    void returnsFavouritedIdsOnSuccess() {
        responseBody = "{\"favouritedTargetIds\":[\"" + FACILITY + "\"]}";

        Set<UUID> result = client().favouritedMunicipalFacilityIds(USER, Set.of(FACILITY));

        assertThat(result).containsExactly(FACILITY);
        RecordedRequest request = recorded.get();
        assertThat(request.path()).isEqualTo("/api/v1/places/favourites/parking/status");
        assertThat(request.query()).contains("targetIds=" + FACILITY);
        assertThat(request.gatewayAuth()).isEqualTo(SECRET);
        assertThat(request.userId()).isEqualTo(USER.toString());
    }

    @Test
    void failsOpenOnServerError() {
        responseStatus = 500;
        responseBody = "{\"code\":\"INTERNAL_ERROR\"}";

        Set<UUID> result = client().favouritedMunicipalFacilityIds(USER, Set.of(FACILITY));

        assertThat(result).isEmpty();
    }

    @Test
    void emptyInputReturnsEmptyWithoutCall() {
        Set<UUID> result = client().favouritedMunicipalFacilityIds(USER, Set.of());
        assertThat(result).isEmpty();
        assertThat(recorded.get()).isNull();
    }
}
