package com.parkio.gateway.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.gateway.infrastructure.web.GatewayErrorResponseWriter;
import com.parkio.gateway.shared.GatewayHeaders;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
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

    private static final String KEY_ID = "filter-test-key";
    private static final String ISSUER = "parkio-auth";
    private static final String AUDIENCE = "parkio-api";
    private static KeyPair keyPair;

    @BeforeAll
    static void generateKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();
    }

    @Test
    void publicRouteIsForwardedWithoutTokenAndStripsClientIdentity() {
        var request = MockServerHttpRequest.method(HttpMethod.POST, "/api/v1/auth/login")
                .header(GatewayHeaders.USER_ID, "spoofed-id")
                .build();
        var exchange = MockServerWebExchange.from(request);
        var chain = new CapturingChain();

        filter().filter(exchange, chain).block();

        assertThat(chain.wasInvoked()).isTrue();
        assertThat(forwardedHeader(chain, GatewayHeaders.USER_ID)).isNull();
    }

    @Test
    void jwksRouteIsPublic() {
        var request = MockServerHttpRequest
                .get("/api/v1/auth/.well-known/jwks.json")
                .build();
        var chain = new CapturingChain();

        filter().filter(MockServerWebExchange.from(request), chain).block();

        assertThat(chain.wasInvoked()).isTrue();
    }

    @Test
    void protectedRouteWithoutTokenIsRejected() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest
                .get("/api/v1/users/me").build());
        var chain = new CapturingChain();

        filter().filter(exchange, chain).block();

        assertThat(chain.wasInvoked()).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void protectedRouteWithInvalidTokenIsRejected() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest
                .get("/api/v1/users/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-real-token")
                .build());
        var chain = new CapturingChain();

        filter().filter(exchange, chain).block();

        assertThat(chain.wasInvoked()).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void validTokenInjectsIdentityAndOverridesClientHeaders() {
        UUID userId = UUID.randomUUID();
        String token = validToken(userId, "rider@parkio.test", List.of("USER"));
        var request = MockServerHttpRequest.get("/api/v1/users/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header(GatewayHeaders.USER_ID, "spoofed-id")
                .header(GatewayHeaders.USER_EMAIL, "spoofed@evil.test")
                .header(GatewayHeaders.USER_ROLES, "ADMIN")
                .build();
        var chain = new CapturingChain();

        filter().filter(MockServerWebExchange.from(request), chain).block();

        assertThat(forwardedHeader(chain, GatewayHeaders.USER_ID)).isEqualTo(userId.toString());
        assertThat(forwardedHeader(chain, GatewayHeaders.USER_EMAIL)).isEqualTo("rider@parkio.test");
        assertThat(forwardedHeader(chain, GatewayHeaders.USER_ROLES)).isEqualTo("USER");
    }

    private static AuthenticationGlobalFilter filter() {
        JwtProperties properties = new JwtProperties();
        properties.setIssuer(ISSUER);
        properties.setAudience(AUDIENCE);
        properties.setJwksUri("http://unused.test/jwks");
        RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
        JwksKeyResolver resolver = keyId -> KEY_ID.equals(keyId)
                ? Mono.just(publicKey)
                : Mono.error(new JwtException("Unknown JWT key id"));
        JwtTokenValidator validator = new JwtTokenValidator(
                properties, resolver, new ObjectMapper().findAndRegisterModules());
        return new AuthenticationGlobalFilter(
                new PublicEndpoints(),
                validator,
                new GatewayErrorResponseWriter(
                        new ObjectMapper().findAndRegisterModules(),
                        Clock.fixed(Instant.parse("2026-06-07T00:00:00Z"), ZoneOffset.UTC)));
    }

    private static String validToken(UUID userId, String email, List<String> roles) {
        Instant now = Instant.now();
        return Jwts.builder()
                .header().keyId(KEY_ID).and()
                .issuer(ISSUER)
                .audience().add(AUDIENCE).and()
                .subject(userId.toString())
                .claim("email", email)
                .claim("roles", roles)
                .claim("status", "ACTIVE")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(15, ChronoUnit.MINUTES)))
                .signWith((RSAPrivateKey) keyPair.getPrivate(), Jwts.SIG.RS256)
                .compact();
    }

    private static String forwardedHeader(CapturingChain chain, String name) {
        return chain.captured().getRequest().getHeaders().getFirst(name);
    }

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
