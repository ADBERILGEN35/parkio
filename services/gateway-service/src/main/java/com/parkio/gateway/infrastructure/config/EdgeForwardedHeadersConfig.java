package com.parkio.gateway.infrastructure.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.server.adapter.ForwardedHeaderTransformer;

/**
 * Replaces Spring Boot's {@code forwardedHeaderTransformer} (registered for
 * {@code server.forward-headers-strategy=framework}, conditional on a missing bean) with
 * {@link EdgeOwnedForwardedHeaderTransformer}, which ignores a client-supplied {@code Forwarded}
 * header. The bean name matters: WebFlux looks the transformer up by this name.
 */
@Configuration
public class EdgeForwardedHeadersConfig {

    @Bean(name = "forwardedHeaderTransformer")
    @ConditionalOnProperty(value = "server.forward-headers-strategy", havingValue = "framework")
    public ForwardedHeaderTransformer forwardedHeaderTransformer() {
        return new EdgeOwnedForwardedHeaderTransformer();
    }
}
