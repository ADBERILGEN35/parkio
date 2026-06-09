package com.parkio.gateway.infrastructure.client;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Builds the non-blocking {@link WebClient} the gateway uses to reach user-service's
 * internal endpoints. Base URL comes from {@link UserStatusProperties} (env-overridable,
 * defaulting to the user-service route target).
 */
@Configuration
public class UserServiceClientConfig {

    @Bean
    public WebClient userServiceWebClient(WebClient.Builder builder, UserStatusProperties properties) {
        return builder.baseUrl(properties.getBaseUrl()).build();
    }
}
