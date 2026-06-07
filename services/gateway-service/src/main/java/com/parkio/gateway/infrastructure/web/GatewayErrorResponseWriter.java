package com.parkio.gateway.infrastructure.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.gateway.shared.ApiError;
import com.parkio.gateway.shared.GatewayHeaders;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Writes a consistent JSON {@link ApiError} directly to the response when the edge
 * rejects a request (e.g. missing/invalid token), without proxying downstream. The
 * resolved correlation id is echoed as {@code traceId} (ai-context/04).
 */
@Component
public class GatewayErrorResponseWriter {

    private static final Logger log = LoggerFactory.getLogger(GatewayErrorResponseWriter.class);

    private final ObjectMapper objectMapper;
    private final Clock clock;

    public GatewayErrorResponseWriter(ObjectMapper objectMapper, Clock clock) {
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public Mono<Void> write(ServerWebExchange exchange, HttpStatus status, String code, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        String correlationId = (String) exchange.getAttributes().get(GatewayHeaders.CORRELATION_ID_ATTRIBUTE);
        ApiError body = new ApiError(code, message, correlationId, clock.instant());

        byte[] bytes;
        try {
            bytes = objectMapper.writeValueAsBytes(body);
        } catch (JsonProcessingException ex) {
            log.error("Failed to serialize gateway error response", ex);
            bytes = ("{\"code\":\"" + code + "\"}").getBytes(StandardCharsets.UTF_8);
        }
        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }
}
