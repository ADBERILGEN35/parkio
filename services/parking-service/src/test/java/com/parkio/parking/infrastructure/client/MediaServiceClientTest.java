package com.parkio.parking.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.parkio.parking.application.port.MediaAccessPort;
import com.parkio.parking.domain.exception.ParkingErrorCode;
import com.parkio.parking.domain.exception.ParkingException;
import com.parkio.parking.infrastructure.web.CorrelationIdFilter;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.web.client.RestClient;

/**
 * Wire-level tests for {@link MediaServiceClient} against a local HTTP server:
 * the shared {@code X-Gateway-Auth} secret and the bound {@code X-Correlation-Id}
 * are sent, the response is parsed, and failures map to safe domain errors
 * (404 → SPOT_NOT_FOUND, anything else → MEDIA_ACCESS_UNAVAILABLE/503).
 */
class MediaServiceClientTest {

    private static final String SECRET = "test-internal-secret";

    private HttpServer server;
    private final AtomicReference<RecordedRequest> recorded = new AtomicReference<>();
    private volatile int responseStatus = 200;
    private volatile String responseBody = "{}";

    private record RecordedRequest(String path, String gatewayAuth, String correlationId, String body) {
    }

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            recorded.set(new RecordedRequest(
                    exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("X-Gateway-Auth"),
                    exchange.getRequestHeaders().getFirst(CorrelationIdFilter.HEADER),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
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

    private MediaServiceClient client() {
        return new MediaServiceClient(RestClient.builder(),
                "http://localhost:" + server.getAddress().getPort(),
                SECRET, Duration.ofSeconds(1), Duration.ofSeconds(2));
    }

    @Test
    void sendsGatewayAuthAndCorrelationIdAndParsesGrant() {
        UUID mediaId = UUID.randomUUID();
        UUID requester = UUID.randomUUID();
        Instant expiresAt = Instant.parse("2026-06-09T12:05:00Z");
        responseBody = """
                {"mediaId":"%s","accessUrl":"https://minio.local/signed?sig=abc","expiresAt":"%s"}
                """.formatted(mediaId, expiresAt);
        MDC.put(CorrelationIdFilter.MDC_KEY, "trace-123");

        MediaAccessPort.MediaAccessGrant grant = client().requestAccessUrl(mediaId, requester);

        assertThat(grant.mediaId()).isEqualTo(mediaId);
        assertThat(grant.accessUrl()).isEqualTo("https://minio.local/signed?sig=abc");
        assertThat(grant.expiresAt()).isEqualTo(expiresAt);

        RecordedRequest request = recorded.get();
        assertThat(request.path()).isEqualTo("/internal/media/" + mediaId + "/access-url");
        assertThat(request.gatewayAuth()).isEqualTo(SECRET);
        assertThat(request.correlationId()).isEqualTo("trace-123");
        assertThat(request.body()).contains(requester.toString(), MediaServiceClient.PURPOSE);
    }

    @Test
    void omitsCorrelationIdWhenNoneIsBound() {
        UUID mediaId = UUID.randomUUID();
        responseBody = """
                {"mediaId":"%s","accessUrl":"https://minio.local/signed","expiresAt":"2026-06-09T12:05:00Z"}
                """.formatted(mediaId);

        client().requestAccessUrl(mediaId, UUID.randomUUID());

        assertThat(recorded.get().correlationId()).isNull();
    }

    @Test
    void mediaNotFoundMapsToSpotNotFound() {
        responseStatus = 404;
        responseBody = "{\"code\":\"MEDIA_NOT_FOUND\"}";

        assertThatThrownBy(() -> client().requestAccessUrl(UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(ParkingException.class)
                .extracting(e -> ((ParkingException) e).errorCode())
                .isEqualTo(ParkingErrorCode.SPOT_NOT_FOUND);
    }

    @Test
    void serverErrorMapsToMediaAccessUnavailable() {
        responseStatus = 500;
        responseBody = "{\"code\":\"INTERNAL_ERROR\"}";

        assertThatThrownBy(() -> client().requestAccessUrl(UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(ParkingException.class)
                .extracting(e -> ((ParkingException) e).errorCode())
                .isEqualTo(ParkingErrorCode.MEDIA_ACCESS_UNAVAILABLE);
    }

    @Test
    void unreachableMediaServiceMapsToMediaAccessUnavailable() {
        int port = server.getAddress().getPort();
        server.stop(0);
        MediaServiceClient client = new MediaServiceClient(RestClient.builder(),
                "http://localhost:" + port, SECRET, Duration.ofMillis(250), Duration.ofMillis(250));

        assertThatThrownBy(() -> client.requestAccessUrl(UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(ParkingException.class)
                .extracting(e -> ((ParkingException) e).errorCode())
                .isEqualTo(ParkingErrorCode.MEDIA_ACCESS_UNAVAILABLE);
    }

    @Test
    void incompleteResponseBodyMapsToMediaAccessUnavailable() {
        responseBody = "{\"mediaId\":\"" + UUID.randomUUID() + "\"}";

        assertThatThrownBy(() -> client().requestAccessUrl(UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(ParkingException.class)
                .extracting(e -> ((ParkingException) e).errorCode())
                .isEqualTo(ParkingErrorCode.MEDIA_ACCESS_UNAVAILABLE);
    }
}
