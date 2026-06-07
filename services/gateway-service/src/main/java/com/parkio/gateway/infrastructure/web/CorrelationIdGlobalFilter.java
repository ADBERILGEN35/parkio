package com.parkio.gateway.infrastructure.web;

import com.parkio.gateway.shared.GatewayHeaders;
import java.util.UUID;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Ensures every request carries a correlation id. If the client supplies
 * {@code X-Correlation-Id} it is forwarded; otherwise one is generated. The id is
 * propagated downstream, stored on the exchange (so error responses can quote it
 * as {@code traceId}), and echoed on the response.
 *
 * <p>Runs first ({@link Ordered#HIGHEST_PRECEDENCE}) so the id is available to
 * every later filter, including authentication error responses.
 */
@Component
public class CorrelationIdGlobalFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String existing = request.getHeaders().getFirst(GatewayHeaders.CORRELATION_ID);
        String correlationId = (existing != null && !existing.isBlank())
                ? existing.trim()
                : UUID.randomUUID().toString();

        ServerHttpRequest mutated = request.mutate()
                .header(GatewayHeaders.CORRELATION_ID, correlationId)
                .build();

        exchange.getResponse().getHeaders().set(GatewayHeaders.CORRELATION_ID, correlationId);
        exchange.getAttributes().put(GatewayHeaders.CORRELATION_ID_ATTRIBUTE, correlationId);

        return chain.filter(exchange.mutate().request(mutated).build());
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
