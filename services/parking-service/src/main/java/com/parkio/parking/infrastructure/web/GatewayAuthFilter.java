package com.parkio.parking.infrastructure.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
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

    private final List<byte[]> acceptedSecrets;
    private final ObjectMapper objectMapper;

    @Autowired
    public GatewayAuthFilter(@Value("${parkio.gateway.internal-secret}") String internalSecret,
                             @Value("${parkio.gateway.internal-accepted-secrets:}") String additionalAcceptedSecrets,
                             ObjectMapper objectMapper) {
        if (!StringUtils.hasText(internalSecret)) {
            throw new IllegalStateException(
                    "parkio.gateway.internal-secret (PARKIO_GATEWAY_INTERNAL_SECRET) must be configured");
        }
        this.acceptedSecrets = buildAcceptedSecrets(internalSecret, additionalAcceptedSecrets);
        this.objectMapper = objectMapper;
    }

    /** Test/convenience constructor: current secret only (no previous-secret rotation window). */
    public GatewayAuthFilter(String internalSecret, ObjectMapper objectMapper) {
        this(internalSecret, "", objectMapper);
    }

    private static List<byte[]> buildAcceptedSecrets(String current, String additionalCsv) {
        List<byte[]> secrets = new ArrayList<>();
        secrets.add(current.getBytes(StandardCharsets.UTF_8));
        if (StringUtils.hasText(additionalCsv)) {
            for (String candidate : additionalCsv.split(",")) {
                String trimmed = candidate.trim();
                if (!trimmed.isEmpty()) {
                    secrets.add(trimmed.getBytes(StandardCharsets.UTF_8));
                }
            }
        }
        return List.copyOf(secrets);
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
        if (provided == null) {
            return false;
        }
        byte[] providedBytes = provided.getBytes(StandardCharsets.UTF_8);
        boolean matched = false;
        for (byte[] secret : acceptedSecrets) {
            // Constant-time per candidate; OR-accumulate without early return so timing
            // cannot reveal which secret (current vs previous) matched.
            matched |= MessageDigest.isEqual(providedBytes, secret);
        }
        return matched;
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
