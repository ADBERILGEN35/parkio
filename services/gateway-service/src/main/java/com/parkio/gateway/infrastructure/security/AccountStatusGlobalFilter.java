package com.parkio.gateway.infrastructure.security;

import com.parkio.gateway.infrastructure.client.UserStatusCache;
import com.parkio.gateway.infrastructure.client.UserStatusClient;
import com.parkio.gateway.infrastructure.client.UserStatusUnavailableException;
import com.parkio.gateway.infrastructure.web.GatewayErrorResponseWriter;
import com.parkio.gateway.shared.GatewayHeaders;
import java.util.Optional;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
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
 * {@code 503} (ai-context/07). Resolved statuses are briefly cached
 * ({@link UserStatusCache}) to keep the check cheap.
 */
@Component
public class AccountStatusGlobalFilter implements GlobalFilter, Ordered {

    private static final String ACTIVE = "ACTIVE";

    private final PublicEndpoints publicEndpoints;
    private final UserStatusClient statusClient;
    private final UserStatusCache statusCache;
    private final GatewayErrorResponseWriter errorWriter;

    public AccountStatusGlobalFilter(PublicEndpoints publicEndpoints,
                                     UserStatusClient statusClient,
                                     UserStatusCache statusCache,
                                     GatewayErrorResponseWriter errorWriter) {
        this.publicEndpoints = publicEndpoints;
        this.statusClient = statusClient;
        this.statusCache = statusCache;
        this.errorWriter = errorWriter;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        if (publicEndpoints.isPublic(request)) {
            return chain.filter(exchange);
        }

        String authUserId = request.getHeaders().getFirst(GatewayHeaders.USER_ID);
        if (authUserId == null || authUserId.isBlank()) {
            // Should not happen on a protected route (authentication injects it first);
            // fail closed if it ever does.
            return reject(exchange);
        }

        Optional<String> cached = statusCache.get(authUserId);
        if (cached.isPresent()) {
            return decide(exchange, chain, cached.get());
        }

        return statusClient.fetchStatus(authUserId)
                .flatMap(lookup -> {
                    if (lookup.found()) {
                        statusCache.put(authUserId, lookup.status());
                        return decide(exchange, chain, lookup.status());
                    }
                    return reject(exchange); // 404 from user-service → treat as non-active
                })
                .onErrorResume(UserStatusUnavailableException.class, ex ->
                        errorWriter.write(exchange, HttpStatus.SERVICE_UNAVAILABLE, "USER_STATUS_UNAVAILABLE",
                                "Account status could not be verified. Please try again."));
    }

    private Mono<Void> decide(ServerWebExchange exchange, GatewayFilterChain chain, String status) {
        return ACTIVE.equals(status) ? chain.filter(exchange) : reject(exchange);
    }

    private Mono<Void> reject(ServerWebExchange exchange) {
        // A single code for "not active" (incl. unknown/suspended/banned) avoids leaking
        // whether an account exists or its exact moderation state.
        return errorWriter.write(exchange, HttpStatus.FORBIDDEN, "ACCOUNT_NOT_ACTIVE",
                "Your account is not active.");
    }

    @Override
    public int getOrder() {
        // After authentication (+10) and authorization (+20), before routing.
        return Ordered.HIGHEST_PRECEDENCE + 30;
    }
}
