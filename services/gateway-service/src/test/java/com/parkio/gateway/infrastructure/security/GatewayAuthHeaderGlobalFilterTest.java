package com.parkio.gateway.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.gateway.shared.GatewayHeaders;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * The gateway stamps the trusted internal secret onto every routed request and never
 * lets a client-supplied {@code X-Gateway-Auth} through.
 */
class GatewayAuthHeaderGlobalFilterTest {

    private static final String SECRET = "configured-internal-secret-value";

    private final GatewayAuthHeaderGlobalFilter filter = new GatewayAuthHeaderGlobalFilter(SECRET);

    @Test
    void stripsSpoofedHeaderAndInjectsConfiguredSecret() {
        MockServerHttpRequest request = MockServerHttpRequest
                .method(HttpMethod.GET, "/api/v1/users/me")
                .header(GatewayHeaders.GATEWAY_AUTH, "attacker-supplied-value")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        CapturingChain chain = new CapturingChain();

        filter.filter(exchange, chain).block();

        assertThat(forwarded(chain)).isEqualTo(SECRET);
    }

    @Test
    void injectsSecretWhenAbsent() {
        MockServerHttpRequest request = MockServerHttpRequest
                .method(HttpMethod.POST, "/api/v1/auth/login").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        CapturingChain chain = new CapturingChain();

        filter.filter(exchange, chain).block();

        assertThat(forwarded(chain)).isEqualTo(SECRET);
    }

    private static String forwarded(CapturingChain chain) {
        return chain.captured.getRequest().getHeaders().getFirst(GatewayHeaders.GATEWAY_AUTH);
    }

    private static final class CapturingChain implements GatewayFilterChain {
        private ServerWebExchange captured;

        @Override
        public Mono<Void> filter(ServerWebExchange exchange) {
            this.captured = exchange;
            return Mono.empty();
        }
    }
}
