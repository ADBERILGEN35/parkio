package com.parkio.gateway.infrastructure.config;

import com.parkio.gateway.shared.GatewayHeaders;
import java.util.List;
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
        if (properties.isAllowCredentials() && properties.getAllowedOrigins().contains("*")) {
            throw new IllegalStateException("CORS credentials cannot be enabled with wildcard origins");
        }
        config.setAllowedOrigins(properties.getAllowedOrigins());
        config.setAllowedMethods(properties.getAllowedMethods());
        config.setAllowedHeaders(properties.getAllowedHeaders());
        config.setAllowCredentials(properties.isAllowCredentials());
        // A cross-origin admin client must be able to read the export's truncation report.
        config.setExposedHeaders(List.of(GatewayHeaders.EXPORT_ROW_LIMIT, GatewayHeaders.EXPORT_MATCHING_ROWS,
                GatewayHeaders.EXPORT_TRUNCATED));
        config.setMaxAge(properties.getMaxAge());

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return new CorsWebFilter(source);
    }
}
