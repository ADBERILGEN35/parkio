package com.parkio.gateway.infrastructure.security;

import com.parkio.gateway.shared.GatewayHeaders;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Stamps every routed request with the internal {@code X-Gateway-Auth} shared secret so
 * downstream services can prove the request came through this gateway (the only public
 * ingress). Any client-supplied {@code X-Gateway-Auth} is stripped first and replaced
 * with the configured value — a client must never control it.
 *
 * <p>Applies to <strong>all</strong> routes (public and protected alike), since every
 * downstream API/internal endpoint requires the header. Runs just after
 * {@code CorrelationIdGlobalFilter} and before authentication. The secret is externalized
 * ({@code PARKIO_GATEWAY_INTERNAL_SECRET}); the context fails to start without it
 * (fail closed, ai-context/07).
 */
@Component
public class GatewayAuthHeaderGlobalFilter implements GlobalFilter, Ordered {

    private final String internalSecret;

    public GatewayAuthHeaderGlobalFilter(@Value("${parkio.gateway.internal-secret}") String internalSecret) {
        if (!StringUtils.hasText(internalSecret)) {
            throw new IllegalStateException(
                    "parkio.gateway.internal-secret (PARKIO_GATEWAY_INTERNAL_SECRET) must be configured");
        }
        this.internalSecret = internalSecret;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest mutated = exchange.getRequest().mutate()
                .headers(headers -> {
                    headers.remove(GatewayHeaders.GATEWAY_AUTH); // never trust an inbound copy
                    headers.set(GatewayHeaders.GATEWAY_AUTH, internalSecret);
                })
                .build();
        return chain.filter(exchange.mutate().request(mutated).build());
    }

    @Override
    public int getOrder() {
        // After CorrelationId (HIGHEST_PRECEDENCE), before authentication (+10).
        return Ordered.HIGHEST_PRECEDENCE + 5;
    }
}
