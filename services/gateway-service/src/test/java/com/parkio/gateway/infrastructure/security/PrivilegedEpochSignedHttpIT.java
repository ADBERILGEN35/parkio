package com.parkio.gateway.infrastructure.security;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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
import com.parkio.gateway.application.waitlist.WaitlistAdminCounts;
import com.parkio.gateway.application.waitlist.WaitlistAdminPage;
import com.parkio.gateway.application.waitlist.WaitlistInterestRepository;
import com.parkio.gateway.application.waitlist.WaitlistRateLimiter;
import com.parkio.gateway.infrastructure.client.UserStatusClient;
import com.parkio.gateway.infrastructure.client.UserStatusLookup;
import com.sun.net.httpserver.HttpServer;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/**
 * Signed JWT through the live gateway HTTP stack against a loopback auth stub.
 * Filter-only {@link PrivilegedEpochContractTest} is not this proof.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = GatewayServiceApplication.class)
@AutoConfigureWebTestClient
@ActiveProfiles("test")
class PrivilegedEpochSignedHttpIT {

    private static String encodeModulus(RSAPublicKey publicKey) {
        return encodeBigInt(publicKey.getModulus());
    }

    private static String encodeExponent(RSAPublicKey publicKey) {
        return encodeBigInt(publicKey.getPublicExponent());
    }

    private static String encodeBigInt(BigInteger value) {
        byte[] bytes = value.toByteArray();
        int offset = bytes.length > 1 && bytes[0] == 0 ? 1 : 0;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(java.util.Arrays.copyOfRange(bytes, offset, bytes.length));
    }

    private static final String ADMIN_ID = "11111111-1111-1111-1111-111111111111";
    private static final HttpServer AUTH;
    private static final JwtService ISSUER;
    private static final String JWKS_JSON;
    private static final AtomicInteger epochStatus = new AtomicInteger(200);
    private static final AtomicReference<String> epochBody = new AtomicReference<>(
            "{\"userId\":\"" + ADMIN_ID + "\",\"sessionEpoch\":0}");

    static {
        try {
            JwtProperties jwt = new JwtProperties();
            jwt.setIssuer("parkio-auth-test");
            jwt.setAudience("parkio-api");
            jwt.setAccessTokenTtl(Duration.ofMinutes(15));
            jwt.setGenerateEphemeralKey(true);
            RsaKeyProvider keys = new RsaKeyProvider(jwt);
            ISSUER = new JwtService(jwt, keys, Clock.systemUTC(), new ObjectMapper());
            RSAPublicKey publicKey = keys.publicKey();
            JWKS_JSON = "{\"keys\":[{\"kty\":\"RSA\",\"kid\":\"" + keys.keyId()
                    + "\",\"use\":\"sig\",\"alg\":\"RS256\",\"n\":\"" + encodeModulus(publicKey)
                    + "\",\"e\":\"" + encodeExponent(publicKey) + "\"}]}";
            AUTH = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            AUTH.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                String payload;
                int code;
                if (path.endsWith("/jwks.json")) {
                    payload = JWKS_JSON;
                    code = 200;
                } else if (path.contains("/session-epoch")) {
                    payload = epochBody.get();
                    code = epochStatus.get();
                } else {
                    payload = "";
                    code = 404;
                }
                byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(code, bytes.length == 0 ? -1 : bytes.length);
                if (bytes.length > 0) {
                    exchange.getResponseBody().write(bytes);
                }
                exchange.close();
            });
            AUTH.setExecutor(java.util.concurrent.Executors.newCachedThreadPool(runnable -> {
                Thread thread = new Thread(runnable, "privileged-epoch-auth-stub");
                thread.setDaemon(true);
                return thread;
            }));
            AUTH.start();
        } catch (Exception ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    @DynamicPropertySource
    static void authStub(DynamicPropertyRegistry registry) {
        String base = "http://127.0.0.1:" + AUTH.getAddress().getPort();
        registry.add("parkio.security.jwt.jwks-uri", () -> base + "/api/v1/auth/.well-known/jwks.json");
        registry.add("parkio.gateway.session-epoch.base-url", () -> base);
        registry.add("parkio.gateway.session-epoch.cache-ttl", () -> "PT0S");
        registry.add("parkio.gateway.session-epoch.request-timeout", () -> "PT5S");
        registry.add("spring.datasource.url",
                () -> "jdbc:h2:mem:parkio_gateway_epoch_http_it;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
    }

    @AfterAll
    static void stopAuth() {
        AUTH.stop(0);
    }

    @Autowired
    private WebTestClient webTestClient;

    @MockBean
    private UserStatusClient userStatusClient;

    @MockBean
    private WaitlistInterestRepository repository;

    @MockBean
    private WaitlistRateLimiter rateLimiter;

    @BeforeEach
    void stubs() {
        when(userStatusClient.fetchStatus(anyString())).thenReturn(Mono.just(UserStatusLookup.found("ACTIVE")));
        when(repository.exportConfirmed(any(), any())).thenReturn(List.of());
        when(rateLimiter.check(anyString(), anyString())).thenReturn(Mono.empty());
        when(repository.countByStatus()).thenReturn(new WaitlistAdminCounts(0, 0, 0, 0));
        when(repository.findAdminPage(any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new WaitlistAdminPage(List.of(), 0, 20, 0, 0));
        epochStatus.set(200);
        epochBody.set("{\"userId\":\"" + ADMIN_ID + "\",\"sessionEpoch\":0}");
    }

    @Test
    void signedEpochZeroTokenIsAccepted() {
        getExport(token(ADMIN_ID, 0)).expectStatus().isOk();
    }

    @Test
    void missingJwtIsUnauthorized() {
        webTestClient.get().uri("/api/v1/waitlist/export").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void malformedJwtIsUnauthorized() {
        getExport("not.a.jwt").expectStatus().isUnauthorized();
    }

    @Test
    void unrelatedUserIsForbidden() {
        getExport(token("22222222-2222-2222-2222-222222222222", 0, RoleName.USER))
                .expectStatus().isForbidden();
    }

    @Test
    void missingEpochFieldFailsClosed() {
        epochBody.set("{\"userId\":\"" + ADMIN_ID + "\"}");
        getExport(token(ADMIN_ID, 0)).expectStatus().isEqualTo(503)
                .expectBody().jsonPath("$.code").isEqualTo("SESSION_EPOCH_UNAVAILABLE");
    }

    @Test
    void nullEpochFailsClosed() {
        epochBody.set("{\"userId\":\"" + ADMIN_ID + "\",\"sessionEpoch\":null}");
        getExport(token(ADMIN_ID, 0)).expectStatus().isEqualTo(503);
    }

    @Test
    void emptyEpochBodyFailsClosed() {
        epochBody.set("");
        getExport(token(ADMIN_ID, 0)).expectStatus().isEqualTo(503);
    }

    @Test
    void malformedEpochBodyFailsClosed() {
        epochBody.set("{\"userId\":\"" + ADMIN_ID + "\",\"sessionEpoch\":");
        getExport(token(ADMIN_ID, 0)).expectStatus().isEqualTo(503);
    }

    @Test
    void identityMismatchFailsClosed() {
        epochBody.set("{\"userId\":\"22222222-2222-2222-2222-222222222222\",\"sessionEpoch\":0}");
        getExport(token(ADMIN_ID, 0)).expectStatus().isEqualTo(503);
    }

    @Test
    void staleSignedTokenIsRevokedAfterEpochBump() {
        epochBody.set("{\"userId\":\"" + ADMIN_ID + "\",\"sessionEpoch\":1}");
        getExport(token(ADMIN_ID, 0)).expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.code").isEqualTo("TOKEN_REVOKED");
        getExport(token(ADMIN_ID, 1)).expectStatus().isOk();
    }

    private WebTestClient.ResponseSpec getExport(String token) {
        return webTestClient.get().uri("/api/v1/waitlist/export")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange();
    }

    private static String token(String userId, long epoch) {
        return token(userId, epoch, RoleName.ADMIN);
    }

    private static String token(String userId, long epoch, RoleName role) {
        Instant now = Instant.now();
        AuthUser user = new AuthUser(UUID.fromString(userId), "synthetic@parkio.example",
                "synthetic-hash", AuthUserStatus.ACTIVE, null, true, now,
                null, null, null, EmailLocale.TR, epoch,
                Set.of(new Role(UUID.randomUUID(), role)), now, null);
        return ISSUER.issue(user).token();
    }
}
