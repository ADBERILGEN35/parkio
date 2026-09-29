package com.parkio.gateway.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

/** Real HTTP proof of 2xx epoch parsing. ExchangeFunction stubs are not this class. */
class SessionEpochClientHttpTest {

    private static HttpServer server;
    private static SessionEpochClient client;
    private static final AtomicInteger status = new AtomicInteger(200);
    private static final AtomicReference<String> body = new AtomicReference<>("");

    @BeforeAll
    static void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] payload = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", MediaType.APPLICATION_JSON_VALUE);
            exchange.sendResponseHeaders(status.get(), payload.length == 0 ? -1 : payload.length);
            if (payload.length > 0) {
                exchange.getResponseBody().write(payload);
            }
            exchange.close();
        });
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "session-epoch-http-stub");
            thread.setDaemon(true);
            return thread;
        }));
        server.start();
        SessionEpochProperties properties = new SessionEpochProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setRequestTimeout(Duration.ofSeconds(5));
        client = new SessionEpochClient(WebClient.builder().baseUrl(properties.getBaseUrl()).build(), properties);
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void httpEpochZeroIsAccepted() {
        respond(200, "{\"userId\":\"u1\",\"sessionEpoch\":0}");
        assertThat(client.fetchCurrentEpoch("u1").block()).isZero();
    }

    @Test
    void httpMissingEpochIsRejected() {
        respond(200, "{\"userId\":\"u1\"}");
        assertThatThrownBy(() -> client.fetchCurrentEpoch("u1").block())
                .isInstanceOf(SessionEpochUnavailableException.class);
    }

    @Test
    void httpNullEpochIsRejected() {
        respond(200, "{\"userId\":\"u1\",\"sessionEpoch\":null}");
        assertThatThrownBy(() -> client.fetchCurrentEpoch("u1").block())
                .isInstanceOf(SessionEpochUnavailableException.class);
    }

    @Test
    void httpEmptyBodyIsRejected() {
        respond(200, "");
        assertThatThrownBy(() -> client.fetchCurrentEpoch("u1").block())
                .isInstanceOf(SessionEpochUnavailableException.class);
    }

    @Test
    void httpMalformedBodyIsRejected() {
        respond(200, "{\"userId\":\"u1\",\"sessionEpoch\":");
        assertThatThrownBy(() -> client.fetchCurrentEpoch("u1").block())
                .isInstanceOf(SessionEpochUnavailableException.class);
    }

    @Test
    void httpIdentityMismatchIsRejected() {
        respond(200, "{\"userId\":\"other\",\"sessionEpoch\":0}");
        assertThatThrownBy(() -> client.fetchCurrentEpoch("u1").block())
                .isInstanceOf(SessionEpochUnavailableException.class);
    }

    private static void respond(int code, String payload) {
        status.set(code);
        body.set(payload);
    }
}
