package com.parkio.auth.infrastructure.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Materialises the deferred CSRF token on the web-shaped requests that hand the token out (login, register,
 * refresh and {@code GET /api/v1/auth/csrf}), so the {@code XSRF-TOKEN} cookie is issued together with the
 * body token. Other requests (bearer calls, JWKS, native clients) never receive the cookie; the protected
 * endpoints load the token themselves.
 */
public class CsrfCookieFilter extends OncePerRequestFilter {

    private static final Set<String> HANDOUT_PATHS = Set.of(
            CookieTransportCsrf.COOKIE_PATH + "/login",
            CookieTransportCsrf.COOKIE_PATH + "/register",
            CookieTransportCsrf.COOKIE_PATH + "/refresh-token",
            CookieTransportCsrf.COOKIE_PATH + "/csrf");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // Web-shaped hand-out requests only: native clients (body-token transport) get no cookie at all.
        if (HANDOUT_PATHS.contains(request.getRequestURI())
                && !CookieTransportCsrf.isMobileShaped(request)) {
            CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
            if (token != null) {
                token.getToken();
            }
        }
        chain.doFilter(request, response);
    }
}
