package com.parkio.gateway.infrastructure.security;

import com.parkio.gateway.infrastructure.web.GatewayErrorResponseWriter;
import com.parkio.gateway.shared.GatewayHeaders;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.PathContainer;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import reactor.core.publisher.Mono;

/**
 * Protects waitlist operator endpoints that are served by the gateway's own
 * {@code @RestController} (not proxied through Spring Cloud Gateway routes).
 *
 * <p>{@link AuthenticationGlobalFilter} / {@link AuthorizationGlobalFilter} only run on
 * the gateway filter chain. Local WebFlux controllers bypass that chain, so ADMIN-only
 * waitlist export/list must be enforced here at the WebFilter layer (same rationale as
 * {@link ActuatorPublicSurfaceWebFilter}).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class WaitlistAdminSecurityWebFilter implements WebFilter {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final Set<String> ADMIN_ONLY = Set.of("ADMIN", "SUPER_ADMIN");
    private static final PathPatternParser PARSER = PathPatternParser.defaultInstance;
    private static final List<PathPattern> PROTECTED = List.of(
            PARSER.parse("/api/v1/waitlist/export"),
            PARSER.parse("/api/v1/waitlist/admin"),
            PARSER.parse("/api/v1/waitlist/admin/**"));

    private final JwtTokenValidator tokenValidator;
    private final GatewayErrorResponseWriter errorWriter;

    public WaitlistAdminSecurityWebFilter(
            JwtTokenValidator tokenValidator, GatewayErrorResponseWriter errorWriter) {
        this.tokenValidator = tokenValidator;
        this.errorWriter = errorWriter;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        PathContainer path = request.getPath().pathWithinApplication();
        if (!isProtected(request.getMethod(), path)) {
            return chain.filter(exchange);
        }

        ServerHttpRequest.Builder builder = request.mutate().headers(headers -> {
            headers.remove(GatewayHeaders.USER_ID);
            headers.remove(GatewayHeaders.USER_EMAIL);
            headers.remove(GatewayHeaders.USER_ROLES);
        });

        String authorization = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            return errorWriter.write(exchange, HttpStatus.UNAUTHORIZED, "MISSING_TOKEN",
                    "Authentication token is required.");
        }

        String token = authorization.substring(BEARER_PREFIX.length()).trim();
        return tokenValidator.validate(token)
                .flatMap(user -> {
                    Set<String> roles = user.roles().stream()
                            .map(role -> role == null ? "" : role.trim().toUpperCase(Locale.ROOT))
                            .filter(role -> !role.isEmpty())
                            .collect(Collectors.toUnmodifiableSet());
                    boolean permitted = roles.stream().anyMatch(ADMIN_ONLY::contains);
                    if (!permitted) {
                        return errorWriter.write(exchange, HttpStatus.FORBIDDEN, "FORBIDDEN",
                                "You do not have permission to access this resource.");
                    }
                    builder.header(GatewayHeaders.USER_ID, user.userId());
                    if (user.email() != null) {
                        builder.header(GatewayHeaders.USER_EMAIL, user.email());
                    }
                    builder.header(GatewayHeaders.USER_ROLES, String.join(",", roles));
                    return chain.filter(exchange.mutate().request(builder.build()).build());
                })
                .onErrorResume(ex -> errorWriter.write(exchange, HttpStatus.UNAUTHORIZED, "INVALID_TOKEN",
                        "Authentication token is invalid or expired."));
    }

    static boolean isProtected(HttpMethod method, PathContainer path) {
        if (method != HttpMethod.GET) {
            return false;
        }
        return PROTECTED.stream().anyMatch(pattern -> pattern.matches(path));
    }
}
