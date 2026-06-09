package com.parkio.gateway.infrastructure.client;

import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Non-blocking client for user-service's internal account-status endpoint
 * ({@code GET /internal/users/{authUserId}/status}). Used by the edge to enforce live
 * account status per request. Distinguishes three outcomes:
 * <ul>
 *   <li>{@code 2xx} → {@link UserStatusLookup#found(String)};</li>
 *   <li>{@code 404} → {@link UserStatusLookup#notFound()} (unprovisioned/unknown);</li>
 *   <li>timeout / connection error / any other status → the Mono errors with
 *       {@link UserStatusUnavailableException} so the gateway can fail closed.</li>
 * </ul>
 */
@Component
public class UserStatusClient {

    private final WebClient webClient;
    private final UserStatusProperties properties;

    public UserStatusClient(WebClient userServiceWebClient, UserStatusProperties properties) {
        this.webClient = userServiceWebClient;
        this.properties = properties;
    }

    public Mono<UserStatusLookup> fetchStatus(String authUserId) {
        return webClient.get()
                .uri("/internal/users/{authUserId}/status", authUserId)
                .exchangeToMono(response -> {
                    if (response.statusCode().is2xxSuccessful()) {
                        return response.bodyToMono(UserStatusResponse.class)
                                .map(body -> UserStatusLookup.found(body.status()));
                    }
                    if (response.statusCode().value() == 404) {
                        return response.releaseBody().thenReturn(UserStatusLookup.notFound());
                    }
                    return response.releaseBody().then(Mono.error(new UserStatusUnavailableException(
                            "user-service returned status " + response.statusCode().value())));
                })
                .timeout(properties.getRequestTimeout())
                // Anything that is not already an "unavailable" signal (timeout, connection
                // refused, malformed body, ...) is normalised to fail-closed unavailability.
                .onErrorMap(ex -> !(ex instanceof UserStatusUnavailableException),
                        ex -> new UserStatusUnavailableException("user-status lookup failed", ex));
    }
}
