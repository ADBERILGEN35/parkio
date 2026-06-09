package com.parkio.aivalidation.infrastructure.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gateway-only ingress guard. Requires the shared {@code X-Gateway-Auth} secret on every
 * API/internal request so a directly-reachable service cannot be called without going
 * through the gateway (which injects the secret). Actuator endpoints stay open for
 * probes/metrics scraping (only health/info/prometheus are exposed). Kafka consumers and
 * scheduled jobs are unaffected (not HTTP). The secret is externalized
 * ({@code PARKIO_GATEWAY_INTERNAL_SECRET}); the context fails to start without it
 * (fail closed, ai-context/07).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class GatewayAuthFilter extends OncePerRequestFilter {

    private static final String GATEWAY_AUTH_HEADER = "X-Gateway-Auth";

    private final byte[] expectedSecret;
    private final ObjectMapper objectMapper;

    public GatewayAuthFilter(@Value("${parkio.gateway.internal-secret}") String internalSecret,
                             ObjectMapper objectMapper) {
        if (!StringUtils.hasText(internalSecret)) {
            throw new IllegalStateException(
                    "parkio.gateway.internal-secret (PARKIO_GATEWAY_INTERNAL_SECRET) must be configured");
        }
        this.expectedSecret = internalSecret.getBytes(StandardCharsets.UTF_8);
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        // Actuator and OpenAPI docs skip gateway-auth (probes, metrics scrape, local API contracts).
        return uri.startsWith("/actuator/")
                || uri.startsWith("/v3/api-docs")
                || uri.startsWith("/swagger-ui");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!matches(request.getHeader(GATEWAY_AUTH_HEADER))) {
            writeUnauthorized(response);
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean matches(String provided) {
        return provided != null
                && MessageDigest.isEqual(provided.getBytes(StandardCharsets.UTF_8), expectedSecret);
    }

    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "GATEWAY_AUTH_REQUIRED");
        body.put("message", "Direct service access is not permitted; route requests through the API gateway.");
        body.put("traceId", MDC.get(CorrelationIdFilter.MDC_KEY));
        body.put("timestamp", Instant.now().toString());
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
