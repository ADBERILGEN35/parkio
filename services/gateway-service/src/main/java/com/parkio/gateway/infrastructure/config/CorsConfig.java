package com.parkio.gateway.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

/**
 * Reactive CORS for the edge, driven entirely by {@link CorsProperties}. Applied to
 * all paths; with no configured origins it denies cross-origin browser requests.
 */
@Configuration
public class CorsConfig {

    @Bean
    public CorsWebFilter corsWebFilter(CorsProperties properties) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(properties.getAllowedOrigins());
        config.setAllowedMethods(properties.getAllowedMethods());
        config.setAllowedHeaders(properties.getAllowedHeaders());
        config.setAllowCredentials(properties.isAllowCredentials());
        config.setMaxAge(properties.getMaxAge());

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return new CorsWebFilter(source);
    }
}
