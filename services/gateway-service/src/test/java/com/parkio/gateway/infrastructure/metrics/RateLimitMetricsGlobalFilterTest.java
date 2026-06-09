package com.parkio.gateway.infrastructure.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

/** Verifies the 429 counter fires only when a request is rejected at the edge. */
class RateLimitMetricsGlobalFilterTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final RateLimitMetricsGlobalFilter filter = new RateLimitMetricsGlobalFilter(registry);

    @Test
    void countsRateLimitedRequests() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/parking/spots").build());

        filter.filter(exchange, ex -> {
            ex.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
            return Mono.empty();
        }).block();

        assertThat(registry.counter("parkio.gateway.rate.limit.rejected.count").count()).isEqualTo(1.0);
    }

    @Test
    void ignoresNonRateLimitedRequests() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/parking/spots").build());

        filter.filter(exchange, ex -> {
            ex.getResponse().setStatusCode(HttpStatus.OK);
            return Mono.empty();
        }).block();

        assertThat(registry.counter("parkio.gateway.rate.limit.rejected.count").count()).isZero();
    }
}
