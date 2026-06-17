package com.parkio.parking.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.parkio.parking.domain.exception.ParkingErrorCode;
import com.parkio.parking.domain.exception.ParkingException;
import com.parkio.parking.infrastructure.web.CorrelationIdFilter;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.web.client.RestClient;

/**
 * Wire-level tests for {@link MediaReadinessClient} against a local HTTP server: the
 * shared {@code X-Gateway-Auth} secret and bound {@code X-Correlation-Id} are sent,
 * a {@code READY} status passes, and every other outcome fails closed
 * (non-ready/404 → MEDIA_NOT_READY, transport failure → MEDIA_ACCESS_UNAVAILABLE).
 */
class MediaReadinessClientTest {

    private static final String SECRET = "test-internal-secret";

    private HttpServer server;
    private final AtomicReference<RecordedRequest> recorded = new AtomicReference<>();
    private volatile int responseStatus = 200;
    private volatile String responseBody = "{}";

    private record RecordedRequest(String path, String gatewayAuth, String correlationId) {
    }

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            recorded.set(new RecordedRequest(
                    exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("X-Gateway-Auth"),
                    exchange.getRequestHeaders().getFirst(CorrelationIdFilter.HEADER)));
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
        MDC.remove(CorrelationIdFilter.MDC_KEY);
    }

    private MediaReadinessClient client() {
        return new MediaReadinessClient(RestClient.builder(),
                "http://localhost:" + server.getAddress().getPort(),
                SECRET, Duration.ofSeconds(1), Duration.ofSeconds(2));
    }

    @Test
    void readyMediaPassesAndSendsGatewayAuthAndCorrelationId() {
        UUID mediaId = UUID.randomUUID();
        responseBody = "{\"mediaId\":\"%s\",\"status\":\"READY\"}".formatted(mediaId);
        MDC.put(CorrelationIdFilter.MDC_KEY, "trace-xyz");

        client().ensureMediaReady(mediaId);

        RecordedRequest request = recorded.get();
        assertThat(request.path()).isEqualTo("/internal/media/" + mediaId + "/status");
        assertThat(request.gatewayAuth()).isEqualTo(SECRET);
        assertThat(request.correlationId()).isEqualTo("trace-xyz");
    }

    @Test
    void nonReadyStatusMapsToMediaNotReady() {
        responseBody = "{\"mediaId\":\"%s\",\"status\":\"PENDING_SCAN\"}".formatted(UUID.randomUUID());

        assertThatThrownBy(() -> client().ensureMediaReady(UUID.randomUUID()))
                .isInstanceOf(ParkingException.class)
                .extracting(e -> ((ParkingException) e).errorCode())
                .isEqualTo(ParkingErrorCode.MEDIA_NOT_READY);
    }

    @Test
    void notFoundMapsToMediaNotReady() {
        responseStatus = 404;
        responseBody = "{\"code\":\"MEDIA_NOT_FOUND\"}";

        assertThatThrownBy(() -> client().ensureMediaReady(UUID.randomUUID()))
                .isInstanceOf(ParkingException.class)
                .extracting(e -> ((ParkingException) e).errorCode())
                .isEqualTo(ParkingErrorCode.MEDIA_NOT_READY);
    }

    @Test
    void serverErrorFailsClosedAsMediaAccessUnavailable() {
        responseStatus = 500;
        responseBody = "{\"code\":\"INTERNAL_ERROR\"}";

        assertThatThrownBy(() -> client().ensureMediaReady(UUID.randomUUID()))
                .isInstanceOf(ParkingException.class)
                .extracting(e -> ((ParkingException) e).errorCode())
                .isEqualTo(ParkingErrorCode.MEDIA_ACCESS_UNAVAILABLE);
    }

    @Test
    void unreachableMediaServiceFailsClosedAsMediaAccessUnavailable() {
        int port = server.getAddress().getPort();
        server.stop(0);
        MediaReadinessClient client = new MediaReadinessClient(RestClient.builder(),
                "http://localhost:" + port, SECRET, Duration.ofMillis(250), Duration.ofMillis(250));

        assertThatThrownBy(() -> client.ensureMediaReady(UUID.randomUUID()))
                .isInstanceOf(ParkingException.class)
                .extracting(e -> ((ParkingException) e).errorCode())
                .isEqualTo(ParkingErrorCode.MEDIA_ACCESS_UNAVAILABLE);
    }
}
