package com.parkio.gateway.infrastructure.client;

import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Non-blocking client for auth-service's internal session-epoch endpoint
 * ({@code GET /internal/auth/users/{userId}/session-epoch}). Used by the edge to enforce
 * per-request access-token revocation. Any non-2xx response (including {@code 404} for an
 * unknown user), a timeout or a connection error errors the Mono with
 * {@link SessionEpochUnavailableException} so the gateway fails closed — an epoch that
 * cannot be confirmed must not let a possibly-revoked token through.
 */
@Component
public class SessionEpochClient {

    private final WebClient webClient;
    private final SessionEpochProperties properties;

    public SessionEpochClient(WebClient authServiceWebClient, SessionEpochProperties properties) {
        this.webClient = authServiceWebClient;
        this.properties = properties;
    }

    public Mono<Long> fetchCurrentEpoch(String userId) {
        return webClient.get()
                .uri("/internal/auth/users/{userId}/session-epoch", userId)
                .exchangeToMono(response -> {
                    if (response.statusCode().is2xxSuccessful()) {
                        return response.bodyToMono(SessionEpochResponse.class)
                                .map(SessionEpochResponse::sessionEpoch);
                    }
                    return response.releaseBody().then(Mono.error(new SessionEpochUnavailableException(
                            "auth-service returned status " + response.statusCode().value())));
                })
                .timeout(properties.getRequestTimeout())
                // Anything not already an "unavailable" signal (timeout, connection refused,
                // malformed body, ...) is normalised to fail-closed unavailability.
                .onErrorMap(ex -> !(ex instanceof SessionEpochUnavailableException),
                        ex -> new SessionEpochUnavailableException("session-epoch lookup failed", ex));
    }
}
