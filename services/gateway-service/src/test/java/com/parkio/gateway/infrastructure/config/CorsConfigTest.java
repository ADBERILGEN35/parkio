package com.parkio.gateway.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * CORS behaviour at the edge for the browser SPA. The frontend issues credentialed
 * requests ({@code withCredentials}) so the HttpOnly refresh cookie flows on
 * {@code /auth/**} calls; browsers then require
 * {@code Access-Control-Allow-Credentials: true} together with a non-wildcard
 * {@code Access-Control-Allow-Origin} or they block the response as a CORS error.
 *
 * <p>These tests drive the real {@link CorsConfig#corsWebFilter(CorsProperties)} bean
 * with a {@code localhost:5173} (Vite) origin and assert the exact response headers,
 * locking in the credentialed-CORS contract that the login flow depends on.
 */
class CorsConfigTest {

    private static final String DEV_ORIGIN = "http://localhost:5173";
    // The gateway's own origin — must differ from DEV_ORIGIN so the request is treated
    // as cross-origin and the CORS filter engages (CorsUtils.isCorsRequest).
    private static final String GATEWAY_LOGIN_URL = "http://localhost:8080/api/v1/auth/login";

    private final CorsConfig corsConfig = new CorsConfig();

    /**
     * Reproduces the production incident: with credentials disabled (the pre-fix
     * {@code dev} profile default), a credentialed preflight from the SPA origin gets
     * NO {@code Access-Control-Allow-Credentials} header, so the browser blocks the
     * subsequent POST /auth/login response as a CORS error.
     */
    @Test
    void preflightOmitsAllowCredentialsWhenDisabled() {
        CorsWebFilter filter = corsConfig.corsWebFilter(
                props(List.of("http://localhost:3000", DEV_ORIGIN), false));

        HttpHeaders responseHeaders = runPreflight(filter);

        assertThat(responseHeaders.getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isEqualTo(DEV_ORIGIN);
        assertThat(responseHeaders.getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS)).isNull();
    }

    /**
     * The fix: with credentials enabled (the {@code dev} profile now matches the
     * docker-compose overlay), the preflight echoes the specific origin and grants
     * credentials, satisfying the browser's credentialed-CORS check.
     */
    @Test
    void preflightGrantsCredentialsAndSpecificOriginWhenEnabled() {
        CorsWebFilter filter = corsConfig.corsWebFilter(
                props(List.of("http://localhost:3000", DEV_ORIGIN), true));

        HttpHeaders responseHeaders = runPreflight(filter);

        assertThat(responseHeaders.getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isEqualTo(DEV_ORIGIN);
        assertThat(responseHeaders.getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS)).isEqualTo("true");
    }

    /** The actual (non-preflight) POST response must also carry the credentials grant. */
    @Test
    void actualLoginResponseCarriesAllowCredentialsWhenEnabled() {
        CorsWebFilter filter = corsConfig.corsWebFilter(props(List.of(DEV_ORIGIN), true));
        MockServerHttpRequest request = MockServerHttpRequest
                .method(HttpMethod.POST, GATEWAY_LOGIN_URL)
                .header(HttpHeaders.ORIGIN, DEV_ORIGIN)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        filter.filter(exchange, noOpChain()).block();

        HttpHeaders responseHeaders = exchange.getResponse().getHeaders();
        assertThat(responseHeaders.getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isEqualTo(DEV_ORIGIN);
        assertThat(responseHeaders.getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS)).isEqualTo("true");
    }

    /**
     * The actual incident: the running gateway used the DEFAULT profile (not {@code dev})
     * with origins supplied via {@code PARKIO_CORS_ALLOWED_ORIGINS} and
     * {@code PARKIO_CORS_ALLOW_CREDENTIALS} unset. The base {@code application.yml} must
     * therefore default {@code allow-credentials=true} on its own — without relying on a
     * profile or env override — so credentialed login works on the path the app runs.
     */
    @Test
    void baseProfileDefaultsCredentialsEnabledWithoutEnvOverride() throws IOException {
        CorsProperties properties = bindCorsProperties("application.yml");

        assertThat(properties.isAllowCredentials())
                .as("base application.yml must enable credentials by default")
                .isTrue();
    }

    /**
     * Locks the {@code dev} profile (gradlew bootRun) configuration: it must bind
     * {@code allow-credentials=true} and keep the Vite origin on the explicit
     * allow-list (never wildcard).
     */
    @Test
    void devProfileEnablesCredentialsForViteOrigin() throws IOException {
        CorsProperties properties = bindCorsProperties("application-dev.yml");

        assertThat(properties.isAllowCredentials()).isTrue();
        assertThat(properties.getAllowedOrigins()).contains(DEV_ORIGIN).doesNotContain("*");
    }

    /**
     * CORS headers must survive error responses: a credentialed login that returns
     * 4xx/5xx must still carry {@code Access-Control-Allow-Credentials}, or the browser
     * masks the real status as a CORS error. CorsWebFilter stamps the headers up-front,
     * before the downstream chain runs, so they are present regardless of status.
     */
    @Test
    void errorResponseStillCarriesAllowCredentials() {
        CorsWebFilter filter = corsConfig.corsWebFilter(props(List.of(DEV_ORIGIN), true));
        MockServerHttpRequest request = MockServerHttpRequest
                .method(HttpMethod.POST, GATEWAY_LOGIN_URL)
                .header(HttpHeaders.ORIGIN, DEV_ORIGIN)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        // Downstream fails the request with a 500 after CORS has run.
        filter.filter(exchange, ex -> {
            ex.getResponse().setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR);
            return Mono.empty();
        }).block();

        HttpHeaders responseHeaders = exchange.getResponse().getHeaders();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(responseHeaders.getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isEqualTo(DEV_ORIGIN);
        assertThat(responseHeaders.getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS)).isEqualTo("true");
    }

    /** The dangerous combination (wildcard origin + credentials) must fail fast at startup. */
    @Test
    void credentialsWithWildcardOriginFailsFast() {
        assertThatThrownBy(() -> corsConfig.corsWebFilter(props(List.of("*"), true)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("wildcard");
    }

    private HttpHeaders runPreflight(CorsWebFilter filter) {
        MockServerHttpRequest request = MockServerHttpRequest
                .method(HttpMethod.OPTIONS, GATEWAY_LOGIN_URL)
                .header(HttpHeaders.ORIGIN, DEV_ORIGIN)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        filter.filter(exchange, noOpChain()).block();
        return exchange.getResponse().getHeaders();
    }

    /**
     * Binds {@code parkio.gateway.cors} from a real classpath YAML resource, resolving
     * {@code ${PARKIO_CORS_*}} placeholders against the environment (env vars unset in
     * tests, so the in-file defaults apply) — exactly how Spring Boot loads it at runtime.
     */
    private static CorsProperties bindCorsProperties(String resource) throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load(resource, new ClassPathResource(resource));
        sources.forEach(environment.getPropertySources()::addFirst);
        return Binder.get(environment)
                .bind("parkio.gateway.cors", CorsProperties.class)
                .orElseGet(CorsProperties::new);
    }

    private static CorsProperties props(List<String> allowedOrigins, boolean allowCredentials) {
        CorsProperties properties = new CorsProperties();
        properties.setAllowedOrigins(allowedOrigins);
        properties.setAllowCredentials(allowCredentials);
        return properties;
    }

    private static WebFilterChain noOpChain() {
        return exchange -> Mono.empty();
    }
}
