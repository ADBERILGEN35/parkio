package com.parkio.auth.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.stereotype.Component;

/** 403 in the service's JSON error shape; names the CSRF cause so the SPA can fetch a token and retry once. */
@Component
public class CsrfAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;
    private final Clock clock;

    public CsrfAccessDeniedHandler(ObjectMapper objectMapper, Clock clock) {
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException exception)
            throws IOException {
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        Map<String, Object> body = new LinkedHashMap<>();
        boolean csrf = exception instanceof CsrfException;
        body.put("code", csrf ? "CSRF_TOKEN_REQUIRED" : "FORBIDDEN");
        body.put("message", csrf
                ? "This cookie-authenticated request needs the X-XSRF-TOKEN header matching the XSRF-TOKEN cookie."
                : "Access is denied.");
        body.put("traceId", MDC.get("correlationId"));
        body.put("timestamp", clock.instant().toString());
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
