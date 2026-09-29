package com.parkio.gateway.infrastructure.security;

import com.parkio.gateway.infrastructure.client.UserStatusCache;
import com.parkio.gateway.infrastructure.client.UserStatusClient;
import com.parkio.gateway.infrastructure.client.UserStatusUnavailableException;
import com.parkio.gateway.infrastructure.web.GatewayErrorResponseWriter;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * The live account-status decision, shared by {@link AccountStatusGlobalFilter} (routed
 * traffic) and {@link WaitlistAdminSecurityWebFilter} (local waitlist admin controllers)
 * so both enforce one contract: only {@code ACTIVE} passes, any other or unknown status is
 * rejected with {@code 403}, and an undeterminable status fails closed with {@code 503}.
 * Resolved statuses are briefly cached ({@link UserStatusCache}). {@code onActive} is only
 * subscribed for an active account.
 */
@Component
public class AccountStatusVerifier {

    private static final String ACTIVE = "ACTIVE";

    private final UserStatusClient statusClient;
    private final UserStatusCache statusCache;
    private final GatewayErrorResponseWriter errorWriter;

    public AccountStatusVerifier(UserStatusClient statusClient,
                                 UserStatusCache statusCache,
                                 GatewayErrorResponseWriter errorWriter) {
        this.statusClient = statusClient;
        this.statusCache = statusCache;
        this.errorWriter = errorWriter;
    }

    public Mono<Void> verify(ServerWebExchange exchange, String authUserId, Supplier<Mono<Void>> onActive) {
        if (authUserId == null || authUserId.isBlank()) {
            // Should not happen on a protected route (authentication injects it first);
            // fail closed if it ever does.
            return reject(exchange);
        }

        Optional<String> cached = statusCache.get(authUserId);
        if (cached.isPresent()) {
            return decide(exchange, cached.get(), onActive);
        }

        return statusClient.fetchStatus(authUserId)
                .flatMap(lookup -> {
                    if (lookup.found()) {
                        statusCache.put(authUserId, lookup.status());
                        return decide(exchange, lookup.status(), onActive);
                    }
                    return reject(exchange); // 404 from user-service → treat as non-active
                })
                .onErrorResume(UserStatusUnavailableException.class, ex ->
                        errorWriter.write(exchange, HttpStatus.SERVICE_UNAVAILABLE, "USER_STATUS_UNAVAILABLE",
                                "Account status could not be verified. Please try again."));
    }

    private Mono<Void> decide(ServerWebExchange exchange, String status, Supplier<Mono<Void>> onActive) {
        return ACTIVE.equals(status) ? onActive.get() : reject(exchange);
    }

    private Mono<Void> reject(ServerWebExchange exchange) {
        // A single code for "not active" (incl. unknown/suspended/banned) avoids leaking
        // whether an account exists or its exact moderation state.
        return errorWriter.write(exchange, HttpStatus.FORBIDDEN, "ACCOUNT_NOT_ACTIVE",
                "Your account is not active.");
    }
}
