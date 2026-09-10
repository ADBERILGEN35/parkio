package com.parkio.gateway.infrastructure.security;

import com.parkio.gateway.infrastructure.config.GatewayPublicSurfaceProperties;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import reactor.core.publisher.Mono;

/**
 * Blocks sensitive actuator endpoints for non-loopback callers when public
 * {@code /actuator/info} is disabled.
 *
 * <p>Spring Boot serves actuator endpoints on the gateway port outside the Spring
 * Cloud Gateway filter chain, so {@link AuthenticationGlobalFilter} and
 * {@link PublicEndpoints} alone cannot conceal {@code /actuator/info}. This filter
 * runs at the WebFlux layer so Caddy-proxied internet traffic is rejected while
 * loopback identity probes (docker exec / dark smoke) keep working.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ActuatorPublicSurfaceWebFilter implements WebFilter {

    private static final PathPatternParser PARSER = PathPatternParser.defaultInstance;

    private static final List<PathPattern> SENSITIVE_ACTUATOR_PATHS = List.of(
            PARSER.parse("/actuator/info"),
            PARSER.parse("/actuator/env"),
            PARSER.parse("/actuator/env/**"),
            PARSER.parse("/actuator/configprops"),
            PARSER.parse("/actuator/configprops/**"));

    private final GatewayPublicSurfaceProperties publicSurface;

    public ActuatorPublicSurfaceWebFilter(GatewayPublicSurfaceProperties publicSurface) {
        this.publicSurface = publicSurface;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (publicSurface.isActuatorInfoEnabled()) {
            return chain.filter(exchange);
        }

        PathContainer path = exchange.getRequest().getPath().pathWithinApplication();
        if (!isSensitiveActuatorPath(path)) {
            return chain.filter(exchange);
        }

        if (PublicEndpoints.isLoopback(exchange.getRequest())) {
            return chain.filter(exchange);
        }

        exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);
        return exchange.getResponse().setComplete();
    }

    static boolean isSensitiveActuatorPath(PathContainer path) {
        return SENSITIVE_ACTUATOR_PATHS.stream().anyMatch(pattern -> pattern.matches(path));
    }
}
