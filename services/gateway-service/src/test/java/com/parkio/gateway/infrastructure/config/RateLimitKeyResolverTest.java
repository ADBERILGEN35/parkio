package com.parkio.gateway.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.gateway.shared.GatewayHeaders;
import java.net.InetSocketAddress;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

/**
 * The rate-limit bucket key is the authenticated user id when present (so a logged-in
 * caller is limited as an individual across IPs) and the client IP otherwise (anonymous
 * endpoints such as login/register). With no trusted proxies configured the anonymous
 * fallback keys on the socket peer (legacy behaviour); see {@link ClientIpResolverTest}
 * for the proxy-aware cases.
 */
class RateLimitKeyResolverTest {

    private final KeyResolver resolver =
            new RateLimitConfig().userOrIpKeyResolver(new ClientIpResolver(List.of()));

    private final KeyResolver proxyAwareResolver = new RateLimitConfig()
            .userOrIpKeyResolver(new ClientIpResolver(List.of("172.16.0.0/12")));

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

    @Test
    void authenticatedUserIdTakesPriorityOverForwardedFor() {
        // Even behind a trusted proxy with an X-Forwarded-For, an authenticated caller is
        // keyed by user id, not IP.
        MockServerHttpRequest request = MockServerHttpRequest
                .get("/api/v1/users/me")
                .header(GatewayHeaders.USER_ID, "user-123")
                .remoteAddress(new InetSocketAddress("172.18.0.5", 5555))
                .header("X-Forwarded-For", "203.0.113.9")
                .build();

        String key = proxyAwareResolver.resolve(MockServerWebExchange.from(request)).block();

        assertThat(key).isEqualTo("user:user-123");
    }

    @Test
    void anonymousKeyContainsOnlyValidatedIpNotRawHeader() {
        // A malicious, oversized header value must never leak into the Redis key. From a
        // trusted proxy a junk XFF is dropped and the key falls back to the (safe) peer IP.
        String malicious = "x".repeat(4096) + "\r\nFLUSHALL";
        MockServerHttpRequest request = MockServerHttpRequest
                .post("/api/v1/auth/login")
                .remoteAddress(new InetSocketAddress("172.18.0.5", 4444))
                .header("X-Forwarded-For", malicious)
                .build();

        String key = proxyAwareResolver.resolve(MockServerWebExchange.from(request)).block();

        assertThat(key).isEqualTo("ip:172.18.0.5");
        assertThat(key).doesNotContain("FLUSHALL");
    }
}
