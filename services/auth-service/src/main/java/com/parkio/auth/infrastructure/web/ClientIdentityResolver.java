package com.parkio.auth.infrastructure.web;

import com.parkio.auth.application.LoginClientKeys;
import com.parkio.auth.application.LoginFailureTracker;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * Client identity for login throttling (CL-F15): the client IP the gateway resolved and
 * stamped as {@code X-Parkio-Client-Ip}. The header is trusted only on a request that
 * {@link GatewayAuthFilter} authenticated as coming from the gateway, and only when it is an
 * IP literal; anything else falls back to the shared {@link LoginFailureTracker#UNKNOWN_CLIENT}
 * bucket, which is the pre-CL-F15 behaviour for that account. IPv6 addresses become their /64
 * network ({@link LoginClientKeys}).
 */
@Component
public class ClientIdentityResolver {

    static final String CLIENT_IP_HEADER = "X-Parkio-Client-Ip";

    public String clientKey(HttpServletRequest request) {
        if (!Boolean.TRUE.equals(request.getAttribute(GatewayAuthFilter.GATEWAY_AUTHENTICATED_ATTRIBUTE))) {
            return LoginFailureTracker.UNKNOWN_CLIENT;
        }
        String value = request.getHeader(CLIENT_IP_HEADER);
        if (value == null) {
            return LoginFailureTracker.UNKNOWN_CLIENT;
        }
        return LoginClientKeys.fromIpLiteral(value);
    }
}
