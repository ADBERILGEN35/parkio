package com.parkio.gateway.infrastructure.security;

import com.parkio.gateway.infrastructure.config.ClientIpResolver;
import com.parkio.gateway.shared.GatewayHeaders;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Stamps every routed request with the client IP the gateway resolved at the edge
 * ({@code X-Parkio-Client-Ip}, CL-F15), so downstream services can key per-client
 * throttling without parsing forwarding headers themselves. Any client-supplied copy is
 * stripped first: like {@code X-User-*}, the header is gateway-set and never client-set.
 *
 * <p>The value comes from {@link ClientIpResolver}: the peer address, or the right-most
 * untrusted {@code X-Forwarded-For} hop when the peer is a configured trusted proxy. With
 * {@code server.forward-headers-strategy=framework} (hosted-beta) the transformer
 * ({@code EdgeOwnedForwardedHeaderTransformer}) has already replaced the peer address with the
 * first {@code X-Forwarded-For} entry and removed the forwarding headers before this filter runs;
 * it ignores a client-supplied RFC 7239 {@code Forwarded} header, and the edge (Caddy) owns
 * {@code X-Forwarded-For} and replaces whatever an internet client sent, so the value here is the
 * edge-observed client. When no address can be determined the header is
 * left absent and the consumer falls back to its shared bucket; nothing fails open.
 */
@Component
public class ClientIpHeaderGlobalFilter implements GlobalFilter, Ordered {

    private final ClientIpResolver clientIpResolver;

    public ClientIpHeaderGlobalFilter(ClientIpResolver clientIpResolver) {
        this.clientIpResolver = clientIpResolver;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String clientIp = clientIpResolver.resolve(exchange.getRequest());
        ServerHttpRequest mutated = exchange.getRequest().mutate()
                .headers(headers -> {
                    headers.remove(GatewayHeaders.CLIENT_IP); // never trust an inbound copy
                    if (clientIp != null) {
                        headers.set(GatewayHeaders.CLIENT_IP, clientIp);
                    }
                })
                .build();
        return chain.filter(exchange.mutate().request(mutated).build());
    }

    @Override
    public int getOrder() {
        // Right after the gateway-auth stamp (+5), before authentication (+10).
        return Ordered.HIGHEST_PRECEDENCE + 6;
    }
}
