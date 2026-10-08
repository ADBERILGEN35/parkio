package com.parkio.auth.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.auth.application.AuthApplicationService;
import com.parkio.auth.application.LoginFailureTracker;
import com.parkio.auth.application.command.RegisterCommand;
import com.parkio.auth.application.command.VerifyEmailCommand;
import com.parkio.auth.application.port.EmailVerificationSender;
import com.parkio.auth.application.port.RefreshTokenHasher;
import com.parkio.auth.application.port.RefreshTokenRepository;
import com.parkio.auth.domain.RefreshToken;
import com.parkio.auth.domain.RefreshTokenRevocationReason;
import com.parkio.auth.domain.RoleName;
import com.parkio.auth.infrastructure.persistence.entity.RoleEntity;
import com.parkio.auth.infrastructure.persistence.jpa.RoleJpaRepository;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Opt-in Chromium acceptance for cookie CSRF / SameSite behavior (GHAS alert #7 evidence).
 *
 * <p>Enable with {@code PARKIO_CSRF_BROWSER=1} plus a resolvable {@code @playwright/test}
 * (typically {@code frontend/apps/web} after {@code pnpm install} and
 * {@code pnpm exec playwright install chromium}). Default CI leaves the flag unset; this
 * test then aborts with an explicit limitation rather than claiming a browser pass.
 *
 * <p><b>Proven when enabled:</b> Chromium stores Secure/HttpOnly/SameSite=Strict refresh
 * cookies from a real auth HTTP port; allowed-origin refresh/logout succeed; a
 * {@code 127.0.0.1} attacker document does not attach the localhost Strict cookie
 * (verified from the server-received Cookie header); forged
 * {@code X-Parkio-Client: mobile} with a browser Origin stays on the cookie path; rejected
 * cross-site attempts leave the refresh row for legitimate logout (no reuse/epoch bump).
 *
 * <p><b>Not proven:</b> gateway CORS/credentials, TLS sibling hosts, production
 * non-exposure of {@code X-Gateway-Auth} (lab pages inject the test secret). Missing Origin
 * remains MockMvc-only ({@link CookieCsrfGuardHttpIntegrationTest}) because Chromium always
 * sends Origin on cross-origin {@code fetch}. Auth-service has no production CORS bean —
 * browsers reach auth through the gateway — so this IT installs a <em>test-only</em>
 * lab CORS filter (before {@code GatewayAuthFilter}) so Chromium can observe HTTP status
 * codes directly without claiming gateway-edge CORS proof.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(CookieCsrfChromiumAcceptanceIT.LabCorsConfiguration.class)
class CookieCsrfChromiumAcceptanceIT {

    private static final String GATEWAY_SECRET =
            "test-only-parkio-gateway-internal-secret-0123456789";
    private static final String PASSWORD = "StrongerPass123";
    private static final LabOrigins LAB = LabOrigins.start();

    @LocalServerPort
    private int authPort;

    @Autowired
    private AuthApplicationService authService;

    @Autowired
    private RefreshTokenRepository refreshTokens;

    @Autowired
    private RefreshTokenHasher refreshTokenHasher;

    @Autowired
    private RoleJpaRepository roles;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private LoginFailureTracker loginFailureTracker;

    @MockBean
    private EmailVerificationSender emailVerificationSender;

    @DynamicPropertySource
    static void labProperties(DynamicPropertyRegistry registry) {
        registry.add("parkio.security.refresh-cookie.allowed-origins", LAB::appOrigin);
        registry.add("parkio.security.refresh-cookie.secure", () -> "true");
        registry.add("parkio.security.refresh-cookie.same-site", () -> "Strict");
        registry.add("parkio.gateway.internal-secret", () -> GATEWAY_SECRET);
    }

    @AfterAll
    static void stopLabOrigins() {
        LAB.close();
    }

    @BeforeEach
    void seedUserRole() {
        if (roles.findByName(RoleName.USER).isEmpty()) {
            roles.save(new RoleEntity(UUID.randomUUID(), RoleName.USER));
        }
    }

    @Test
    void chromiumCookieOriginAndSameSiteAcceptance() throws Exception {
        assumeTrue(
                "1".equals(System.getenv("PARKIO_CSRF_BROWSER")),
                () -> """
                        PARKIO_CSRF_BROWSER is not 1 — Chromium CSRF/SameSite acceptance skipped.
                        Limitation: default CI does not install Playwright Chromium or bind the
                        localhost vs 127.0.0.1 origin pair. Server-side Origin/Referer evidence
                        remains in CookieCsrfGuardHttpIntegrationTest (#125). Gateway CORS and
                        TLS sibling-subdomain behavior remain unverified by this class.
                        """);

        Path playwrightPackage = resolvePlaywrightPackage();
        assertThat(playwrightPackage)
                .as("@playwright/test missing at resolved package path; install pnpm and Chromium")
                .isNotNull();

        String email = "csrf-browser-" + UUID.randomUUID() + "@example.com";
        registerAndVerify(email);

        String authBase = "http://localhost:" + authPort;
        LAB.rewritePages(authBase, GATEWAY_SECRET);

        LAB.clearCrossSitePosts();
        Path resultFile = Files.createTempFile("parkio-csrf-chromium-", ".json");
        Path script = repoRoot().resolve("scripts/auth-csrf-chromium-acceptance.mjs");
        assertThat(script).exists();

        List<String> command = new ArrayList<>();
        command.add(nodeExecutable());
        command.add(script.toAbsolutePath().toString());

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(repoRoot().toFile());
        pb.redirectErrorStream(true);
        Map<String, String> env = pb.environment();
        env.put("PARKIO_CSRF_BROWSER", "1");
        env.put("PARKIO_CSRF_AUTH_BASE", authBase);
        env.put("PARKIO_CSRF_GATEWAY_SECRET", GATEWAY_SECRET);
        env.put("PARKIO_CSRF_APP_ORIGIN", LAB.appOrigin());
        env.put("PARKIO_CSRF_EVIL_ORIGIN", LAB.evilOrigin());
        env.put("PARKIO_CSRF_EMAIL", email);
        env.put("PARKIO_CSRF_PASSWORD", PASSWORD);
        env.put("PARKIO_CSRF_RESULT_FILE", resultFile.toAbsolutePath().toString());
        env.put("PARKIO_CSRF_PLAYWRIGHT_PACKAGE", playwrightPackage.toAbsolutePath().toString());

        Process process = pb.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        boolean finished = process.waitFor(Duration.ofMinutes(3).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        assertThat(finished).as("chromium harness timed out; output=%s", output).isTrue();
        assertThat(process.exitValue())
                .as("chromium harness failed; output=%s result=%s", output, Files.readString(resultFile))
                .isZero();

        JsonNode result = objectMapper.readTree(Files.readString(resultFile));
        assertThat(result.path("status").asText()).isEqualTo("passed");
        assertThat(result.path("checks").path("cookieSecure").asBoolean()).isTrue();
        assertThat(result.path("checks").path("cookieHttpOnly").asBoolean()).isTrue();
        assertThat(result.path("checks").path("allowedOriginRefreshWithoutCsrf").asInt()).isEqualTo(403);
        assertThat(result.path("checks").path("allowedOriginRefresh").asInt()).isEqualTo(200);
        assertThat(result.path("checks").path("allowedOriginLogout").asInt()).isEqualTo(204);
        assertThat(result.path("checks").path("forgedMobileWithOrigin").asInt()).isEqualTo(200);
        assertThat(result.path("checks").path("crossSiteRefreshStatus").asInt()).isNotEqualTo(200);
        assertThat(result.path("checks").path("crossSiteLogoutStatus").asInt())
                .isNotIn(200, 204);

        List<LabRequest> crossSitePosts = LAB.crossSitePosts();
        assertThat(crossSitePosts).extracting(LabRequest::path)
                .contains("/api/v1/auth/refresh-token", "/api/v1/auth/logout");
        assertThat(crossSitePosts).allSatisfy(post ->
                assertThat(post.cookieHeader())
                        .as("server-received Cookie on cross-site %s", post.path())
                        .doesNotContain("parkio_refresh="));

        String rawBeforeCrossSite = result.path("refreshCookieValuesBeforeCrossSite").get(0).asText();
        RefreshToken token = refreshTokens.findByTokenHash(refreshTokenHasher.hash(rawBeforeCrossSite))
                .orElseThrow();
        assertThat(token.isRevoked()).isTrue();
        assertThat(token.revokedReason())
                .as("cross-site must not rotate; legitimate logout should revoke the same row")
                .isEqualTo(RefreshTokenRevocationReason.LOGOUT);

        UUID userId = token.userId();
        assertThat(authService.sessionEpoch(userId))
                .as("cross-site rejection must not bump session epoch via reuse detection")
                .isZero();
    }

    private void registerAndVerify(String email) {
        clearInvocations(emailVerificationSender);
        authService.register(new RegisterCommand(email, PASSWORD));
        ArgumentCaptor<String> tokenCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailVerificationSender, atLeastOnce()).sendVerificationLink(eq(email), tokenCaptor.capture(), any());
        authService.verifyEmail(new VerifyEmailCommand(tokenCaptor.getValue()));
    }

    private static Path repoRoot() {
        Path cwd = Path.of("").toAbsolutePath().normalize();
        Path probe = cwd.resolve("scripts/auth-csrf-chromium-acceptance.mjs");
        if (Files.exists(probe)) {
            return cwd;
        }
        Path fromModule = cwd.resolve("../..").normalize();
        if (Files.exists(fromModule.resolve("scripts/auth-csrf-chromium-acceptance.mjs"))) {
            return fromModule;
        }
        throw new IllegalStateException("cannot locate repo root from " + cwd);
    }

    private static Path resolvePlaywrightPackage() {
        String resolved = System.getenv("PARKIO_CSRF_PLAYWRIGHT_PACKAGE");
        if (resolved != null && !resolved.isBlank()) {
            Path candidate = Path.of(resolved).toAbsolutePath().normalize();
            return Files.isDirectory(candidate) ? candidate : null;
        }
        Path candidate = repoRoot().resolve("frontend/apps/web/node_modules/@playwright/test");
        return Files.isDirectory(candidate) ? candidate : null;
    }

    private static String nodeExecutable() {
        String fromEnv = System.getenv("PARKIO_CSRF_NODE");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv;
        }
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? "node.exe" : "node";
    }

    /**
     * Test-only CORS filter placed before {@code GatewayAuthFilter} so Chromium
     * preflights (no {@code X-Gateway-Auth}) and credentialed responses are readable.
     * Production browsers never call auth without the gateway CORS edge; allowing the
     * evil lab origin here is intentional so SameSite omission and Origin rejection are
     * observable as HTTP statuses rather than opaque network errors.
     */
    @TestConfiguration
    static class LabCorsConfiguration {
        @Bean
        LabCorsFilter labCorsFilter() {
            return new LabCorsFilter();
        }
    }

    @Order(Ordered.HIGHEST_PRECEDENCE)
    static final class LabCorsFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request,
                                        HttpServletResponse response,
                                        FilterChain filterChain)
                throws ServletException, IOException {
            String origin = request.getHeader("Origin");
            if ("POST".equalsIgnoreCase(request.getMethod())
                    && LAB.evilOrigin().equals(origin)
                    && ("/api/v1/auth/refresh-token".equals(request.getRequestURI())
                        || "/api/v1/auth/logout".equals(request.getRequestURI()))) {
                LAB.recordCrossSitePost(request.getRequestURI(), request.getHeader("Cookie"));
            }
            if (origin != null
                    && (origin.equals(LAB.appOrigin()) || origin.equals(LAB.evilOrigin()))) {
                response.setHeader("Access-Control-Allow-Origin", origin);
                response.setHeader("Access-Control-Allow-Credentials", "true");
                response.setHeader("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
                // Stands in for the gateway, whose CORS policy allows every request header;
                // X-XSRF-TOKEN is the CSRF double-submit header of the cookie transport.
                response.setHeader(
                        "Access-Control-Allow-Headers",
                        "Content-Type, X-Gateway-Auth, X-Parkio-Client, X-XSRF-TOKEN, Authorization");
                response.setHeader("Vary", "Origin");
                if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
                    response.setStatus(HttpServletResponse.SC_OK);
                    return;
                }
            }
            filterChain.doFilter(request, response);
        }
    }

    private record LabRequest(String path, String cookieHeader) {
    }

    private static final class LabOrigins implements AutoCloseable {
        private final HttpServer app;
        private final HttpServer evil;
        private final String appOrigin;
        private final String evilOrigin;
        private final List<LabRequest> crossSitePosts = new CopyOnWriteArrayList<>();
        private volatile byte[] appHtml;
        private volatile byte[] evilHtml;

        private LabOrigins(HttpServer app, HttpServer evil, String appOrigin, String evilOrigin) {
            this.app = app;
            this.evil = evil;
            this.appOrigin = appOrigin;
            this.evilOrigin = evilOrigin;
            this.appHtml = placeholderHtml("app");
            this.evilHtml = placeholderHtml("evil");
        }

        static LabOrigins start() {
            try {
                HttpServer app = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
                HttpServer evil = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                LabOrigins lab = new LabOrigins(
                        app,
                        evil,
                        "http://localhost:" + app.getAddress().getPort(),
                        "http://127.0.0.1:" + evil.getAddress().getPort());
                app.createContext("/", exchange -> write(exchange, lab.appHtml));
                evil.createContext("/", exchange -> write(exchange, lab.evilHtml));
                app.setExecutor(Executors.newCachedThreadPool());
                evil.setExecutor(Executors.newCachedThreadPool());
                app.start();
                evil.start();
                return lab;
            } catch (IOException ex) {
                throw new IllegalStateException("failed to bind CSRF lab origins", ex);
            }
        }

        String appOrigin() {
            return appOrigin;
        }

        String evilOrigin() {
            return evilOrigin;
        }

        void clearCrossSitePosts() {
            crossSitePosts.clear();
        }

        void recordCrossSitePost(String path, String cookieHeader) {
            crossSitePosts.add(new LabRequest(path, cookieHeader == null ? "" : cookieHeader));
        }

        List<LabRequest> crossSitePosts() {
            return List.copyOf(crossSitePosts);
        }

        void rewritePages(String authBase, String gatewaySecret) {
            byte[] html = pageHtml(authBase, gatewaySecret).getBytes(StandardCharsets.UTF_8);
            this.appHtml = html;
            this.evilHtml = html;
        }

        @Override
        public void close() {
            app.stop(0);
            evil.stop(0);
        }

        private static void write(com.sun.net.httpserver.HttpExchange exchange, byte[] body) throws IOException {
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        }

        private static byte[] placeholderHtml(String title) {
            return ("<!doctype html><html><body>" + title + "</body></html>")
                    .getBytes(StandardCharsets.UTF_8);
        }

        private static String pageHtml(String authBase, String gatewaySecret) {
            return """
                    <!doctype html><html><body>
                    <h1>parkio-csrf-lab</h1>
                    <script>
                    window.__parkio = {
                      authBase: %s,
                      gatewaySecret: %s,
                      async call(path, { headers = {}, body, method = 'POST' } = {}) {
                        const response = await fetch(this.authBase + path, {
                          method,
                          credentials: 'include',
                          headers: {
                            'content-type': 'application/json',
                            'X-Gateway-Auth': this.gatewaySecret,
                            ...headers,
                          },
                          body: body === undefined ? undefined : JSON.stringify(body),
                        });
                        const text = await response.text();
                        let json = null;
                        try { json = text ? JSON.parse(text) : null; } catch { json = { raw: text }; }
                        return { status: response.status, json };
                      }
                    };
                    </script>
                    </body></html>
                    """.formatted(jsonString(authBase), jsonString(gatewaySecret));
        }

        private static String jsonString(String value) {
            try {
                return new ObjectMapper().writeValueAsString(value);
            } catch (IOException ex) {
                throw new IllegalStateException(ex);
            }
        }
    }
}
