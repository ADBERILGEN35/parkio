package com.parkio.gateway.infrastructure.client;

import com.parkio.gateway.shared.GatewayHeaders;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Builds the non-blocking {@link WebClient} the gateway uses to reach auth-service's
 * internal session-epoch endpoint. Base URL comes from {@link SessionEpochProperties}
 * (env-overridable, defaulting to the auth-service route target). It sends the
 * {@code X-Gateway-Auth} shared secret on every call, since the call does not pass
 * through the gateway's own routing filter yet still targets a gateway-protected
 * internal endpoint.
 */
@Configuration
public class AuthServiceClientConfig {

    @Bean
    public WebClient authServiceWebClient(WebClient.Builder builder,
                                          SessionEpochProperties properties,
                                          @Value("${parkio.gateway.internal-secret}") String internalSecret) {
        return builder
                .baseUrl(properties.getBaseUrl())
                .defaultHeader(GatewayHeaders.GATEWAY_AUTH, internalSecret)
                .build();
    }
}
