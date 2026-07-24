package com.parkio.parking.infrastructure.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.regex.Pattern;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Prevents storage of session and community-claim responses containing precise location data. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class ParkingSessionNoStoreFilter extends OncePerRequestFilter {

    private static final String SESSION_PATH_PREFIX = "/api/v1/parking/sessions";
    private static final Pattern COMMUNITY_CLAIM_PATH =
            Pattern.compile("^/api/v1/parking/spots/[^/]+/claim$");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String requestUri = request.getRequestURI();
        boolean parkingSessionPath = requestUri.equals(SESSION_PATH_PREFIX)
                || requestUri.startsWith(SESSION_PATH_PREFIX + "/");
        return !parkingSessionPath && !COMMUNITY_CLAIM_PATH.matcher(requestUri).matches();
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        filterChain.doFilter(request, response);
    }
}
