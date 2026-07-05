package com.parkio.gateway.infrastructure.web;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class PathCanonicalizationGlobalFilter implements GlobalFilter, Ordered {

    private final GatewayErrorResponseWriter errorWriter;

    public PathCanonicalizationGlobalFilter(GatewayErrorResponseWriter errorWriter) {
        this.errorWriter = errorWriter;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String rawPath = exchange.getRequest().getURI().getRawPath();
        if (PathCanonicalization.isUnsafeRawPath(rawPath)) {
            return errorWriter.write(
                    exchange,
                    HttpStatus.BAD_REQUEST,
                    "INVALID_REQUEST_PATH",
                    "Request path is not allowed.");
        }
        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }
}