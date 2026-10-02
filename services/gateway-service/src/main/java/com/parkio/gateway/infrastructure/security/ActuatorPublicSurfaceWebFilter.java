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
 * so the metrics endpoint cannot be limited to loopback. The public edge (Caddy) sets
 * {@value #EDGE_RELAY_HEADER} on every request it relays to the gateway, and the internal
 * scrape carries none, so a relayed request gets {@code 404} for the metrics paths (CL-F28).
 * The marker is what works in production: every production model runs the gateway with
 * {@code SERVER_FORWARD_HEADERS_STRATEGY=framework}, and Spring then applies and removes
 * {@code Forwarded} / {@code X-Forwarded-*} before any WebFilter runs. Those headers are still
 * checked for setups without that strategy. A header here only ever causes a refusal, never an
 * exemption, so sending one gains nothing. Caddy also blocks these paths itself.
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

    /** Set by the public Caddy edge on every request it relays to the gateway (docker/caddy/Caddyfile). */
    static final String EDGE_RELAY_HEADER = "X-Parkio-Edge-Relay";

    /**
     * Headers that mark a request relayed by a proxy. Only the edge marker survives Spring's
     * {@code framework} forwarded-header handling; the standard ones cover setups without it.
     */
    private static final List<String> PROXY_HEADERS =
            List.of(EDGE_RELAY_HEADER, "Forwarded", "X-Forwarded-For", "X-Forwarded-Host", "X-Forwarded-Proto");

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
