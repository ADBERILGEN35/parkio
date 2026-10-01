package com.parkio.parking.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.unit.DataSize;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** CL-F23: the response bounds against a real loopback HTTP server. */
class BoundedFeedResponsesTest {

    private HttpServer server;

    @BeforeAll
    static void warmUp() throws Exception {
        warmUpHttpStack();
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    /**
     * The first HTTP exchange in a fresh test JVM loads the client stack, which took 4-5 s on a
     * slow machine. Timed tests run after one untimed exchange so they measure only the bounds.
     */
    static void warmUpHttpStack() throws Exception {
        BoundedFeedResponsesTest warmUp = new BoundedFeedResponsesTest();
        warmUp.serve(body(16), false, 16, Duration.ZERO);
        try {
            warmUp.fetch(DataSize.ofKilobytes(1), Duration.ofSeconds(10));
        } finally {
            warmUp.stop();
        }
    }

    @Test
    void streamedBodyIsCutOffOneByteAfterTheSizeLimit() throws Exception {
        serve(body(64 * 1024), false, 64 * 1024, Duration.ZERO);

        assertThatThrownBy(() -> fetch(DataSize.ofKilobytes(1), Duration.ofSeconds(10)))
                .isInstanceOf(RestClientException.class)
                .satisfies(ex -> assertThat(BoundedFeedResponses.exceededLimit(ex)).isTrue())
                .rootCause().hasMessageContaining("exceeds 1024 bytes");
    }

    @Test
    void declaredOversizedBodyFailsBeforeItIsRead() throws Exception {
        serve(body(64 * 1024), true, 64 * 1024, Duration.ZERO);

        assertThatThrownBy(() -> fetch(DataSize.ofKilobytes(1), Duration.ofSeconds(10)))
                .satisfies(ex -> assertThat(BoundedFeedResponses.exceededLimit(ex)).isTrue())
                .rootCause().hasMessageContaining("exceeds 1024 bytes");
    }

    @Test
    void slowDripIsCutOffAtTheTimeLimitDespiteTheReadTimeout() throws Exception {
        // One byte every 100 ms for 5 s: each socket read beats the 2 s read timeout.
        serve(body(50), false, 1, Duration.ofMillis(100));

        long started = System.nanoTime();
        assertThatThrownBy(() -> fetch(DataSize.ofMegabytes(1), Duration.ofMillis(500)))
                .satisfies(ex -> assertThat(BoundedFeedResponses.exceededLimit(ex)).isTrue())
                .rootCause().hasMessageContaining("not complete within PT0.5S");
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(2500));
    }

    @Test
    void bodiesWithinTheBoundsAreReadCompletely() throws Exception {
        byte[] exactly = body(1024);
        serve(exactly, false, exactly.length, Duration.ZERO);

        JsonNode node = fetch(DataSize.ofBytes(exactly.length), Duration.ofSeconds(10));

        assertThat(exactly).hasSize(1024);
        assertThat(node.isArray()).isTrue();
        assertThat(node.size()).isEqualTo(zeros(exactly));
    }

    @Test
    void declaredBodyOfExactlyTheLimitIsReadCompletely() throws Exception {
        // A fixed-length body's stream supports mark/reset, which Spring uses to look for a body.
        byte[] exactly = body(1024);
        serve(exactly, true, exactly.length, Duration.ZERO);

        JsonNode node = fetch(DataSize.ofBytes(exactly.length), Duration.ofSeconds(10));

        assertThat(node.size()).isEqualTo(zeros(exactly));
    }

    @Test
    void oversizedStreamedBodyIsAbandonedWithoutReadingTheRest() throws Exception {
        // 64 KiB in 1 KiB chunks every 100 ms: reading it to the end would take about 6.4 s.
        serve(body(64 * 1024), false, 1024, Duration.ofMillis(100));

        long started = System.nanoTime();
        assertThatThrownBy(() -> fetch(DataSize.ofKilobytes(1), Duration.ofSeconds(30)))
                .satisfies(ex -> assertThat(BoundedFeedResponses.exceededLimit(ex)).isTrue())
                .rootCause().hasMessageContaining("exceeds 1024 bytes");
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(2500));
    }

    @Test
    void declaredOversizedBodyIsRefusedWithoutReadingIt() throws Exception {
        serve(body(64 * 1024), true, 1024, Duration.ofMillis(100));

        long started = System.nanoTime();
        assertThatThrownBy(() -> fetch(DataSize.ofKilobytes(1), Duration.ofSeconds(30)))
                .satisfies(ex -> assertThat(BoundedFeedResponses.exceededLimit(ex)).isTrue())
                .rootCause().hasMessageContaining("exceeds 1024 bytes");
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(2500));
    }

    @Test
    void nonPositiveBoundsAreRejectedAtConstruction() {
        assertThatThrownBy(() -> BoundedFeedResponses.interceptor(DataSize.ofBytes(0), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BoundedFeedResponses.interceptor(DataSize.ofKilobytes(1), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** A JSON array of exactly {@code size} bytes. */
    static byte[] body(int size) {
        StringBuilder json = new StringBuilder("[0");
        while (json.length() + 2 <= size - 1) {
            json.append(",0");
        }
        while (json.length() < size - 1) {
            json.append(' ');
        }
        return json.append(']').toString().getBytes(StandardCharsets.US_ASCII);
    }

    private static int zeros(byte[] json) {
        return (int) new String(json, StandardCharsets.US_ASCII).chars().filter(c -> c == '0').count();
    }

    /** Sends {@code payload} in {@code chunkSize} pieces, flushing each and pausing after it. */
    private void serve(byte[] payload, boolean declareLength, int chunkSize, Duration pause) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/feed", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, declareLength ? payload.length : 0);
            try (OutputStream out = exchange.getResponseBody()) {
                for (int offset = 0; offset < payload.length; offset += chunkSize) {
                    out.write(payload, offset, Math.min(chunkSize, payload.length - offset));
                    out.flush();
                    if (!pause.isZero()) {
                        Thread.sleep(pause.toMillis());
                    }
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } catch (java.io.IOException ignored) {
                // the client gave up on purpose
            }
        });
        server.start();
    }

    private JsonNode fetch(DataSize maxSize, Duration maxTime) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(2));
        return RestClient.builder()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .requestFactory(factory)
                .requestInterceptor(BoundedFeedResponses.interceptor(maxSize, maxTime))
                .build()
                .get().uri("/feed").retrieve().body(JsonNode.class);
    }
}
