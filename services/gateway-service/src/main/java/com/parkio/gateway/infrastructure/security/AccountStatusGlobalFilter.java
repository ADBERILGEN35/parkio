package com.parkio.gateway.infrastructure.security;

import com.parkio.gateway.shared.GatewayHeaders;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Live account-status enforcement at the edge. A valid JWT proves <em>identity</em>;
 * it does not prove the account is still in good standing — a token stays valid until
 * it expires, even after the user is suspended/banned. This filter closes that gap by
 * checking the <em>current</em> status from user-service on every protected request.
 *
 * <p>Runs after authentication/authorization (so the trusted {@code X-User-Id} is
 * present) and before routing. Public routes (login/register/refresh/logout, actuator)
 * are skipped — they carry no identity and must stay reachable. Only {@code ACTIVE} is
 * allowed through; any other status, or an unknown/unprovisioned account, is rejected
 * with {@code 403}. If the status cannot be determined, the request fails closed with
 * {@code 503} (ai-context/07). The decision itself lives in {@link AccountStatusVerifier},
 * which the local waitlist admin filter reuses.
 */
@Component
public class AccountStatusGlobalFilter implements GlobalFilter, Ordered {

    private final PublicEndpoints publicEndpoints;
    private final AccountStatusVerifier statusVerifier;

    public AccountStatusGlobalFilter(PublicEndpoints publicEndpoints, AccountStatusVerifier statusVerifier) {
        this.publicEndpoints = publicEndpoints;
        this.statusVerifier = statusVerifier;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        if (publicEndpoints.isPublic(request)) {
            return chain.filter(exchange);
        }

        String authUserId = request.getHeaders().getFirst(GatewayHeaders.USER_ID);
        return statusVerifier.verify(exchange, authUserId, () -> chain.filter(exchange));
    }

    @Override
    public int getOrder() {
        // After authentication (+10) and authorization (+20), before routing.
        return Ordered.HIGHEST_PRECEDENCE + 30;
    }
}
