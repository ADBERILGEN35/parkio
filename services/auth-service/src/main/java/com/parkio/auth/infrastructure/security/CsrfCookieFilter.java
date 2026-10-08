package com.parkio.auth.infrastructure.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Materialises the deferred CSRF token on web-shaped auth requests, so the {@code XSRF-TOKEN} cookie is issued
 * by the first auth call (login, or {@code GET /api/v1/auth/csrf}) and the controller can hand the token out.
 * Native clients never receive it.
 */
public class CsrfCookieFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // Web-shaped auth requests only: native clients (body-token transport) get no cookie at all.
        if (request.getRequestURI().startsWith(CookieTransportCsrf.COOKIE_PATH + "/")
                && !CookieTransportCsrf.isMobileShaped(request)) {
            CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
            if (token != null) {
                token.getToken();
            }
        }
        chain.doFilter(request, response);
    }
}
