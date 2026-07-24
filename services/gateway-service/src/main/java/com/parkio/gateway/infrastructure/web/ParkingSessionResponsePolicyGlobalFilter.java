package com.parkio.gateway.infrastructure.web;

import java.util.regex.Pattern;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Applies the frozen cache and edge-error contract to location-sensitive parking routes. */
@Component
public class ParkingSessionResponsePolicyGlobalFilter implements GlobalFilter, Ordered {

    private static final String SESSION_PATH_PREFIX = "/api/v1/parking/sessions";
    private static final Pattern COMMUNITY_CLAIM_PATH =
            Pattern.compile("^/api/v1/parking/spots/[^/]+/claim$");

    private final GatewayErrorResponseWriter errorWriter;

    public ParkingSessionResponsePolicyGlobalFilter(GatewayErrorResponseWriter errorWriter) {
        this.errorWriter = errorWriter;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!isSensitiveParkingPath(exchange)) {
            return chain.filter(exchange);
        }

        ServerHttpResponse response = exchange.getResponse();
        response.getHeaders().set(HttpHeaders.CACHE_CONTROL, "no-store");
        ServerHttpResponseDecorator decoratedResponse = new ServerHttpResponseDecorator(response) {
            @Override
            public Mono<Void> setComplete() {
                if (!response.isCommitted()
                        && HttpStatus.TOO_MANY_REQUESTS.equals(response.getStatusCode())) {
                    return errorWriter.write(
                            exchange,
                            HttpStatus.TOO_MANY_REQUESTS,
                            "RATE_LIMITED",
                            "Too many requests. Try again later.");
                }
                return super.setComplete();
            }
        };
        return chain.filter(exchange.mutate().response(decoratedResponse).build());
    }

    @Override
    public int getOrder() {
        // After correlation/path/metrics filters, before authentication and route filters.
        return Ordered.HIGHEST_PRECEDENCE + 3;
    }

    private static boolean isSensitiveParkingPath(ServerWebExchange exchange) {
        String path = exchange.getRequest().getPath().pathWithinApplication().value();
        return path.equals(SESSION_PATH_PREFIX)
                || path.startsWith(SESSION_PATH_PREFIX + "/")
                || COMMUNITY_CLAIM_PATH.matcher(path).matches();
    }
}
