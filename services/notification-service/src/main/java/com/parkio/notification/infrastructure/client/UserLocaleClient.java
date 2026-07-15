package com.parkio.notification.infrastructure.client;

import com.parkio.notification.application.port.UserLocalePort;
import com.parkio.notification.domain.NotificationLocale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Reads preferredLocale from user-service. Failures (network, 404 already mapped
 * to default upstream, unexpected errors) return the product default {@code tr}.
 */
@Component
public class UserLocaleClient implements UserLocalePort {

    private static final Logger log = LoggerFactory.getLogger(UserLocaleClient.class);

    private final RestClient restClient;

    public UserLocaleClient(
            RestClient.Builder builder,
            @Value("${parkio.notification.user-service.base-url:http://localhost:8082}")
                    String baseUrl,
            @Value("${parkio.gateway.internal-secret}") String gatewaySecret) {
        this.restClient = builder.baseUrl(baseUrl)
                .defaultHeader("X-Gateway-Auth", gatewaySecret)
                .build();
    }

    @Override
    public NotificationLocale resolvePreferredLocale(UUID userId) {
        try {
            PreferredLocaleResponse body = restClient.get()
                    .uri("/internal/users/{userId}/preferred-locale", userId)
                    .retrieve()
                    .body(PreferredLocaleResponse.class);
            if (body == null || body.preferredLocale() == null) {
                return NotificationLocale.DEFAULT;
            }
            return NotificationLocale.parseOrDefault(body.preferredLocale());
        } catch (RestClientException ex) {
            log.debug("Could not resolve preferredLocale for {}: {}", userId, ex.getClass().getSimpleName());
            return NotificationLocale.DEFAULT;
        }
    }

    record PreferredLocaleResponse(String preferredLocale) {
    }
}
