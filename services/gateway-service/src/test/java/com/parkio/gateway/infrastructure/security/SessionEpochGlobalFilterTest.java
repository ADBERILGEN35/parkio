package com.parkio.gateway.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.gateway.infrastructure.client.SessionEpochCache;
import com.parkio.gateway.infrastructure.client.SessionEpochClient;
import com.parkio.gateway.infrastructure.client.SessionEpochProperties;
import com.parkio.gateway.infrastructure.client.SessionEpochUnavailableException;
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
 * Edge access-token revocation via session epoch: a token whose epoch matches the
 * user's current epoch passes; a stale epoch is rejected with {@code 401}; a missing
 * epoch claim is treated as 0 (passes only while the current epoch is still 0); an
 * unavailable lookup fails closed with {@code 503}; public routes are never checked; and
 * a resolved epoch is cached so repeat requests don't re-hit auth-service.
 */
class SessionEpochGlobalFilterTest {

    private static final String USER_ID = "11111111-1111-1111-1111-111111111111";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-09T00:00:00Z"), ZoneOffset.UTC);

    private final SessionEpochClient client = mock(SessionEpochClient.class);
    private final SessionEpochCache cache = new SessionEpochCache(CLOCK, new SessionEpochProperties());
    private final SessionEpochGlobalFilter filter = new SessionEpochGlobalFilter(
            new PublicEndpoints(), client, cache,
            new GatewayErrorResponseWriter(new ObjectMapper().findAndRegisterModules(), CLOCK));

    @Test
    void tokenWithCurrentEpochReachesProtectedRoute() {
        when(client.fetchCurrentEpoch(USER_ID)).thenReturn(Mono.just(3L));
        CapturingChain chain = new CapturingChain();

        filter.filter(protectedExchange(3L), chain).block();

        assertThat(chain.wasInvoked()).isTrue();
    }

    @Test
    void staleTokenEpochIsRejectedWith401() {
        when(client.fetchCurrentEpoch(USER_ID)).thenReturn(Mono.just(3L));
        CapturingChain chain = new CapturingChain();
        ServerWebExchange exchange = protectedExchange(2L);

        filter.filter(exchange, chain).block();

        assertThat(chain.wasInvoked()).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void missingEpochClaimTreatedAsZeroPassesWhenCurrentIsZero() {
        when(client.fetchCurrentEpoch(USER_ID)).thenReturn(Mono.just(0L));
        CapturingChain chain = new CapturingChain();

        // No epoch attribute → legacy token; current epoch still 0 → allowed.
        filter.filter(protectedExchange(null), chain).block();

        assertThat(chain.wasInvoked()).isTrue();
    }

    @Test
    void missingEpochClaimTreatedAsZeroRejectedAfterEpochBump() {
        when(client.fetchCurrentEpoch(USER_ID)).thenReturn(Mono.just(1L));
        CapturingChain chain = new CapturingChain();
        ServerWebExchange exchange = protectedExchange(null);

        filter.filter(exchange, chain).block();

        assertThat(chain.wasInvoked()).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void unavailableEpochFailsClosedWith503() {
        when(client.fetchCurrentEpoch(USER_ID))
                .thenReturn(Mono.error(new SessionEpochUnavailableException("auth-service down")));
        CapturingChain chain = new CapturingChain();
        ServerWebExchange exchange = protectedExchange(1L);

        filter.filter(exchange, chain).block();

        assertThat(chain.wasInvoked()).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void publicAuthRouteIsNotEpochChecked() {
        CapturingChain chain = new CapturingChain();
        MockServerHttpRequest request = MockServerHttpRequest
                .method(HttpMethod.POST, "/api/v1/auth/login").build();
        ServerWebExchange exchange = MockServerWebExchange.from(request);

        filter.filter(exchange, chain).block();

        assertThat(chain.wasInvoked()).isTrue();
        verify(client, never()).fetchCurrentEpoch(anyString());
    }

    @Test
    void resolvedEpochIsCachedAcrossRequests() {
        when(client.fetchCurrentEpoch(USER_ID)).thenReturn(Mono.just(2L));

        filter.filter(protectedExchange(2L), new CapturingChain()).block();
        filter.filter(protectedExchange(2L), new CapturingChain()).block();

        verify(client, times(1)).fetchCurrentEpoch(USER_ID); // second request served from cache
    }

    private static ServerWebExchange protectedExchange(Long tokenEpoch) {
        MockServerHttpRequest request = MockServerHttpRequest
                .method(HttpMethod.GET, "/api/v1/users/me")
                .header(GatewayHeaders.USER_ID, USER_ID)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        if (tokenEpoch != null) {
            exchange.getAttributes().put(GatewayHeaders.TOKEN_SESSION_EPOCH_ATTRIBUTE, tokenEpoch);
        }
        return exchange;
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
