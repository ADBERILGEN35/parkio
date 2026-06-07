package com.parkio.gateway.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.gateway.shared.GatewayHeaders;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

class CorrelationIdGlobalFilterTest {

    private final CorrelationIdGlobalFilter filter = new CorrelationIdGlobalFilter();

    @Test
    void generatesCorrelationIdWhenAbsent() {
        MockServerHttpRequest request = MockServerHttpRequest.method(HttpMethod.GET, "/api/v1/users/me").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        CapturingChain chain = new CapturingChain();

        filter.filter(exchange, chain).block();

        String forwarded = chain.captured().getRequest().getHeaders().getFirst(GatewayHeaders.CORRELATION_ID);
        assertThat(forwarded).isNotBlank();
        assertThat(exchange.getResponse().getHeaders().getFirst(GatewayHeaders.CORRELATION_ID)).isEqualTo(forwarded);
        assertThat(exchange.getAttributes().get(GatewayHeaders.CORRELATION_ID_ATTRIBUTE)).isEqualTo(forwarded);
    }

    @Test
    void forwardsClientSuppliedCorrelationId() {
        MockServerHttpRequest request = MockServerHttpRequest
                .method(HttpMethod.GET, "/api/v1/users/me")
                .header(GatewayHeaders.CORRELATION_ID, "client-correlation-123")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        CapturingChain chain = new CapturingChain();

        filter.filter(exchange, chain).block();

        assertThat(chain.captured().getRequest().getHeaders().getFirst(GatewayHeaders.CORRELATION_ID))
                .isEqualTo("client-correlation-123");
        assertThat(exchange.getResponse().getHeaders().getFirst(GatewayHeaders.CORRELATION_ID))
                .isEqualTo("client-correlation-123");
    }

    private static final class CapturingChain implements GatewayFilterChain {

        private ServerWebExchange captured;

        @Override
        public Mono<Void> filter(ServerWebExchange exchange) {
            this.captured = exchange;
            return Mono.empty();
        }

        ServerWebExchange captured() {
            return captured;
        }
    }
}
