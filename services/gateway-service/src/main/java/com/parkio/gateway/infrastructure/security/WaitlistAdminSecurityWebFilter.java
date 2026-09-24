package com.parkio.gateway.infrastructure.security;

import com.parkio.gateway.infrastructure.web.GatewayErrorResponseWriter;
import com.parkio.gateway.shared.GatewayHeaders;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.PathContainer;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.reactive.CorsUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import reactor.core.publisher.Mono;

/**
 * Protects waitlist operator endpoints served by the gateway's own
 * {@code @RestController} (not proxied through Spring Cloud Gateway routes).
 *
 * <p>{@link AuthenticationGlobalFilter}, {@link AuthorizationGlobalFilter},
 * {@link SessionEpochGlobalFilter} and {@link AccountStatusGlobalFilter} only run on the
 * Spring Cloud Gateway filter chain. Local WebFlux controllers bypass that chain, so this
 * WebFilter applies the same contract to the waitlist admin/export paths: valid JWT,
 * {@code ADMIN}/{@code SUPER_ADMIN} role, current session epoch ({@link SessionEpochVerifier})
 * and an {@code ACTIVE} account ({@link AccountStatusVerifier}) — each failing closed.
 *
 * <p>Protection is decided by path alone, never by HTTP method: WebFlux serves
 * {@code HEAD} from {@code @GetMapping} handlers, and any other method must be refused
 * (401/403) before it can reach the controller's 405. The one exemption is a genuine CORS
 * preflight, which carries no credentials and is answered by {@code CorsWebFilter} without
 * reaching a handler.
 *
 * <p>Client-supplied {@code X-User-*} headers are stripped before JWT validation. Roles are
 * taken only from a validated token.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class WaitlistAdminSecurityWebFilter implements WebFilter {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final Set<String> ADMIN_ONLY = Set.of("ADMIN", "SUPER_ADMIN");
    private static final PathPatternParser PARSER = PathPatternParser.defaultInstance;
    private static final List<PathPattern> PROTECTED = List.of(
            PARSER.parse("/api/v1/waitlist/export"),
            PARSER.parse("/api/v1/waitlist/export/**"),
            PARSER.parse("/api/v1/waitlist/admin"),
            PARSER.parse("/api/v1/waitlist/admin/**"));

    private final JwtTokenValidator tokenValidator;
    private final SessionEpochVerifier epochVerifier;
    private final AccountStatusVerifier statusVerifier;
    private final GatewayErrorResponseWriter errorWriter;

    public WaitlistAdminSecurityWebFilter(JwtTokenValidator tokenValidator,
                                          SessionEpochVerifier epochVerifier,
                                          AccountStatusVerifier statusVerifier,
                                          GatewayErrorResponseWriter errorWriter) {
        this.tokenValidator = tokenValidator;
        this.epochVerifier = epochVerifier;
        this.statusVerifier = statusVerifier;
        this.errorWriter = errorWriter;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        if (!isProtected(request.getPath().pathWithinApplication())) {
            return chain.filter(exchange);
        }
        if (CorsUtils.isPreFlightRequest(request)) {
            // Browsers never send credentials on a preflight; CorsWebFilter answers it
            // (allowed or rejected) and never forwards it to a handler.
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
        // Only validation failures map to INVALID_TOKEN; an empty result is also treated as
        // invalid so a request can never complete without a decision.
        return tokenValidator.validate(token)
                .map(Optional::of)
                .onErrorResume(ex -> Mono.just(Optional.empty()))
                .defaultIfEmpty(Optional.empty())
                .flatMap(user -> user.isPresent()
                        ? authorize(exchange, chain, builder, user.get())
                        : errorWriter.write(exchange, HttpStatus.UNAUTHORIZED, "INVALID_TOKEN",
                                "Authentication token is invalid or expired."));
    }

    private Mono<Void> authorize(ServerWebExchange exchange, WebFilterChain chain,
                                 ServerHttpRequest.Builder builder, AuthenticatedUser user) {
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
        // Absent epoch claim (legacy token) → 0, as on routed traffic.
        long tokenEpoch = user.sessionEpoch() == null ? 0L : user.sessionEpoch();
        // Same order as the routed chain: session epoch (+25), then account status (+30).
        return epochVerifier.verify(exchange, user.userId(), tokenEpoch,
                () -> statusVerifier.verify(exchange, user.userId(),
                        () -> chain.filter(exchange.mutate().request(builder.build()).build())));
    }

    public static boolean isProtected(PathContainer path) {
        return PROTECTED.stream().anyMatch(pattern -> pattern.matches(path));
    }
}
