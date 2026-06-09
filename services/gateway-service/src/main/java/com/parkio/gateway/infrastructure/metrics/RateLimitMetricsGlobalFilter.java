package com.parkio.gateway.infrastructure.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Counts {@code parkio.gateway.rate.limit.rejected.count}: requests answered with
 * {@code 429 Too Many Requests}. Backend services never return 429 themselves, so every
 * occurrence is the edge {@code RequestRateLimiter} rejecting a caller — a sustained
 * spike means abuse or an undersized rate limit.
 *
 * <p>Runs at order +1 (after CorrelationId, before everything else) so the counter
 * still fires when the route's rate limiter short-circuits the chain.
 */
@Component
public class RateLimitMetricsGlobalFilter implements GlobalFilter, Ordered {

    private final Counter rejected;

    public RateLimitMetricsGlobalFilter(MeterRegistry registry) {
        this.rejected = Counter.builder("parkio.gateway.rate.limit.rejected.count")
                .description("Requests rejected at the edge with 429 Too Many Requests")
                .register(registry);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return chain.filter(exchange).doFinally(signal -> {
            if (HttpStatus.TOO_MANY_REQUESTS.equals(exchange.getResponse().getStatusCode())) {
                rejected.increment();
            }
        });
    }

    @Override
    public int getOrder() {
        // After CorrelationId (HIGHEST_PRECEDENCE), before gateway-auth header (+5).
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }
}
