package com.parkio.gateway.infrastructure.security;

import com.parkio.gateway.infrastructure.client.SessionEpochCache;
import com.parkio.gateway.infrastructure.client.SessionEpochClient;
import com.parkio.gateway.infrastructure.client.SessionEpochUnavailableException;
import com.parkio.gateway.infrastructure.web.GatewayErrorResponseWriter;
import java.util.OptionalLong;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * The session-epoch revocation decision, shared by {@link SessionEpochGlobalFilter}
 * (routed traffic) and {@link WaitlistAdminSecurityWebFilter} (local waitlist admin
 * controllers) so both enforce one contract: a stale epoch is rejected with {@code 401},
 * an epoch that cannot be resolved fails closed with {@code 503}, and resolved epochs are
 * briefly cached ({@link SessionEpochCache}). {@code onValid} is only subscribed when the
 * token's epoch is current.
 */
@Component
public class SessionEpochVerifier {

    private final SessionEpochClient epochClient;
    private final SessionEpochCache epochCache;
    private final GatewayErrorResponseWriter errorWriter;

    public SessionEpochVerifier(SessionEpochClient epochClient,
                                SessionEpochCache epochCache,
                                GatewayErrorResponseWriter errorWriter) {
        this.epochClient = epochClient;
        this.epochCache = epochCache;
        this.errorWriter = errorWriter;
    }

    public Mono<Void> verify(ServerWebExchange exchange, String userId, long tokenEpoch,
                             Supplier<Mono<Void>> onValid) {
        if (userId == null || userId.isBlank()) {
            // Should not happen on a protected route (authentication injects it first);
            // fail closed if it ever does.
            return reject(exchange);
        }

        OptionalLong cached = epochCache.get(userId);
        if (cached.isPresent()) {
            return decide(exchange, tokenEpoch, cached.getAsLong(), onValid);
        }

        return epochClient.fetchCurrentEpoch(userId)
                .flatMap(currentEpoch -> {
                    epochCache.put(userId, currentEpoch);
                    return decide(exchange, tokenEpoch, currentEpoch, onValid);
                })
                .onErrorResume(SessionEpochUnavailableException.class, ex ->
                        errorWriter.write(exchange, HttpStatus.SERVICE_UNAVAILABLE, "SESSION_EPOCH_UNAVAILABLE",
                                "Session validity could not be verified. Please try again."));
    }

    private Mono<Void> decide(ServerWebExchange exchange, long tokenEpoch, long currentEpoch,
                              Supplier<Mono<Void>> onValid) {
        // A token from before a session-invalidating event carries an older epoch.
        return tokenEpoch < currentEpoch ? reject(exchange) : onValid.get();
    }

    private Mono<Void> reject(ServerWebExchange exchange) {
        // Generic 401 — the client only learns the token is no longer valid, not why.
        return errorWriter.write(exchange, HttpStatus.UNAUTHORIZED, "TOKEN_REVOKED",
                "Your session has been revoked. Please sign in again.");
    }
}
