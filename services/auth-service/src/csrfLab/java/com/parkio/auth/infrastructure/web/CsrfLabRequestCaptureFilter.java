package com.parkio.auth.infrastructure.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Disposable CSRF-lab capture only. Compiled from the csrfLab source set and
 * attached to bootRun only when -Pparkio.csrfLab=true. Never packaged into the
 * production bootJar. When enabled, appends one JSON line per auth mutation
 * request with Origin/Referer/Cookie names and gateway-auth presence as seen by
 * auth-service after the gateway hop.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
@ConditionalOnProperty(name = "parkio.csrf-lab.capture-enabled", havingValue = "true")
public class CsrfLabRequestCaptureFilter extends OncePerRequestFilter {

    private final Path captureFile;
    private final ObjectMapper objectMapper;

    public CsrfLabRequestCaptureFilter(
            @Value("${parkio.csrf-lab.capture-file}") String captureFile,
            ObjectMapper objectMapper) {
        this.captureFile = Path.of(captureFile);
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return !(uri.startsWith("/api/v1/auth/login")
                || uri.startsWith("/api/v1/auth/refresh-token")
                || uri.startsWith("/api/v1/auth/logout"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {
        filterChain.doFilter(request, response);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("ts", Instant.now().toString());
        row.put("method", request.getMethod());
        row.put("path", request.getRequestURI());
        row.put("origin", request.getHeader("Origin"));
        row.put("referer", request.getHeader("Referer"));
        row.put("hasGatewayAuth", request.getHeader("X-Gateway-Auth") != null
                && !request.getHeader("X-Gateway-Auth").isBlank());
        row.put("parkioClient", request.getHeader("X-Parkio-Client"));
        row.put("hasAuthorization", request.getHeader("Authorization") != null);
        Cookie[] cookies = request.getCookies();
        row.put(
                "cookieNames",
                cookies == null
                        ? ""
                        : Arrays.stream(cookies).map(Cookie::getName).collect(Collectors.joining(",")));
        row.put("hasParkioRefreshCookie", cookies != null && Arrays.stream(cookies)
                .anyMatch(c -> "parkio_refresh".equals(c.getName())));
        row.put("status", response.getStatus());
        Files.createDirectories(captureFile.getParent() == null ? Path.of(".") : captureFile.getParent());
        Files.writeString(
                captureFile,
                objectMapper.writeValueAsString(row) + "\n",
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }
}
