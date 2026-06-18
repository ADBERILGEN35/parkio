package com.parkio.gateway.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.gateway.infrastructure.web.GatewayErrorResponseWriter;
import com.parkio.gateway.shared.GatewayHeaders;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Edge role-gating: privileged routes require MODERATOR/ADMIN; user-facing moderation
 * endpoints admit any authenticated user. The filter reads the
 * trusted {@code X-User-Roles} header injected by {@link AuthenticationGlobalFilter}.
 */
class AuthorizationGlobalFilterTest {

    private final AuthorizationGlobalFilter filter = new AuthorizationGlobalFilter(
            new RouteAuthorizationRules(),
            new GatewayErrorResponseWriter(new ObjectMapper().findAndRegisterModules(),
                    Clock.fixed(Instant.parse("2026-06-07T00:00:00Z"), ZoneOffset.UTC)));

    @Test
    void normalUserCannotAccessAnalytics() {
        CapturingChain chain = new CapturingChain();
        ServerWebExchange exchange = exchange(HttpMethod.GET, "/api/v1/analytics/overview", "USER");

        filter.filter(exchange, chain).block();

        assertThat(chain.wasInvoked()).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void moderatorCannotAccessPlatformAnalytics() {
        CapturingChain chain = new CapturingChain();
        ServerWebExchange exchange = exchange(HttpMethod.GET, "/api/v1/analytics/overview", "USER,MODERATOR");

        filter.filter(exchange, chain).block();

        assertThat(chain.wasInvoked()).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void adminCanAccessPlatformAnalytics() {
        CapturingChain chain = new CapturingChain();
        ServerWebExchange exchange = exchange(HttpMethod.GET, "/api/v1/analytics/daily", "ADMIN");

        filter.filter(exchange, chain).block();

        assertThat(chain.wasInvoked()).isTrue();
    }

    @Test
    void userCanReadTheirOwnAnalytics() {
        CapturingChain chain = new CapturingChain();
        ServerWebExchange exchange = exchange(HttpMethod.GET, "/api/v1/analytics/users/abc", "USER");

        filter.filter(exchange, chain).block();

        assertThat(chain.wasInvoked()).isTrue();
    }

    @Test
    void normalUserCannotForceManualAiValidation() {
        CapturingChain chain = new CapturingChain();
        ServerWebExchange exchange = exchange(HttpMethod.POST, "/api/v1/ai-validations/manual", "USER");

        filter.filter(exchange, chain).block();

        assertThat(chain.wasInvoked()).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void moderatorCanForceManualAiValidation() {
        CapturingChain chain = new CapturingChain();
        ServerWebExchange exchange = exchange(HttpMethod.POST, "/api/v1/ai-validations/manual", "MODERATOR");

        filter.filter(exchange, chain).block();

        assertThat(chain.wasInvoked()).isTrue();
    }

    @Test
    void normalUserCannotReadAiValidationLookup() {
        CapturingChain chain = new CapturingChain();
        ServerWebExchange exchange = exchange(HttpMethod.GET, "/api/v1/ai-validations/media/abc", "USER");

        filter.filter(exchange, chain).block();

        assertThat(chain.wasInvoked()).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void moderatorCanReadAiValidationLookup() {
        CapturingChain chain = new CapturingChain();
        ServerWebExchange exchange = exchange(HttpMethod.GET, "/api/v1/ai-validations/media/abc", "MODERATOR");

        filter.filter(exchange, chain).block();

        assertThat(chain.wasInvoked()).isTrue();
    }

    @Test
    void normalUserCannotAccessModerationCases() {
        CapturingChain chain = new CapturingChain();
        ServerWebExchange exchange = exchange(HttpMethod.GET, "/api/v1/moderation/cases", "USER");

        filter.filter(exchange, chain).block();

        assertThat(chain.wasInvoked()).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void moderatorCanAccessModerationCases() {
        CapturingChain chain = new CapturingChain();
        ServerWebExchange exchange = exchange(HttpMethod.GET, "/api/v1/moderation/cases", "MODERATOR");

        filter.filter(exchange, chain).block();

        assertThat(chain.wasInvoked()).isTrue();
    }

    @Test
    void userFacingReportEndpointIsOpenToAuthenticatedUser() {
        CapturingChain chain = new CapturingChain();
        ServerWebExchange exchange = exchange(HttpMethod.POST, "/api/v1/moderation/reports", "USER");

        filter.filter(exchange, chain).block();

        assertThat(chain.wasInvoked()).isTrue();
    }

    @Test
    void userFacingAppealEndpointIsOpenToAuthenticatedUser() {
        CapturingChain chain = new CapturingChain();
        ServerWebExchange exchange = exchange(HttpMethod.POST, "/api/v1/moderation/appeals", "USER");

        filter.filter(exchange, chain).block();

        assertThat(chain.wasInvoked()).isTrue();
    }

    @Test
    void unprivilegedRouteWithoutAnyRuleIsAllowed() {
        CapturingChain chain = new CapturingChain();
        ServerWebExchange exchange = exchange(HttpMethod.GET, "/api/v1/users/me", "USER");

        filter.filter(exchange, chain).block();

        assertThat(chain.wasInvoked()).isTrue();
    }

    private static ServerWebExchange exchange(HttpMethod method, String path, String roles) {
        MockServerHttpRequest request = MockServerHttpRequest
                .method(method, path)
                .header(GatewayHeaders.USER_ID, "user-123")
                .header(GatewayHeaders.USER_ROLES, roles)
                .build();
        return MockServerWebExchange.from(request);
    }

    /** Captures whether the request was forwarded downstream. */
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
    }
}
