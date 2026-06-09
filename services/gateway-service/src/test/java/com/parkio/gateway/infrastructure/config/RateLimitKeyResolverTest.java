package com.parkio.gateway.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.gateway.shared.GatewayHeaders;
import java.net.InetSocketAddress;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

/**
 * The rate-limit bucket key is the authenticated user id when present (so a logged-in
 * caller is limited as an individual across IPs) and the client IP otherwise (anonymous
 * endpoints such as login/register).
 */
class RateLimitKeyResolverTest {

    private final KeyResolver resolver = new RateLimitConfig().userOrIpKeyResolver();

    @Test
    void usesUserIdWhenAuthenticated() {
        MockServerHttpRequest request = MockServerHttpRequest
                .get("/api/v1/users/me")
                .header(GatewayHeaders.USER_ID, "user-123")
                .remoteAddress(new InetSocketAddress("203.0.113.7", 5555))
                .build();

        String key = resolver.resolve(MockServerWebExchange.from(request)).block();

        assertThat(key).isEqualTo("user:user-123");
    }

    @Test
    void fallsBackToClientIpWhenAnonymous() {
        MockServerHttpRequest request = MockServerHttpRequest
                .post("/api/v1/auth/login")
                .remoteAddress(new InetSocketAddress("203.0.113.7", 5555))
                .build();

        String key = resolver.resolve(MockServerWebExchange.from(request)).block();

        assertThat(key).isEqualTo("ip:203.0.113.7");
    }

    @Test
    void blankUserIdFallsBackToIp() {
        MockServerHttpRequest request = MockServerHttpRequest
                .get("/api/v1/users/me")
                .header(GatewayHeaders.USER_ID, "  ")
                .remoteAddress(new InetSocketAddress("198.51.100.4", 4444))
                .build();

        String key = resolver.resolve(MockServerWebExchange.from(request)).block();

        assertThat(key).isEqualTo("ip:198.51.100.4");
    }
}
