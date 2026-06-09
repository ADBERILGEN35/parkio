package com.parkio.gateway.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.gateway.infrastructure.web.GatewayErrorResponseWriter;
import com.parkio.gateway.shared.GatewayHeaders;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

class AuthenticationGlobalFilterTest {

    private static final String SECRET = "unit-test-parkio-jwt-secret-0123456789-abcdefghij";
    private static final String ISSUER = "parkio-auth";

    private final AuthenticationGlobalFilter filter = new AuthenticationGlobalFilter(
            new PublicEndpoints(),
            new JwtTokenValidator(properties()),
            new GatewayErrorResponseWriter(new ObjectMapper().findAndRegisterModules(),
                    Clock.fixed(Instant.parse("2026-06-07T00:00:00Z"), ZoneOffset.UTC)));

    @Test
    void publicRouteIsForwardedWithoutTokenAndStripsClientIdentity() {
        MockServerHttpRequest request = MockServerHttpRequest
                .method(HttpMethod.POST, "/api/v1/auth/login")
                .header(GatewayHeaders.USER_ID, "spoofed-id")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        CapturingChain chain = new CapturingChain();

        filter.filter(exchange, chain).block();

        assertThat(chain.wasInvoked()).isTrue();
        assertThat(forwardedHeader(chain, GatewayHeaders.USER_ID)).isNull();
    }

    @Test
    void protectedRouteWithoutTokenIsRejected() {
        MockServerHttpRequest request = MockServerHttpRequest
                .method(HttpMethod.GET, "/api/v1/users/me")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        CapturingChain chain = new CapturingChain();

        filter.filter(exchange, chain).block();

        assertThat(chain.wasInvoked()).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void protectedRouteWithInvalidTokenIsRejected() {
        MockServerHttpRequest request = MockServerHttpRequest
                .method(HttpMethod.GET, "/api/v1/users/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-real-token")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        CapturingChain chain = new CapturingChain();

        filter.filter(exchange, chain).block();

        assertThat(chain.wasInvoked()).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void validTokenInjectsIdentityAndOverridesClientSuppliedHeader() {
        UUID userId = UUID.randomUUID();
        String token = validToken(userId, "rider@parkio.test", List.of("USER"));
        MockServerHttpRequest request = MockServerHttpRequest
                .method(HttpMethod.GET, "/api/v1/users/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header(GatewayHeaders.USER_ID, "spoofed-id")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        CapturingChain chain = new CapturingChain();

        filter.filter(exchange, chain).block();

        assertThat(chain.wasInvoked()).isTrue();
        assertThat(forwardedHeader(chain, GatewayHeaders.USER_ID)).isEqualTo(userId.toString());
        assertThat(forwardedHeader(chain, GatewayHeaders.USER_EMAIL)).isEqualTo("rider@parkio.test");
        assertThat(forwardedHeader(chain, GatewayHeaders.USER_ROLES)).isEqualTo("USER");
    }

    @Test
    void validTokenOverridesClientSuppliedRolesHeader() {
        UUID userId = UUID.randomUUID();
        String token = validToken(userId, "rider@parkio.test", List.of("USER"));
        MockServerHttpRequest request = MockServerHttpRequest
                .method(HttpMethod.GET, "/api/v1/analytics/overview")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                // A client attempts to escalate by injecting privileged roles.
                .header(GatewayHeaders.USER_ROLES, "ADMIN,MODERATOR")
                .header(GatewayHeaders.USER_EMAIL, "spoofed@evil.test")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        CapturingChain chain = new CapturingChain();

        filter.filter(exchange, chain).block();

        // Only the validated token's roles/email survive; the spoofed values are gone.
        assertThat(forwardedHeader(chain, GatewayHeaders.USER_ROLES)).isEqualTo("USER");
        assertThat(forwardedHeader(chain, GatewayHeaders.USER_EMAIL)).isEqualTo("rider@parkio.test");
    }

    private static String forwardedHeader(CapturingChain chain, String name) {
        return chain.captured().getRequest().getHeaders().getFirst(name);
    }

    private static String validToken(UUID userId, String email, List<String> roles) {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer(ISSUER)
                .subject(userId.toString())
                .claim("email", email)
                .claim("roles", roles)
                .claim("status", "ACTIVE")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(15, ChronoUnit.MINUTES)))
                .signWith(key)
                .compact();
    }

    private static JwtProperties properties() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret(SECRET);
        properties.setIssuer(ISSUER);
        return properties;
    }

    /** Captures the exchange handed downstream so injected/stripped headers can be asserted. */
    private static final class CapturingChain implements GatewayFilterChain {

        private ServerWebExchange captured;

        @Override
        public Mono<Void> filter(ServerWebExchange exchange) {
            this.captured = exchange;
            return Mono.empty();
        }

        boolean wasInvoked() {
            return captured != null;
        }

        ServerWebExchange captured() {
            return captured;
        }
    }
}
