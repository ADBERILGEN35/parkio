package com.parkio.gateway.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.gateway.infrastructure.client.SessionEpochCache;
import com.parkio.gateway.infrastructure.client.SessionEpochClient;
import com.parkio.gateway.infrastructure.client.SessionEpochProperties;
import com.parkio.gateway.infrastructure.client.UserStatusCache;
import com.parkio.gateway.infrastructure.client.UserStatusClient;
import com.parkio.gateway.infrastructure.client.UserStatusLookup;
import com.parkio.gateway.infrastructure.client.UserStatusProperties;
import com.parkio.gateway.infrastructure.web.GatewayErrorResponseWriter;
import com.parkio.gateway.shared.GatewayHeaders;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.PathContainer;
import org.springframework.http.server.RequestPath;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

class WaitlistAdminSecurityWebFilterTest {

    private JwtTokenValidator tokenValidator;
    private WaitlistAdminSecurityWebFilter filter;

    @BeforeEach
    void setUp() {
        tokenValidator = mock(JwtTokenValidator.class);
        SessionEpochClient epochClient = mock(SessionEpochClient.class);
        UserStatusClient statusClient = mock(UserStatusClient.class);
        when(epochClient.fetchCurrentEpoch(anyString())).thenReturn(Mono.just(0L));
        when(statusClient.fetchStatus(anyString())).thenReturn(Mono.just(UserStatusLookup.found("ACTIVE")));
        GatewayErrorResponseWriter errorWriter = new GatewayErrorResponseWriter(new ObjectMapper(), Clock.systemUTC());
        filter = new WaitlistAdminSecurityWebFilter(
                tokenValidator,
                new SessionEpochVerifier(epochClient,
                        new SessionEpochCache(Clock.systemUTC(), new SessionEpochProperties()), errorWriter),
                new AccountStatusVerifier(statusClient,
                        new UserStatusCache(Clock.systemUTC(), new UserStatusProperties()), errorWriter),
                errorWriter);
    }

    @Test
    void pathMatchingCoversExportAdminAndTrailingSlashIndependentOfMethod() {
        for (String protectedPath : List.of(
                "/api/v1/waitlist/export",
                "/api/v1/waitlist/export/",
                "/api/v1/waitlist/admin",
                "/api/v1/waitlist/admin/",
                "/api/v1/waitlist/admin/summary",
                "/api/v1/waitlist/admin/summary/")) {
            assertThat(WaitlistAdminSecurityWebFilter.isProtected(path(protectedPath)))
                    .as(protectedPath).isTrue();
        }
        for (String publicPath : List.of(
                "/api/v1/waitlist",
                "/api/v1/waitlist/",
                "/api/v1/waitlist/confirm",
                "/api/v1/waitlist/withdraw",
                "/api/v1/waitlist/resend",
                "/api/v1/waitlist/exports",
                "/api/v1/waitlist/administrator")) {
            assertThat(WaitlistAdminSecurityWebFilter.isProtected(path(publicPath)))
                    .as(publicPath).isFalse();
        }
    }

    @Test
    void anonymousNonGetMethodsDeniedWithoutInvokingChain() {
        for (HttpMethod method : List.of(HttpMethod.HEAD, HttpMethod.POST, HttpMethod.PUT,
                HttpMethod.PATCH, HttpMethod.DELETE, HttpMethod.OPTIONS)) {
            var exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.method(method, "/api/v1/waitlist/export").build());
            var chain = new CapturingChain();

            filter.filter(exchange, chain).block();

            assertThat(chain.invoked).as(method.name()).isFalse();
            assertThat(exchange.getResponse().getStatusCode()).as(method.name()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    @Test
    void emptyValidationResultDenied() {
        when(tokenValidator.validate("empty")).thenReturn(Mono.empty());
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/waitlist/export")
                .header(HttpHeaders.AUTHORIZATION, "Bearer empty")
                .build());
        var chain = new CapturingChain();

        filter.filter(exchange, chain).block();

        assertThat(chain.invoked).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(statusBody(exchange)).contains("INVALID_TOKEN");
    }

    @Test
    void anonymousExportDeniedWithoutInvokingChain() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/waitlist/export").build());
        var chain = new CapturingChain();

        filter.filter(exchange, chain).block();

        assertThat(chain.invoked).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(statusBody(exchange)).contains("MISSING_TOKEN");
    }

    @Test
    void trailingSlashExportDeniedAnonymously() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/waitlist/export/").build());
        var chain = new CapturingChain();

        filter.filter(exchange, chain).block();

        assertThat(chain.invoked).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void spoofedRoleHeadersDoNotGrantAccess() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/waitlist/export")
                .header(GatewayHeaders.USER_ROLES, "ADMIN,SUPER_ADMIN")
                .header(GatewayHeaders.USER_ID, "spoofed")
                .build());
        var chain = new CapturingChain();

        filter.filter(exchange, chain).block();

        assertThat(chain.invoked).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void invalidTokenDenied() {
        when(tokenValidator.validate("bad")).thenReturn(Mono.error(new IllegalArgumentException("expired")));
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/waitlist/export")
                .header(HttpHeaders.AUTHORIZATION, "Bearer bad")
                .build());
        var chain = new CapturingChain();

        filter.filter(exchange, chain).block();

        assertThat(chain.invoked).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(statusBody(exchange)).contains("INVALID_TOKEN");
    }

    @Test
    void userAndModeratorForbidden() {
        when(tokenValidator.validate(anyString())).thenAnswer(inv -> {
            String token = inv.getArgument(0);
            String role = token.startsWith("mod") ? "MODERATOR" : "USER";
            return Mono.just(new AuthenticatedUser(
                    UUID.randomUUID().toString(), role.toLowerCase() + "@test", List.of(role), "ACTIVE", 0L));
        });

        for (String token : List.of("user-token", "mod-token")) {
            var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/waitlist/export")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .build());
            var chain = new CapturingChain();
            filter.filter(exchange, chain).block();
            assertThat(chain.invoked).isFalse();
            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    @Test
    void adminAndSuperAdminAllowed() {
        when(tokenValidator.validate("admin-token")).thenReturn(Mono.just(new AuthenticatedUser(
                "admin-id", "admin@test", List.of("ADMIN"), "ACTIVE", 0L)));
        when(tokenValidator.validate("super-token")).thenReturn(Mono.just(new AuthenticatedUser(
                "super-id", "super@test", List.of("SUPER_ADMIN"), "ACTIVE", 0L)));

        for (String token : List.of("admin-token", "super-token")) {
            var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/waitlist/export")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .build());
            var chain = new CapturingChain();
            filter.filter(exchange, chain).block();
            assertThat(chain.invoked).isTrue();
            assertThat(exchange.getResponse().getStatusCode()).isNull();
        }
    }

    @Test
    void publicWaitlistPostsBypassFilter() {
        for (String path : List.of(
                "/api/v1/waitlist",
                "/api/v1/waitlist/confirm",
                "/api/v1/waitlist/withdraw",
                "/api/v1/waitlist/resend")) {
            var exchange = MockServerWebExchange.from(MockServerHttpRequest.post(path).build());
            var chain = new CapturingChain();
            filter.filter(exchange, chain).block();
            assertThat(chain.invoked).isTrue();
        }
    }

    private static PathContainer path(String value) {
        RequestPath requestPath = RequestPath.parse(value, null);
        return requestPath.pathWithinApplication();
    }

    private static String statusBody(MockServerWebExchange exchange) {
        DataBuffer joined = DataBufferUtils.join(exchange.getResponse().getBody()).block();
        if (joined == null) {
            return "";
        }
        byte[] bytes = new byte[joined.readableByteCount()];
        joined.read(bytes);
        DataBufferUtils.release(joined);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static final class CapturingChain implements WebFilterChain {
        boolean invoked;

        @Override
        public Mono<Void> filter(org.springframework.web.server.ServerWebExchange exchange) {
            invoked = true;
            return Mono.empty();
        }
    }
}
