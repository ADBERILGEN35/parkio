package com.parkio.parking.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.parkio.parking.infrastructure.config.MunicipalSourceProperties;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.util.unit.DataSize;
import org.springframework.web.client.RestClient;

/**
 * CL-F23: every municipal source client reads its feed through the response bounds, and a
 * bound violation is not retried (it would only repeat the cost).
 */
class MunicipalFeedClientBoundsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private final AtomicInteger hits = new AtomicInteger();

    @BeforeAll
    static void warmUp() throws Exception {
        BoundedFeedResponsesTest.warmUpHttpStack();
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** Applies the stub address, a 1 KiB size bound and two retries to one source's settings. */
    record Source(String name, BiFunction<MunicipalSourceProperties, String, Supplier<Object>> client) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Arguments> sources() {
        return Stream.of(
                Arguments.of(new Source("izum", (p, baseUrl) -> {
                    configure(baseUrl, p.getIzum()::setBaseUrl, p.getIzum()::setPath, p.getIzum()::setMaxRetries,
                            p.getIzum()::setMaxResponseSize);
                    return new IzumParkingClient(RestClient.builder(), p)::fetch;
                })),
                Arguments.of(new Source("ispark", (p, baseUrl) -> {
                    configure(baseUrl, p.getIspark()::setBaseUrl, p.getIspark()::setPath, p.getIspark()::setMaxRetries,
                            p.getIspark()::setMaxResponseSize);
                    return new IsparkParkingClient(RestClient.builder(), p)::fetch;
                })),
                Arguments.of(new Source("anpark", (p, baseUrl) -> {
                    configure(baseUrl, p.getAnpark()::setBaseUrl, p.getAnpark()::setPath, p.getAnpark()::setMaxRetries,
                            p.getAnpark()::setMaxResponseSize);
                    return new AnparkParkingClient(RestClient.builder(), p)::fetch;
                })),
                Arguments.of(new Source("konya", (p, baseUrl) -> {
                    configure(baseUrl, p.getKonya()::setBaseUrl, p.getKonya()::setPath, p.getKonya()::setMaxRetries,
                            p.getKonya()::setMaxResponseSize);
                    return new KonyaParkingClient(RestClient.builder(), MAPPER, p)::fetch;
                })),
                Arguments.of(new Source("kayseri", (p, baseUrl) -> {
                    configure(baseUrl, p.getKayseri()::setBaseUrl, p.getKayseri()::setPath, p.getKayseri()::setMaxRetries,
                            p.getKayseri()::setMaxResponseSize);
                    return new KayseriParkingClient(RestClient.builder(), MAPPER, p)::fetch;
                })));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sources")
    void oversizedFeedFailsOnceWithoutARetry(Source source) throws Exception {
        start(BoundedFeedResponsesTest.body(64 * 1024), Duration.ZERO);
        Supplier<Object> fetch = source.client().apply(
                new MunicipalSourceProperties(), "http://127.0.0.1:" + server.getAddress().getPort());

        assertThatThrownBy(fetch::get)
                .satisfies(ex -> assertThat(BoundedFeedResponses.exceededLimit(ex)).as("bound violation").isTrue());
        assertThat(hits).as("requests").hasValue(1);
    }

    @Test
    void slowDripFeedIsCutOffAtTheTimeBoundWithoutARetry() throws Exception {
        start(BoundedFeedResponsesTest.body(50), Duration.ofMillis(100));
        MunicipalSourceProperties properties = new MunicipalSourceProperties();
        properties.getIzum().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.getIzum().setPath("/feed");
        properties.getIzum().setReadTimeout(Duration.ofSeconds(2));
        properties.getIzum().setMaxRetries(2);
        properties.getIzum().setMaxResponseTime(Duration.ofMillis(500));
        IzumParkingClient client = new IzumParkingClient(RestClient.builder(), properties);

        long started = System.nanoTime();
        assertThatThrownBy(client::fetch)
                .satisfies(ex -> assertThat(BoundedFeedResponses.exceededLimit(ex)).isTrue());
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(2500));
        assertThat(hits).hasValue(1);
    }

    private static void configure(String baseUrl,
                                  java.util.function.Consumer<String> setBaseUrl,
                                  java.util.function.Consumer<String> setPath,
                                  java.util.function.IntConsumer setMaxRetries,
                                  java.util.function.Consumer<DataSize> setMaxResponseSize) {
        setBaseUrl.accept(baseUrl);
        setPath.accept("/feed");
        setMaxRetries.accept(2);
        setMaxResponseSize.accept(DataSize.ofKilobytes(1));
    }

    private void start(byte[] payload, Duration perByteDelay) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                if (perByteDelay.isZero()) {
                    out.write(payload);
                } else {
                    for (byte b : payload) {
                        out.write(b);
                        out.flush();
                        Thread.sleep(perByteDelay.toMillis());
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
}
