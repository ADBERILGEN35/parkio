package com.parkio.gateway.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.gateway.infrastructure.client.UserStatusCache;
import com.parkio.gateway.infrastructure.client.UserStatusClient;
import com.parkio.gateway.infrastructure.client.UserStatusLookup;
import com.parkio.gateway.infrastructure.client.UserStatusProperties;
import com.parkio.gateway.infrastructure.client.UserStatusUnavailableException;
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
 * Live account-status enforcement at the edge: only {@code ACTIVE} accounts reach
 * protected routes; suspended/unknown accounts get {@code 403}; an unavailable
 * status lookup fails closed with {@code 503}; public routes are never checked; and
 * a resolved status is cached so repeat requests don't re-hit user-service.
 */
class AccountStatusGlobalFilterTest {

    private static final String USER_ID = "11111111-1111-1111-1111-111111111111";

    private final UserStatusClient client = mock(UserStatusClient.class);
    private final UserStatusCache cache = new UserStatusCache(
            Clock.fixed(Instant.parse("2026-06-09T00:00:00Z"), ZoneOffset.UTC), new UserStatusProperties());
    private final AccountStatusGlobalFilter filter = new AccountStatusGlobalFilter(
            new PublicEndpoints(), client, cache,
            new GatewayErrorResponseWriter(new ObjectMapper().findAndRegisterModules(),
                    Clock.fixed(Instant.parse("2026-06-09T00:00:00Z"), ZoneOffset.UTC)));

    @Test
    void activeUserReachesProtectedRoute() {
        when(client.fetchStatus(USER_ID)).thenReturn(Mono.just(UserStatusLookup.found("ACTIVE")));
        CapturingChain chain = new CapturingChain();
        ServerWebExchange exchange = protectedExchange();

        filter.filter(exchange, chain).block();

        assertThat(chain.wasInvoked()).isTrue();
    }

    @Test
    void suspendedUserIsRejectedWith403() {
        when(client.fetchStatus(USER_ID)).thenReturn(Mono.just(UserStatusLookup.found("SUSPENDED")));
        CapturingChain chain = new CapturingChain();
        ServerWebExchange exchange = protectedExchange();

        filter.filter(exchange, chain).block();

        assertThat(chain.wasInvoked()).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void unknownUserIsRejectedWith403() {
        when(client.fetchStatus(USER_ID)).thenReturn(Mono.just(UserStatusLookup.notFound()));
        CapturingChain chain = new CapturingChain();
        ServerWebExchange exchange = protectedExchange();

        filter.filter(exchange, chain).block();

        assertThat(chain.wasInvoked()).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void unavailableStatusFailsClosedWith503() {
        when(client.fetchStatus(USER_ID))
                .thenReturn(Mono.error(new UserStatusUnavailableException("user-service down")));
        CapturingChain chain = new CapturingChain();
        ServerWebExchange exchange = protectedExchange();

        filter.filter(exchange, chain).block();

        assertThat(chain.wasInvoked()).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void publicAuthRouteIsNotStatusChecked() {
        CapturingChain chain = new CapturingChain();
        MockServerHttpRequest request = MockServerHttpRequest
                .method(HttpMethod.POST, "/api/v1/auth/login").build();
        ServerWebExchange exchange = MockServerWebExchange.from(request);

        filter.filter(exchange, chain).block();

        assertThat(chain.wasInvoked()).isTrue();
        verify(client, never()).fetchStatus(anyString());
    }

    @Test
    void resolvedStatusIsCachedAcrossRequests() {
        when(client.fetchStatus(USER_ID)).thenReturn(Mono.just(UserStatusLookup.found("ACTIVE")));

        filter.filter(protectedExchange(), new CapturingChain()).block();
        filter.filter(protectedExchange(), new CapturingChain()).block();

        verify(client, times(1)).fetchStatus(USER_ID); // second request served from cache
    }

    private static ServerWebExchange protectedExchange() {
        MockServerHttpRequest request = MockServerHttpRequest
                .method(HttpMethod.GET, "/api/v1/users/me")
                .header(GatewayHeaders.USER_ID, USER_ID)
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
