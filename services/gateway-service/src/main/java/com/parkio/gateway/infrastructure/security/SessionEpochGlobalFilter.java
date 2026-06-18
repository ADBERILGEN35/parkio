package com.parkio.gateway.infrastructure.security;

import com.parkio.gateway.infrastructure.client.SessionEpochCache;
import com.parkio.gateway.infrastructure.client.SessionEpochClient;
import com.parkio.gateway.infrastructure.client.SessionEpochUnavailableException;
import com.parkio.gateway.infrastructure.web.GatewayErrorResponseWriter;
import com.parkio.gateway.shared.GatewayHeaders;
import java.util.OptionalLong;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
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
 * user's first epoch bump. Resolved epochs are briefly cached ({@link SessionEpochCache}).
 *
 * <p><strong>Fail-closed:</strong> if the current epoch cannot be determined (auth-service
 * unavailable/unknown user) the request is rejected with {@code 503}, consistent with the
 * live account-status check — a token that cannot be confirmed unrevoked must not pass.
 */
@Component
public class SessionEpochGlobalFilter implements GlobalFilter, Ordered {

    private final PublicEndpoints publicEndpoints;
    private final SessionEpochClient epochClient;
    private final SessionEpochCache epochCache;
    private final GatewayErrorResponseWriter errorWriter;

    public SessionEpochGlobalFilter(PublicEndpoints publicEndpoints,
                                    SessionEpochClient epochClient,
                                    SessionEpochCache epochCache,
                                    GatewayErrorResponseWriter errorWriter) {
        this.publicEndpoints = publicEndpoints;
        this.epochClient = epochClient;
        this.epochCache = epochCache;
        this.errorWriter = errorWriter;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        if (publicEndpoints.isPublic(request)) {
            return chain.filter(exchange);
        }

        String userId = request.getHeaders().getFirst(GatewayHeaders.USER_ID);
        if (userId == null || userId.isBlank()) {
            // Should not happen on a protected route (authentication injects it first);
            // fail closed if it ever does.
            return reject(exchange);
        }

        // Trusted: set by JWT validation from the signed claim, not the client. Absent
        // claim (legacy token) → epoch 0, which passes until the user's first epoch bump.
        long tokenEpoch = tokenEpoch(exchange);

        OptionalLong cached = epochCache.get(userId);
        if (cached.isPresent()) {
            return decide(exchange, chain, tokenEpoch, cached.getAsLong());
        }

        return epochClient.fetchCurrentEpoch(userId)
                .flatMap(currentEpoch -> {
                    epochCache.put(userId, currentEpoch);
                    return decide(exchange, chain, tokenEpoch, currentEpoch);
                })
                .onErrorResume(SessionEpochUnavailableException.class, ex ->
                        errorWriter.write(exchange, HttpStatus.SERVICE_UNAVAILABLE, "SESSION_EPOCH_UNAVAILABLE",
                                "Session validity could not be verified. Please try again."));
    }

    private Mono<Void> decide(ServerWebExchange exchange, GatewayFilterChain chain,
                              long tokenEpoch, long currentEpoch) {
        // A token from before a session-invalidating event carries an older epoch.
        return tokenEpoch < currentEpoch ? reject(exchange) : chain.filter(exchange);
    }

    private long tokenEpoch(ServerWebExchange exchange) {
        Object attribute = exchange.getAttribute(GatewayHeaders.TOKEN_SESSION_EPOCH_ATTRIBUTE);
        return attribute instanceof Long epoch ? epoch : 0L;
    }

    private Mono<Void> reject(ServerWebExchange exchange) {
        // Generic 401 — the client only learns the token is no longer valid, not why.
        return errorWriter.write(exchange, HttpStatus.UNAUTHORIZED, "TOKEN_REVOKED",
                "Your session has been revoked. Please sign in again.");
    }

    @Override
    public int getOrder() {
        // After authentication (+10) and authorization (+20); peer of account-status (+30).
        return Ordered.HIGHEST_PRECEDENCE + 25;
    }
}
