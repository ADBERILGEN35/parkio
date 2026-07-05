package com.parkio.gateway.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.gateway.shared.GatewayHeaders;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

class PathCanonicalizationGlobalFilterTest {

    private final PathCanonicalizationGlobalFilter filter = new PathCanonicalizationGlobalFilter(
            new GatewayErrorResponseWriter(new ObjectMapper(), Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)));

    @Test
    void traversalPathReturns400Envelope() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest
                .method(HttpMethod.GET, "/api/v1/analytics/users/../overview")
                .header(GatewayHeaders.CORRELATION_ID, "trace-123")
                .build());
        var chain = new CapturingChain();

        filter.filter(exchange, chain).block();

        assertThat(chain.wasInvoked()).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exchange.getResponse().getHeaders().getContentType()).hasToString("application/json");
    }

    @Test
    void validPathIsForwarded() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest
                .get("/api/v1/parking/spots/nearby")
                .build());
        var chain = new CapturingChain();

        filter.filter(exchange, chain).block();

        assertThat(chain.wasInvoked()).isTrue();
    }

    private static final class CapturingChain implements GatewayFilterChain {
        private boolean invoked;

        @Override
        public Mono<Void> filter(org.springframework.web.server.ServerWebExchange exchange) {
            invoked = true;
            return Mono.empty();
        }

        boolean wasInvoked() {
            return invoked;
        }
    }
}