package com.parkio.auth.infrastructure.web;

import com.parkio.auth.application.LoginFailureTracker;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Client identity for login throttling (CL-F15): the client IP the gateway resolved and
 * stamped as {@code X-Parkio-Client-Ip}. The header is trusted only on a request that
 * {@link GatewayAuthFilter} authenticated as coming from the gateway, and only when it is an
 * IP literal; anything else falls back to the shared {@link LoginFailureTracker#UNKNOWN_CLIENT}
 * bucket, which is the pre-CL-F15 behaviour for that account.
 */
@Component
public class ClientIdentityResolver {

    static final String CLIENT_IP_HEADER = "X-Parkio-Client-Ip";

    /** IPv4 or IPv6 literal; no host names, zones or brackets (the gateway normalises those away). */
    private static final Pattern IP_LITERAL = Pattern.compile("^[0-9a-fA-F:.]{2,45}$");

    public String clientKey(HttpServletRequest request) {
        if (!Boolean.TRUE.equals(request.getAttribute(GatewayAuthFilter.GATEWAY_AUTHENTICATED_ATTRIBUTE))) {
            return LoginFailureTracker.UNKNOWN_CLIENT;
        }
        String value = request.getHeader(CLIENT_IP_HEADER);
        if (value == null) {
            return LoginFailureTracker.UNKNOWN_CLIENT;
        }
        String trimmed = value.trim();
        if (!IP_LITERAL.matcher(trimmed).matches()) {
            return LoginFailureTracker.UNKNOWN_CLIENT;
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }
}
