package com.parkio.gateway.infrastructure.web;

import com.parkio.gateway.shared.GatewayHeaders;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Ensures every request carries a correlation id. A client-supplied
 * {@code X-Correlation-Id} is forwarded only if, once trimmed, it is 1 to
 * {@value #MAX_LENGTH} characters of {@code A-Z a-z 0-9 . _ : -} (a UUID, for example).
 * Any other value (control characters such as CR/LF, spaces, non-ASCII, oversized) is
 * replaced by a generated id, as is a missing one: the value is copied into downstream
 * headers, error bodies ({@code traceId}) and logs, so it must not carry log-injection
 * payloads or unbounded data. The id is propagated downstream, stored on the exchange
 * (so error responses can quote it as {@code traceId}), and echoed on the response.
 *
 * <p>Runs first ({@link Ordered#HIGHEST_PRECEDENCE}) so the id is available to
 * every later filter, including authentication error responses.
 */
@Component
public class CorrelationIdGlobalFilter implements GlobalFilter, Ordered {

    private static final int MAX_LENGTH = 128;

    private static final Pattern ACCEPTED = Pattern.compile("[A-Za-z0-9._:-]{1," + MAX_LENGTH + "}");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String correlationId = accepted(request.getHeaders().getFirst(GatewayHeaders.CORRELATION_ID));

        ServerHttpRequest mutated = request.mutate()
                .header(GatewayHeaders.CORRELATION_ID, correlationId)
                .build();

        exchange.getResponse().getHeaders().set(GatewayHeaders.CORRELATION_ID, correlationId);
        exchange.getAttributes().put(GatewayHeaders.CORRELATION_ID_ATTRIBUTE, correlationId);

        return chain.filter(exchange.mutate().request(mutated).build());
    }

    private static String accepted(String supplied) {
        if (supplied != null) {
            String trimmed = supplied.trim();
            if (ACCEPTED.matcher(trimmed).matches()) {
                return trimmed;
            }
        }
        return UUID.randomUUID().toString();
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
