package com.parkio.gateway.infrastructure.security;

import com.parkio.gateway.infrastructure.web.GatewayErrorResponseWriter;
import com.parkio.gateway.shared.GatewayHeaders;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Edge role-based authorization. Runs immediately after
 * {@link AuthenticationGlobalFilter}, so it reads the <em>trusted</em>, gateway-injected
 * {@code X-User-Roles} header (client-supplied copies were already stripped and replaced
 * with the validated roles). It consults {@link RouteAuthorizationRules}; if a route
 * requires roles the caller lacks, the request is rejected at the edge with a consistent
 * {@code 403} {@link com.parkio.gateway.shared.ApiError} carrying the correlation id,
 * never reaching the downstream service.
 */
@Component
public class AuthorizationGlobalFilter implements GlobalFilter, Ordered {

    private final RouteAuthorizationRules rules;
    private final GatewayErrorResponseWriter errorWriter;

    public AuthorizationGlobalFilter(RouteAuthorizationRules rules, GatewayErrorResponseWriter errorWriter) {
        this.rules = rules;
        this.errorWriter = errorWriter;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        Optional<Set<String>> required =
                rules.requiredRoles(request.getMethod(), request.getPath().pathWithinApplication());
        if (required.isEmpty()) {
            return chain.filter(exchange);
        }

        Set<String> userRoles = parseRoles(request.getHeaders().getFirst(GatewayHeaders.USER_ROLES));
        boolean permitted = userRoles.stream().anyMatch(required.get()::contains);
        if (!permitted) {
            return errorWriter.write(exchange, HttpStatus.FORBIDDEN, "FORBIDDEN",
                    "You do not have permission to access this resource.");
        }
        return chain.filter(exchange);
    }

    private static Set<String> parseRoles(String header) {
        if (header == null || header.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(header.split(","))
                .map(String::trim)
                .filter(role -> !role.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public int getOrder() {
        // Just after authentication (HIGHEST_PRECEDENCE + 10), before routing.
        return Ordered.HIGHEST_PRECEDENCE + 20;
    }
}
