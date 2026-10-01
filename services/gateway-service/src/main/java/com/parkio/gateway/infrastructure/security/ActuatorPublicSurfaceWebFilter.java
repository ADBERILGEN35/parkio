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
 * {@code /actuator/info} is disabled, and never serves {@code /actuator/prometheus} to
 * a request relayed by a proxy.
 *
 * <p>Spring Boot serves actuator endpoints on the gateway port outside the Spring
 * Cloud Gateway filter chain, so {@link AuthenticationGlobalFilter} and
 * {@link PublicEndpoints} alone cannot conceal {@code /actuator/info}. This filter
 * runs at the WebFlux layer so Caddy-proxied internet traffic is rejected while
 * loopback identity probes (docker exec / dark smoke) keep working.
 *
 * <p>Prometheus scrapes {@code gateway-service:8080} directly from another container,
 * so the metrics endpoint cannot be limited to loopback. The public edge (Caddy) adds
 * {@code X-Forwarded-*} to every request it relays, and the internal scrape carries none,
 * so a request with any proxy header gets {@code 404} for the metrics paths (CL-F28).
 * The headers only ever cause a refusal here, never an exemption, so spoofing them gains
 * nothing. Caddy also blocks these paths itself.
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

    private static final List<PathPattern> INTERNAL_SCRAPE_PATHS = List.of(
            PARSER.parse("/actuator/prometheus"),
            PARSER.parse("/actuator/prometheus/**"));

    /** Headers a reverse proxy (the public Caddy edge) adds when it relays a request. */
    private static final List<String> PROXY_HEADERS =
            List.of("Forwarded", "X-Forwarded-For", "X-Forwarded-Host", "X-Forwarded-Proto");

    private final GatewayPublicSurfaceProperties publicSurface;

    public ActuatorPublicSurfaceWebFilter(GatewayPublicSurfaceProperties publicSurface) {
        this.publicSurface = publicSurface;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        PathContainer path = exchange.getRequest().getPath().pathWithinApplication();
        if (isInternalScrapePath(path) && relayedByProxy(exchange)) {
            return notFound(exchange);
        }

        if (publicSurface.isActuatorInfoEnabled()) {
            return chain.filter(exchange);
        }

        if (!isSensitiveActuatorPath(path)) {
            return chain.filter(exchange);
        }

        if (PublicEndpoints.isLoopback(exchange.getRequest())) {
            return chain.filter(exchange);
        }

        return notFound(exchange);
    }

    static boolean isSensitiveActuatorPath(PathContainer path) {
        return SENSITIVE_ACTUATOR_PATHS.stream().anyMatch(pattern -> pattern.matches(path));
    }

    static boolean isInternalScrapePath(PathContainer path) {
        return INTERNAL_SCRAPE_PATHS.stream().anyMatch(pattern -> pattern.matches(path));
    }

    private static boolean relayedByProxy(ServerWebExchange exchange) {
        return PROXY_HEADERS.stream().anyMatch(exchange.getRequest().getHeaders()::containsKey);
    }

    private static Mono<Void> notFound(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);
        return exchange.getResponse().setComplete();
    }
}
