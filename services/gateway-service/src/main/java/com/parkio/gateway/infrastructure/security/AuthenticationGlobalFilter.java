package com.parkio.gateway.infrastructure.security;

import com.parkio.gateway.infrastructure.web.GatewayErrorResponseWriter;
import com.parkio.gateway.shared.GatewayHeaders;
import io.jsonwebtoken.JwtException;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Edge authentication. For every request this filter:
 * <ol>
 *   <li>strips any client-supplied {@code X-User-*} identity headers — clients must
 *       never control identity (defense in depth, even on public routes);</li>
 *   <li>lets {@link PublicEndpoints public routes} through without a token;</li>
 *   <li>otherwise requires a valid {@code Bearer} access token, rejecting missing
 *       or invalid tokens with a consistent JSON error;</li>
 *   <li>on success, injects the verified identity as trusted {@code X-User-*}
 *       headers for downstream services.</li>
 * </ol>
 *
 * <p>Runs just after {@code CorrelationIdGlobalFilter} so error responses carry the
 * correlation id, and well before the routing filter.
 */
@Component
public class AuthenticationGlobalFilter implements GlobalFilter, Ordered {

    private static final String BEARER_PREFIX = "Bearer ";

    private final PublicEndpoints publicEndpoints;
    private final JwtTokenValidator tokenValidator;
    private final GatewayErrorResponseWriter errorWriter;

    public AuthenticationGlobalFilter(PublicEndpoints publicEndpoints,
                                      JwtTokenValidator tokenValidator,
                                      GatewayErrorResponseWriter errorWriter) {
        this.publicEndpoints = publicEndpoints;
        this.tokenValidator = tokenValidator;
        this.errorWriter = errorWriter;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();

        // Always remove inbound identity headers: a client must never inject them.
        ServerHttpRequest.Builder builder = request.mutate().headers(headers -> {
            headers.remove(GatewayHeaders.USER_ID);
            headers.remove(GatewayHeaders.USER_EMAIL);
            headers.remove(GatewayHeaders.USER_ROLES);
        });

        if (publicEndpoints.isPublic(request)) {
            return chain.filter(exchange.mutate().request(builder.build()).build());
        }

        String authorization = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            return errorWriter.write(exchange, HttpStatus.UNAUTHORIZED, "MISSING_TOKEN",
                    "Authentication token is required.");
        }

        String token = authorization.substring(BEARER_PREFIX.length()).trim();
        AuthenticatedUser user;
        try {
            user = tokenValidator.validate(token);
        } catch (JwtException | IllegalArgumentException ex) {
            return errorWriter.write(exchange, HttpStatus.UNAUTHORIZED, "INVALID_TOKEN",
                    "Authentication token is invalid or expired.");
        }

        builder.header(GatewayHeaders.USER_ID, user.userId());
        if (user.email() != null) {
            builder.header(GatewayHeaders.USER_EMAIL, user.email());
        }
        builder.header(GatewayHeaders.USER_ROLES, String.join(",", user.roles()));

        return chain.filter(exchange.mutate().request(builder.build()).build());
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}
