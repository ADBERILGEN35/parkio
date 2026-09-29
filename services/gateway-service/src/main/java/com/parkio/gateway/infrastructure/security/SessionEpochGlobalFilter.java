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
 * Edge access-token revocation via session epoch. A valid JWT proves identity, but a
 * stateless access token stays valid until it expires (15m) even after the session is
 * invalidated (logout-all, refresh-token reuse detection, suspension). Each access token
 * carries the user's session epoch as a claim; this filter compares it against the
 * user's <em>current</em> epoch from auth-service and rejects the request if the token's
 * epoch is stale — cutting the revocation lag from the token TTL down to the cache TTL.
 *
 * <p>Runs after authentication (which validates the JWT and stashes the epoch claim) and
 * authorization. Public routes carry no token and are skipped. The token epoch comes from
 * a trusted exchange attribute set during JWT validation — never from the client. A
 * missing epoch claim (legacy token) is treated as epoch 0, which still works until the
 * user's first epoch bump. The decision itself lives in {@link SessionEpochVerifier}, which
 * the local waitlist admin filter reuses.
 *
 * <p><strong>Fail-closed:</strong> if the current epoch cannot be determined (auth-service
 * unavailable/unknown user) the request is rejected with {@code 503}, consistent with the
 * live account-status check — a token that cannot be confirmed unrevoked must not pass.
 */
@Component
public class SessionEpochGlobalFilter implements GlobalFilter, Ordered {

    private final PublicEndpoints publicEndpoints;
    private final SessionEpochVerifier epochVerifier;

    public SessionEpochGlobalFilter(PublicEndpoints publicEndpoints, SessionEpochVerifier epochVerifier) {
        this.publicEndpoints = publicEndpoints;
        this.epochVerifier = epochVerifier;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        if (publicEndpoints.isPublic(request)) {
            return chain.filter(exchange);
        }

        String userId = request.getHeaders().getFirst(GatewayHeaders.USER_ID);
        // Trusted: set by JWT validation from the signed claim, not the client. Absent
        // claim (legacy token) → epoch 0, which passes until the user's first epoch bump.
        return epochVerifier.verify(exchange, userId, tokenEpoch(exchange), () -> chain.filter(exchange));
    }

    private long tokenEpoch(ServerWebExchange exchange) {
        Object attribute = exchange.getAttribute(GatewayHeaders.TOKEN_SESSION_EPOCH_ATTRIBUTE);
        return attribute instanceof Long epoch ? epoch : 0L;
    }

    @Override
    public int getOrder() {
        // After authentication (+10) and authorization (+20); peer of account-status (+30).
        return Ordered.HIGHEST_PRECEDENCE + 25;
    }
}
