package com.parkio.gateway.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.gateway.shared.ApiError;
import com.parkio.gateway.shared.GatewayHeaders;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Flux;

/**
 * Edge error writer must emit the requested status verbatim (401/403) and never
 * collapse client errors into 500. Downstream 4xx bodies are proxied unchanged by
 * Spring Cloud Gateway; this covers edge-owned responses.
 */
class GatewayErrorResponseWriterTest {

    private final GatewayErrorResponseWriter writer = new GatewayErrorResponseWriter(
            new ObjectMapper().findAndRegisterModules(),
            Clock.fixed(Instant.parse("2026-07-30T12:00:00Z"), ZoneOffset.UTC));

    @Test
    void writesUnauthorizedWithoutLeakingInternals() throws Exception {
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.method(HttpMethod.GET, "/api/v1/parking/spots/nearby").build());
        exchange.getAttributes().put(GatewayHeaders.CORRELATION_ID_ATTRIBUTE, "corr-401");

        writer.write(exchange, HttpStatus.UNAUTHORIZED, "MISSING_TOKEN",
                "Authentication token is required.").block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        ApiError body = readBody(exchange);
        assertThat(body.code()).isEqualTo("MISSING_TOKEN");
        assertThat(body.traceId()).isEqualTo("corr-401");
        assertThat(body.message()).doesNotContain("Exception", "stack", "Bearer");
    }

    @Test
    void writesForbiddenWithoutConvertingToInternalError() throws Exception {
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.method(HttpMethod.GET, "/api/v1/analytics/overview").build());
        exchange.getAttributes().put(GatewayHeaders.CORRELATION_ID_ATTRIBUTE, "corr-403");

        writer.write(exchange, HttpStatus.FORBIDDEN, "FORBIDDEN",
                "Insufficient role for this resource.").block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        ApiError body = readBody(exchange);
        assertThat(body.code()).isEqualTo("FORBIDDEN");
        assertThat(body.traceId()).isEqualTo("corr-403");
    }

    private ApiError readBody(MockServerWebExchange exchange) throws Exception {
        Flux<DataBuffer> body = exchange.getResponse().getBody();
        DataBuffer buffer = body.single().block();
        assertThat(buffer).isNotNull();
        byte[] bytes = new byte[buffer.readableByteCount()];
        buffer.read(bytes);
        return new ObjectMapper().findAndRegisterModules()
                .readValue(new String(bytes, StandardCharsets.UTF_8), ApiError.class);
    }
}