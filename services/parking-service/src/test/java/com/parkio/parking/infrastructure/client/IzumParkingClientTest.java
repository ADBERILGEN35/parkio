package com.parkio.parking.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.parkio.parking.infrastructure.config.MunicipalSourceProperties;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

class IzumParkingClientTest {
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void retriesTransientFiveHundredThenSucceeds() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/ibb/izum/otoparklar", exchange -> {
            if (hits.getAndIncrement() == 0) {
                exchange.sendResponseHeaders(500, -1);
                exchange.close();
                return;
            }
            byte[] body = "[{\"ufid\":\"1\"}]".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        IzumParkingClient client = client(1);
        assertThat(client.fetch().isArray()).isTrue();
        assertThat(hits.get()).isEqualTo(2);
    }

    @Test
    void doesNotRetryPermanentFourHundred() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/ibb/izum/otoparklar", exchange -> {
            hits.incrementAndGet();
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();

        IzumParkingClient client = client(3);
        assertThatThrownBy(client::fetch).isInstanceOf(HttpClientErrorException.class);
        assertThat(hits.get()).isEqualTo(1);
    }

    private IzumParkingClient client(int maxRetries) {
        MunicipalSourceProperties properties = new MunicipalSourceProperties();
        properties.getIzum().setBaseUrl("http://localhost:" + server.getAddress().getPort());
        properties.getIzum().setPath("/api/ibb/izum/otoparklar");
        properties.getIzum().setConnectTimeout(Duration.ofSeconds(2));
        properties.getIzum().setReadTimeout(Duration.ofSeconds(2));
        properties.getIzum().setMaxRetries(maxRetries);
        return new IzumParkingClient(RestClient.builder(), properties);
    }
}
