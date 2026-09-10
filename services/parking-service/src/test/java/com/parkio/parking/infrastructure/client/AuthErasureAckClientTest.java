package com.parkio.parking.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

class AuthErasureAckClientTest {

    private static final String SECRET = "test-internal-secret";

    private HttpServer server;
    private final AtomicReference<RecordedRequest> recorded = new AtomicReference<>();
    private volatile int responseStatus = 204;

    private record RecordedRequest(String path, String gatewayAuth, String body) {
    }

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            recorded.set(new RecordedRequest(
                    exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("X-Gateway-Auth"),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            byte[] body = new byte[0];
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

    private AuthErasureAckClient client() {
        return new AuthErasureAckClient(
                RestClient.builder(),
                "http://localhost:" + server.getAddress().getPort(),
                SECRET);
    }

    @Test
    void postsAckWithGatewayAuthAndParkingServiceName() {
        UUID eventId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        client().acknowledge(eventId, requestId, userId, "SUCCESS");
        RecordedRequest request = recorded.get();
        assertThat(request.path()).isEqualTo("/internal/erasure/acks");
        assertThat(request.gatewayAuth()).isEqualTo(SECRET);
        assertThat(request.body()).contains("\"serviceName\":\"parking\"");
        assertThat(request.body()).contains(eventId.toString());
        assertThat(request.body()).contains(requestId.toString());
        assertThat(request.body()).contains("\"status\":\"SUCCESS\"");
    }

    @Test
    void httpFailurePropagates() {
        responseStatus = 500;
        assertThatThrownBy(() -> client().acknowledge(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "SUCCESS"))
                .isInstanceOf(RestClientException.class);
    }
}
