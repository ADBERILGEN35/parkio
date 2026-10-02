package com.parkio.gateway.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.gateway.infrastructure.config.GatewayPublicSurfaceProperties;
import com.parkio.gateway.infrastructure.web.GatewayErrorResponseWriter;
import com.parkio.gateway.shared.GatewayHeaders;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.netty.channel.ConnectTimeoutException;
import java.io.IOException;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
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
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Signal;

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
    void waitlistSubmissionIsPublic() {
        var request = MockServerHttpRequest
                .post("/api/v1/waitlist")
                .build();
        var chain = new CapturingChain();

        filter().filter(MockServerWebExchange.from(request), chain).block();

        assertThat(chain.wasInvoked()).isTrue();
    }

    @Test
    void waitlistConfirmWithdrawAndResendArePublic() {
        for (String path : List.of(
                "/api/v1/waitlist/confirm",
                "/api/v1/waitlist/withdraw",
                "/api/v1/waitlist/resend")) {
            var request = MockServerHttpRequest.post(path).build();
            var chain = new CapturingChain();
            filter().filter(MockServerWebExchange.from(request), chain).block();
            assertThat(chain.wasInvoked()).as(path).isTrue();
        }
    }

    @Test
    void publicExploreFlagOffReturnsCanonicalMissingToken() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest
                .get("/api/v1/public/explore/facilities").build());
        var chain = new CapturingChain();

        filter().filter(exchange, chain).block();

        assertThat(chain.wasInvoked()).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void publicExploreFlagOnIsCredentialInvariantAndStripsForgedIdentity() {
        GatewayPublicSurfaceProperties properties = new GatewayPublicSurfaceProperties();
        properties.setPublicExploreEnabled(true);
        var filter = filter(properties);
        String normalUserToken = validToken(UUID.randomUUID(), "rider@parkio.test", List.of("USER"));

        var anonymousChain = new CapturingChain();
        filter.filter(MockServerWebExchange.from(MockServerHttpRequest
                .get("/api/v1/public/explore/facilities").build()), anonymousChain).block();

        var credentialedChain = new CapturingChain();
        filter.filter(MockServerWebExchange.from(MockServerHttpRequest
                .get("/api/v1/public/explore/facilities")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + normalUserToken)
                .header(GatewayHeaders.USER_ID, "forged-user")
                .build()), credentialedChain).block();

        assertThat(anonymousChain.wasInvoked()).isTrue();
        assertThat(credentialedChain.wasInvoked()).isTrue();
        assertThat(forwardedHeader(credentialedChain, GatewayHeaders.USER_ID)).isNull();
    }

    @Test
    void publicGeocodingFlagOffReturnsCanonicalMissingToken() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest
                .get("/api/v1/public/geocoding/search").build());
        var chain = new CapturingChain();

        filter().filter(exchange, chain).block();

        assertThat(chain.wasInvoked()).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void publicGeocodingFlagOnIsCredentialInvariantAndStripsForgedIdentity() {
        GatewayPublicSurfaceProperties properties = new GatewayPublicSurfaceProperties();
        properties.setPublicExploreEnabled(true);
        var filter = filter(properties);
        String normalUserToken = validToken(UUID.randomUUID(), "rider@parkio.test", List.of("USER"));

        var anonymousChain = new CapturingChain();
        filter.filter(MockServerWebExchange.from(MockServerHttpRequest
                .get("/api/v1/public/geocoding/search").build()), anonymousChain).block();

        var credentialedChain = new CapturingChain();
        filter.filter(MockServerWebExchange.from(MockServerHttpRequest
                .get("/api/v1/public/geocoding/search")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + normalUserToken)
                .header(GatewayHeaders.USER_ID, "forged-user")
                .header(GatewayHeaders.USER_ROLES, "ADMIN")
                .build()), credentialedChain).block();

        assertThat(anonymousChain.wasInvoked()).isTrue();
        assertThat(credentialedChain.wasInvoked()).isTrue();
        assertThat(forwardedHeader(credentialedChain, GatewayHeaders.USER_ID)).isNull();
        assertThat(forwardedHeader(credentialedChain, GatewayHeaders.USER_ROLES)).isNull();
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
    void waitlistAdminAndExportRequireAuthentication() {
        for (String path : List.of(
                "/api/v1/waitlist/admin",
                "/api/v1/waitlist/admin/summary",
                "/api/v1/waitlist/export")) {
            var exchange = MockServerWebExchange.from(MockServerHttpRequest.get(path).build());
            var chain = new CapturingChain();
            filter().filter(exchange, chain).block();
            assertThat(chain.wasInvoked()).as(path).isFalse();
            assertThat(exchange.getResponse().getStatusCode()).as(path).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    @Test
    void publicWaitlistSubmitRemainsAnonymous() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest
                .post("/api/v1/waitlist").build());
        var chain = new CapturingChain();
        filter().filter(exchange, chain).block();
        assertThat(chain.wasInvoked()).isTrue();
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

    @ParameterizedTest(name = "{0}")
    @MethodSource("downstreamFailures")
    void downstreamFailureAfterSuccessfulValidationIsNotReportedAsInvalidToken(String scenario, Throwable failure) {
        UUID userId = UUID.randomUUID();
        var exchange = protectedExchange(validToken(userId, "rider@parkio.test", List.of("USER")));
        var chain = new CapturingChain(Mono.error(failure));

        Signal<Void> outcome = filter().filter(exchange, chain).materialize().block();

        // Authentication succeeded: the verified identity reached the downstream chain.
        assertThat(forwardedHeader(chain, GatewayHeaders.USER_ID)).isEqualTo(userId.toString());
        // The failure reaches the gateway's error handling unchanged (5xx/504) instead of
        // being rewritten into 401 INVALID_TOKEN, which clients answer with refresh + retry.
        assertThat(outcome.getThrowable()).isSameAs(failure);
        assertThat(exchange.getResponse().getStatusCode()).isNull();
        assertThat(exchange.getResponse().isCommitted()).isFalse();
    }

    static Stream<Arguments> downstreamFailures() {
        return Stream.of(
                Arguments.of("connect timeout", new ConnectTimeoutException(
                        "connection timed out after 2000 ms: gamification-service/10.0.0.8:8085")),
                Arguments.of("connection refused", new ConnectException(
                        "Connection refused: gamification-service/10.0.0.8:8085")),
                Arguments.of("response timeout", new ResponseStatusException(
                        HttpStatus.GATEWAY_TIMEOUT, "Response took longer than timeout: PT30S")),
                Arguments.of("later filter error", new IllegalStateException("route filter failed")));
    }

    @Test
    void failureAfterTheDownstreamResponseIsCommittedIsNotWrittenOver() {
        var exchange = protectedExchange(validToken(UUID.randomUUID(), "rider@parkio.test", List.of("USER")));
        var failure = new IllegalStateException("downstream connection closed mid-body");
        // The downstream status, headers and first bytes are already written when it fails.
        var chain = new CapturingChain(Mono.defer(() -> {
            ServerHttpResponse response = exchange.getResponse();
            response.setStatusCode(HttpStatus.OK);
            response.getHeaders().setContentType(MediaType.TEXT_PLAIN);
            return response.writeWith(Mono.just(response.bufferFactory()
                            .wrap("partial".getBytes(StandardCharsets.UTF_8))))
                    .then(Mono.<Void>error(failure));
        }));

        Signal<Void> outcome = filter().filter(exchange, chain).materialize().block();

        // No second write over the committed response; the original failure propagates.
        assertThat(outcome.getThrowable()).isSameAs(failure);
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(exchange.getResponse().getHeaders().getContentType()).isEqualTo(MediaType.TEXT_PLAIN);
        assertThat(exchange.getResponse().getBodyAsString().block()).isEqualTo("partial");
    }

    @Test
    void missingAndMalformedTokensKeepTheirErrorCodes() {
        var missing = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/users/me").build());
        var malformed = protectedExchange("not-a-real-token");
        var chain = new CapturingChain();

        filter().filter(missing, chain).block();
        filter().filter(malformed, chain).block();

        assertThat(chain.wasInvoked()).isFalse();
        assertThat(missing.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(errorCode(missing)).isEqualTo("MISSING_TOKEN");
        assertInvalidToken(malformed, "not-a-real-token");
    }

    @Test
    void expiredTokenIsRejectedAsInvalidToken() {
        String expired = signedToken(UUID.randomUUID(), Instant.now().minus(5, ChronoUnit.MINUTES), null,
                (RSAPrivateKey) keyPair.getPrivate());
        var exchange = protectedExchange(expired);
        var chain = new CapturingChain();

        filter().filter(exchange, chain).block();

        assertThat(chain.wasInvoked()).isFalse();
        assertInvalidToken(exchange, expired);
    }

    @Test
    void tokenSignedWithAnUntrustedKeyIsRejectedAsInvalidToken() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        String forged = signedToken(UUID.randomUUID(), Instant.now().plus(15, ChronoUnit.MINUTES), null,
                (RSAPrivateKey) generator.generateKeyPair().getPrivate());
        var exchange = protectedExchange(forged);
        var chain = new CapturingChain();

        filter().filter(exchange, chain).block();

        assertThat(chain.wasInvoked()).isFalse();
        assertInvalidToken(exchange, forged);
    }

    @Test
    void signingKeyLookupFailureKeepsTheInvalidTokenMapping() {
        // Key resolution is part of validation, so its failures stay INVALID_TOKEN as before.
        String token = validToken(UUID.randomUUID(), "rider@parkio.test", List.of("USER"));
        var exchange = protectedExchange(token);
        var chain = new CapturingChain();

        filter(keyId -> Mono.error(new IllegalStateException("JWKS endpoint unavailable")))
                .filter(exchange, chain).block();

        assertThat(chain.wasInvoked()).isFalse();
        assertInvalidToken(exchange, token);
    }

    @Test
    void emptyValidationResultIsRejectedAsInvalidToken() {
        String token = validToken(UUID.randomUUID(), "rider@parkio.test", List.of("USER"));
        var exchange = protectedExchange(token);
        var chain = new CapturingChain();

        filter(keyId -> Mono.empty()).filter(exchange, chain).block();

        assertThat(chain.wasInvoked()).isFalse();
        assertInvalidToken(exchange, token);
    }

    @Test
    void sessionEpochClaimIsStashedAsGatewayOnlyAttribute() {
        String token = signedToken(UUID.randomUUID(), Instant.now().plus(15, ChronoUnit.MINUTES), 7L,
                (RSAPrivateKey) keyPair.getPrivate());
        var chain = new CapturingChain();

        filter().filter(protectedExchange(token), chain).block();

        assertThat(chain.captured().<Long>getAttribute(GatewayHeaders.TOKEN_SESSION_EPOCH_ATTRIBUTE)).isEqualTo(7L);
        assertThat(chain.captured().getRequest().getHeaders().keySet())
                .noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("epoch"));
    }

    @Test
    void legacyTokenWithoutSessionEpochLeavesTheAttributeUnset() {
        var chain = new CapturingChain();

        filter().filter(protectedExchange(validToken(UUID.randomUUID(), "rider@parkio.test", List.of("USER"))),
                chain).block();

        assertThat(chain.wasInvoked()).isTrue();
        assertThat(chain.captured().getAttributes()).doesNotContainKey(GatewayHeaders.TOKEN_SESSION_EPOCH_ATTRIBUTE);
    }

    private static AuthenticationGlobalFilter filter() {
        return filter(new GatewayPublicSurfaceProperties());
    }

    private static AuthenticationGlobalFilter filter(GatewayPublicSurfaceProperties publicSurface) {
        RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
        JwksKeyResolver resolver = keyId -> KEY_ID.equals(keyId)
                ? Mono.just(publicKey)
                : Mono.error(new JwtException("Unknown JWT key id"));
        return filter(publicSurface, resolver);
    }

    private static AuthenticationGlobalFilter filter(JwksKeyResolver resolver) {
        return filter(new GatewayPublicSurfaceProperties(), resolver);
    }

    private static AuthenticationGlobalFilter filter(GatewayPublicSurfaceProperties publicSurface,
                                                     JwksKeyResolver resolver) {
        JwtProperties properties = new JwtProperties();
        properties.setIssuer(ISSUER);
        properties.setAudience(AUDIENCE);
        properties.setJwksUri("http://unused.test/jwks");
        JwtTokenValidator validator = new JwtTokenValidator(
                properties, resolver, new ObjectMapper().findAndRegisterModules());
        return new AuthenticationGlobalFilter(
                new PublicEndpoints(publicSurface),
                validator,
                new GatewayErrorResponseWriter(
                        new ObjectMapper().findAndRegisterModules(),
                        Clock.fixed(Instant.parse("2026-06-07T00:00:00Z"), ZoneOffset.UTC)));
    }

    private static MockServerWebExchange protectedExchange(String token) {
        return MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/users/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .build());
    }

    private static void assertInvalidToken(MockServerWebExchange exchange, String token) {
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(errorCode(exchange)).isEqualTo("INVALID_TOKEN");
        assertThat(exchange.getResponse().getBodyAsString().block()).doesNotContain(token);
    }

    private static String errorCode(MockServerWebExchange exchange) {
        try {
            return new ObjectMapper().readTree(exchange.getResponse().getBodyAsString().block())
                    .path("code").asText();
        } catch (IOException ex) {
            throw new AssertionError("Response body is not JSON", ex);
        }
    }

    private static String signedToken(UUID userId, Instant expiresAt, Long sessionEpoch, RSAPrivateKey signingKey) {
        var builder = Jwts.builder()
                .header().keyId(KEY_ID).and()
                .issuer(ISSUER)
                .audience().add(AUDIENCE).and()
                .subject(userId.toString())
                .claim("roles", List.of("USER"))
                .claim("status", "ACTIVE")
                .issuedAt(Date.from(expiresAt.minus(15, ChronoUnit.MINUTES)))
                .expiration(Date.from(expiresAt));
        if (sessionEpoch != null) {
            builder.claim("session_epoch", sessionEpoch);
        }
        return builder.signWith(signingKey, Jwts.SIG.RS256).compact();
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

        private final Mono<Void> result;
        private ServerWebExchange captured;

        CapturingChain() {
            this(Mono.empty());
        }

        /** {@code result} stands in for the rest of the chain (later filters, routing, downstream). */
        CapturingChain(Mono<Void> result) {
            this.result = result;
        }

        @Override
        public Mono<Void> filter(ServerWebExchange exchange) {
            this.captured = exchange;
            return result;
        }

        boolean wasInvoked() {
            return captured != null;
        }

        ServerWebExchange captured() {
            return captured;
        }
    }
}
