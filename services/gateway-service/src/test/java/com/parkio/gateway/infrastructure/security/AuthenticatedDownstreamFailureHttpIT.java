package com.parkio.gateway.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.auth.domain.AuthUser;
import com.parkio.auth.domain.AuthUserStatus;
import com.parkio.auth.domain.EmailLocale;
import com.parkio.auth.domain.Role;
import com.parkio.auth.domain.RoleName;
import com.parkio.auth.infrastructure.security.JwtProperties;
import com.parkio.auth.infrastructure.security.JwtService;
import com.parkio.auth.infrastructure.security.RsaKeyProvider;
import com.parkio.gateway.GatewayServiceApplication;
import com.parkio.gateway.shared.GatewayHeaders;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.jsonwebtoken.Jwts;
import java.io.IOException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/**
 * U11 (CX-F06): once a JWT has been validated, a failure of the routed downstream call is
 * not an authentication failure. Signed tokens go through the live gateway HTTP stack —
 * JWKS validation, session-epoch and account-status lookups, route filters and Netty
 * routing — against a loopback stub. Downstream 4xx/5xx pass through unchanged, transport
 * failures surface as 5xx/504 rather than {@code 401 INVALID_TOKEN} (which clients answer
 * with a token refresh and a replay), and genuine authentication, revocation and
 * account-status outcomes keep their codes. Only the Redis rate limiter is stubbed (it
 * admits every request).
 *
 * <p>Every test signs in as fresh identities, and so does every request whose outcome must
 * come from a new lookup. The gateway caches resolved epochs and statuses per user and
 * compares wall-clock instants, so a zero cache TTL does not isolate requests if the host
 * clock steps backwards: an epoch or status cached by an earlier request would then answer
 * instead of the stub.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = GatewayServiceApplication.class)
@AutoConfigureWebTestClient(timeout = "PT30S")
@ActiveProfiles("test")
class AuthenticatedDownstreamFailureHttpIT {

    private static final String CORRELATION_ID = "u11-downstream-failure";
    // Also bounds the routing client's first connection (pool set-up): generous enough that a
    // slow machine's cold start is never mistaken for a downstream timeout.
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(3);
    private static final HttpServer STUB;
    private static final int CLOSED_PORT;
    private static final RsaKeyProvider KEYS;
    private static final JwtService ISSUER;
    private static final JwtService EXPIRED_ISSUER;
    private static final String JWKS_JSON;
    private static final AtomicInteger epochStatus = new AtomicInteger(200);
    private static final AtomicLong currentEpoch = new AtomicLong(0);
    private static final AtomicInteger statusLookupStatus = new AtomicInteger(200);
    private static final AtomicReference<String> accountStatus = new AtomicReference<>("ACTIVE");
    private static final AtomicInteger downstreamCalls = new AtomicInteger();
    private static final Map<String, String> downstreamHeaders = new ConcurrentHashMap<>();

    static {
        try {
            JwtProperties jwt = new JwtProperties();
            jwt.setIssuer("parkio-auth-test");
            jwt.setAudience("parkio-api");
            jwt.setAccessTokenTtl(Duration.ofMinutes(15));
            jwt.setGenerateEphemeralKey(true);
            KEYS = new RsaKeyProvider(jwt);
            ISSUER = new JwtService(jwt, KEYS, Clock.systemUTC(), new ObjectMapper());
            EXPIRED_ISSUER = new JwtService(jwt, KEYS,
                    Clock.offset(Clock.systemUTC(), Duration.ofHours(-1)), new ObjectMapper());
            RSAPublicKey publicKey = KEYS.publicKey();
            JWKS_JSON = "{\"keys\":[{\"kty\":\"RSA\",\"kid\":\"" + KEYS.keyId()
                    + "\",\"use\":\"sig\",\"alg\":\"RS256\",\"n\":\"" + base64Url(publicKey.getModulus())
                    + "\",\"e\":\"" + base64Url(publicKey.getPublicExponent()) + "\"}]}";
            STUB = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            STUB.createContext("/", AuthenticatedDownstreamFailureHttpIT::handle);
            STUB.setExecutor(Executors.newCachedThreadPool(runnable -> {
                Thread thread = new Thread(runnable, "downstream-failure-stub");
                thread.setDaemon(true);
                return thread;
            }));
            STUB.start();
            // Bound, then released: nothing listens there, so a connect is refused at once.
            // (Reactor Netty only parses 2-5 digit ports, so a port like :1 would not do.)
            try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
                CLOSED_PORT = socket.getLocalPort();
            }
        } catch (Exception ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    @DynamicPropertySource
    static void stubs(DynamicPropertyRegistry registry) {
        String base = "http://127.0.0.1:" + STUB.getAddress().getPort();
        registry.add("parkio.security.jwt.jwks-uri", () -> base + "/api/v1/auth/.well-known/jwks.json");
        registry.add("parkio.gateway.session-epoch.base-url", () -> base);
        registry.add("parkio.gateway.session-epoch.cache-ttl", () -> "PT0S");
        registry.add("parkio.gateway.user-status.base-url", () -> base);
        registry.add("parkio.gateway.user-status.cache-ttl", () -> "PT0S");
        // Route targets and the global response budget, through the variables operators set:
        // gamification and the admin API reach the stub; notifications hit a closed port.
        registry.add("PARKIO_GAMIFICATION_SERVICE_URI", () -> base);
        registry.add("PARKIO_AUTH_SERVICE_URI", () -> base);
        registry.add("PARKIO_NOTIFICATION_SERVICE_URI", () -> "http://127.0.0.1:" + CLOSED_PORT);
        registry.add("PARKIO_GATEWAY_DOWNSTREAM_RESPONSE_TIMEOUT", RESPONSE_TIMEOUT::toString);
        registry.add("spring.datasource.url",
                () -> "jdbc:h2:mem:parkio_gateway_downstream_failure_http_it;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
    }

    @AfterAll
    static void stopStub() {
        STUB.stop(0);
    }

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private RedisRateLimiter redisRateLimiter;

    @Value("${parkio.gateway.internal-secret}")
    private String internalSecret;

    private String userId;
    private String adminId;

    @BeforeEach
    void resetStub() {
        when(redisRateLimiter.isAllowed(anyString(), anyString()))
                .thenReturn(Mono.just(new RateLimiter.Response(true, Map.of())));
        epochStatus.set(200);
        currentEpoch.set(0);
        statusLookupStatus.set(200);
        accountStatus.set("ACTIVE");
        downstreamCalls.set(0);
        downstreamHeaders.clear();
        userId = freshId();
        adminId = freshId();
    }

    @Test
    void authenticatedRequestReachesTheDownstreamWithTheVerifiedIdentity() {
        get("/api/v1/gamification/profile", token(userId, 0, RoleName.USER))
                .expectStatus().isOk()
                .expectBody().jsonPath("$.userId").isEqualTo(userId);

        assertThat(downstreamCalls).hasValue(1);
        assertThat(downstreamHeaders)
                .containsEntry(GatewayHeaders.USER_ID, userId)
                .containsEntry(GatewayHeaders.USER_ROLES, "USER")
                .containsEntry(GatewayHeaders.GATEWAY_AUTH, internalSecret);
    }

    @ParameterizedTest(name = "downstream {0} {1} passes through")
    @CsvSource({"403, BADGE_NOT_OWNED", "404, BADGE_NOT_FOUND", "500, INTERNAL_ERROR", "503, SERVICE_UNAVAILABLE"})
    void downstreamErrorResponsesPassThroughUnchanged(int status, String code) {
        get("/api/v1/gamification/respond/" + status + "/" + code, token(userId, 0, RoleName.USER))
                .expectStatus().isEqualTo(status)
                .expectBody()
                .jsonPath("$.code").isEqualTo(code)
                .jsonPath("$.message").isEqualTo("Downstream business response.");

        assertThat(downstreamCalls).hasValue(1);
    }

    @Test
    void refusedDownstreamConnectionSurfacesAsServerErrorNotInvalidToken() {
        String token = token(userId, 0, RoleName.USER);

        String body = get("/api/v1/notifications", token)
                .expectStatus().isEqualTo(500)
                .expectHeader().valueEquals(GatewayHeaders.CORRELATION_ID, CORRELATION_ID)
                .expectBody(String.class).returnResult().getResponseBody();

        assertNoAuthenticationErrorOrLeak(body, token);
    }

    @Test
    void downstreamResponseTimeoutSurfacesAsGatewayTimeoutNotInvalidToken() {
        String token = token(userId, 0, RoleName.USER);

        String body = get("/api/v1/gamification/slow", token)
                .expectStatus().isEqualTo(504)
                .expectHeader().valueEquals(GatewayHeaders.CORRELATION_ID, CORRELATION_ID)
                .expectBody(String.class).returnResult().getResponseBody();

        assertNoAuthenticationErrorOrLeak(body, token);
    }

    @Test
    void genuineAuthenticationFailuresAreStillRejectedBeforeTheDownstream() throws Exception {
        webTestClient.get().uri("/api/v1/gamification/profile").exchange()
                .expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.code").isEqualTo("MISSING_TOKEN");
        for (String rejected : List.of("not.a.jwt", expiredToken(userId), forgedToken(userId))) {
            get("/api/v1/gamification/profile", rejected)
                    .expectStatus().isUnauthorized()
                    .expectBody().jsonPath("$.code").isEqualTo("INVALID_TOKEN");
        }

        assertThat(downstreamCalls).hasValue(0);
    }

    @Test
    void revokedAndUnverifiableSessionsKeepTheirCodes() {
        currentEpoch.set(1);
        get("/api/v1/gamification/profile", token(userId, 0, RoleName.USER))
                .expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.code").isEqualTo("TOKEN_REVOKED");
        get("/api/v1/admin/users", token(adminId, 0, RoleName.ADMIN))
                .expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.code").isEqualTo("TOKEN_REVOKED");

        // A current admin token whose epoch nothing has cached yet; an unavailable lookup is
        // never cached, so the same admin's next request asks auth-service again.
        String recoveringAdminId = freshId();
        epochStatus.set(500);
        get("/api/v1/admin/users", token(recoveringAdminId, 1, RoleName.ADMIN))
                .expectStatus().isEqualTo(503)
                .expectBody().jsonPath("$.code").isEqualTo("SESSION_EPOCH_UNAVAILABLE");
        assertThat(downstreamCalls).hasValue(0);

        epochStatus.set(200);
        get("/api/v1/admin/users", token(recoveringAdminId, 1, RoleName.ADMIN)).expectStatus().isOk();
        assertThat(downstreamCalls).hasValue(1);
    }

    @Test
    void inactiveAndUnverifiableAccountsKeepTheirCodes() {
        accountStatus.set("SUSPENDED");
        get("/api/v1/gamification/profile", token(userId, 0, RoleName.USER))
                .expectStatus().isForbidden()
                .expectBody().jsonPath("$.code").isEqualTo("ACCOUNT_NOT_ACTIVE");

        // Another account, so the SUSPENDED status cached above cannot answer for it.
        accountStatus.set("ACTIVE");
        statusLookupStatus.set(500);
        get("/api/v1/gamification/profile", token(freshId(), 0, RoleName.USER))
                .expectStatus().isEqualTo(503)
                .expectBody().jsonPath("$.code").isEqualTo("USER_STATUS_UNAVAILABLE");

        assertThat(downstreamCalls).hasValue(0);
    }

    private void assertNoAuthenticationErrorOrLeak(String body, String token) {
        assertThat(body).isNotBlank()
                .doesNotContain("INVALID_TOKEN")
                .doesNotContain(token)
                .doesNotContain(internalSecret)
                .doesNotContain("127.0.0.1")
                .doesNotContain("Exception")
                .doesNotContain("\"trace\"")
                .doesNotContain("\"message\"");
    }

    private WebTestClient.ResponseSpec get(String path, String token) {
        return webTestClient.get().uri(path)
                .accept(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header(GatewayHeaders.CORRELATION_ID, CORRELATION_ID)
                .exchange();
    }

    private static String freshId() {
        return UUID.randomUUID().toString();
    }

    private static String token(String userId, long epoch, RoleName role) {
        return issue(ISSUER, userId, epoch, role);
    }

    private static String expiredToken(String userId) {
        return issue(EXPIRED_ISSUER, userId, 0, RoleName.USER);
    }

    /** Correct claims and the trusted {@code kid}, but signed with a key the gateway never published. */
    private static String forgedToken(String userId) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        Instant now = Instant.now();
        return Jwts.builder()
                .header().keyId(KEYS.keyId()).and()
                .issuer("parkio-auth-test")
                .audience().add("parkio-api").and()
                .subject(userId)
                .claim("roles", List.of("USER"))
                .claim("session_epoch", 0)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(Duration.ofMinutes(15))))
                .signWith(generator.generateKeyPair().getPrivate(), Jwts.SIG.RS256)
                .compact();
    }

    private static String issue(JwtService issuer, String userId, long epoch, RoleName role) {
        Instant now = Instant.now();
        AuthUser user = new AuthUser(UUID.fromString(userId), "synthetic@parkio.example",
                "synthetic-hash", AuthUserStatus.ACTIVE, null, true, now,
                null, null, null, EmailLocale.TR, epoch,
                Set.of(new Role(UUID.randomUUID(), role)), now, null);
        return issuer.issue(user).token();
    }

    private static void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String[] segments = path.split("/");
        if (path.endsWith("/jwks.json")) {
            respond(exchange, 200, JWKS_JSON);
        } else if (path.startsWith("/internal/auth/users/") && path.endsWith("/session-epoch")) {
            respond(exchange, epochStatus.get(),
                    "{\"userId\":\"" + segments[4] + "\",\"sessionEpoch\":" + currentEpoch.get() + "}");
        } else if (path.startsWith("/internal/users/") && path.endsWith("/status")) {
            respond(exchange, statusLookupStatus.get(),
                    "{\"userId\":\"" + segments[3] + "\",\"status\":\"" + accountStatus.get() + "\"}");
        } else {
            downstream(exchange, path, segments);
        }
    }

    /** The routed service: records what the gateway forwarded, then answers. */
    private static void downstream(HttpExchange exchange, String path, String[] segments) throws IOException {
        downstreamCalls.incrementAndGet();
        for (String name : List.of(GatewayHeaders.USER_ID, GatewayHeaders.USER_ROLES, GatewayHeaders.GATEWAY_AUTH)) {
            String value = exchange.getRequestHeaders().getFirst(name);
            if (value != null) {
                downstreamHeaders.put(name, value);
            }
        }
        if (path.equals("/api/v1/gamification/slow")) {
            try {
                Thread.sleep(RESPONSE_TIMEOUT.multipliedBy(4).toMillis());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, "{}");
        } else if (path.startsWith("/api/v1/gamification/respond/")) {
            // /respond/{status}/{code}: an error the service itself answers (business outcome).
            respond(exchange, Integer.parseInt(segments[5]),
                    "{\"code\":\"" + segments[6] + "\",\"message\":\"Downstream business response.\"}");
        } else {
            respond(exchange, 200,
                    "{\"userId\":\"" + exchange.getRequestHeaders().getFirst(GatewayHeaders.USER_ID) + "\"}");
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }

    private static String base64Url(BigInteger value) {
        byte[] bytes = value.toByteArray();
        int offset = bytes.length > 1 && bytes[0] == 0 ? 1 : 0;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(Arrays.copyOfRange(bytes, offset, bytes.length));
    }
}
