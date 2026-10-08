package com.parkio.auth.infrastructure.security;

import com.parkio.auth.presentation.RefreshCookieProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Route-specific CSRF protection for the cookie-authenticated endpoints (CodeQL alert #7, owner decision
 * 2026-10-08). Only the two endpoints that act on the HttpOnly refresh cookie are protected, and only when
 * the request is browser-shaped; native clients (`X-Parkio-Client: mobile`, no Origin/Referer) carry the
 * refresh token in the body and no cookie, so there is nothing for a cross-site page to ride on. Every
 * other endpoint authenticates with a bearer token or with credentials in the body.
 *
 * <p>Double submit: the token lives in the HttpOnly {@code XSRF-TOKEN} cookie (same-site, scoped to
 * {@code /api/v1/auth}) and must be repeated in the {@code X-XSRF-TOKEN} header. The SPA cannot read the
 * cookie (the API is another origin), so the server hands the token out in the body of the web login and
 * refresh responses and on {@code GET /api/v1/auth/csrf}; a cross-site page can read neither (CORS) and
 * cannot forge the header. The Origin/Referer guard in {@code AuthController} stays as the second layer.
 */
public final class CookieTransportCsrf {

    public static final String COOKIE_NAME = "XSRF-TOKEN";
    public static final String HEADER_NAME = "X-XSRF-TOKEN";
    public static final String COOKIE_PATH = "/api/v1/auth";
    public static final Set<String> PROTECTED_PATHS = Set.of("/api/v1/auth/refresh-token", "/api/v1/auth/logout");

    private CookieTransportCsrf() {
    }

    /**
     * POST to a cookie-transport endpoint from a browser-shaped client that presents the refresh cookie.
     * Without the cookie there is nothing a cross-site page could ride on, and the endpoint answers 401 as
     * before (a signed-out browser's bootstrap refresh stays one request).
     */
    public static RequestMatcher protectedRequests(String refreshCookieName) {
        return request -> "POST".equalsIgnoreCase(request.getMethod())
                && PROTECTED_PATHS.contains(request.getRequestURI())
                && !isMobileShaped(request)
                && hasCookie(request, refreshCookieName);
    }

    private static boolean hasCookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return false;
        }
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName()) && cookie.getValue() != null && !cookie.getValue().isBlank()) {
                return true;
            }
        }
        return false;
    }

    /** The same rule {@code AuthController} uses to pick the body-token transport. */
    public static boolean isMobileShaped(HttpServletRequest request) {
        return "mobile".equalsIgnoreCase(request.getHeader("X-Parkio-Client"))
                && request.getHeader("Origin") == null
                && request.getHeader("Referer") == null;
    }

    public static CookieCsrfTokenRepository repository(RefreshCookieProperties refreshCookie) {
        CookieCsrfTokenRepository repository = new CookieCsrfTokenRepository();
        repository.setCookieName(COOKIE_NAME);
        repository.setHeaderName(HEADER_NAME);
        repository.setCookiePath(COOKIE_PATH);
        repository.setCookieCustomizer(cookie -> cookie
                .httpOnly(true)
                .secure(refreshCookie.isSecure())
                .sameSite(refreshCookie.getSameSite()));
        return repository;
    }
}
