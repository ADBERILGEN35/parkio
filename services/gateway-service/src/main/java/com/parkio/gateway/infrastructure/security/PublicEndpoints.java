package com.parkio.gateway.infrastructure.security;

import com.parkio.gateway.infrastructure.config.GatewayPublicSurfaceProperties;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * The allow-list of routes that do not require a valid access token. Everything
 * else is protected and rejected without one (fail closed, ai-context/07).
 *
 * <p>Only the auth bootstrap endpoints (register/login/refresh/logout), JWKS
 * discovery, and the gateway's own health/info actuator endpoints are public.
 * Note: any other {@code /api/v1/auth/**} endpoint is protected by default.
 *
 * <p>When {@code parkio.gateway.public-surface.actuator-info-enabled} is false,
 * {@code /actuator/info} is not on the internet-facing public surface. Loopback
 * peers (invite-production dark / public-staged smoke) may still read identity
 * without a token so internal acceptance keeps working after Level-B sets the
 * flag to false (PROD-DEPLOY-01B-03B). Non-loopback blocking is enforced at
 * the WebFlux layer by {@link ActuatorPublicSurfaceWebFilter} because Spring Boot
 * actuator endpoints bypass the gateway filter chain.
 */
@Component
public class PublicEndpoints {

    private record Rule(HttpMethod method, PathPattern pattern) {

        boolean matches(HttpMethod requestMethod, PathContainer path) {
            return (method == null || method.equals(requestMethod)) && pattern.matches(path);
        }
    }

    private final List<Rule> rules;
    private final PathPattern actuatorInfoPattern;
    private final boolean actuatorInfoPublic;

    public PublicEndpoints(GatewayPublicSurfaceProperties publicSurface) {
        PathPatternParser parser = PathPatternParser.defaultInstance;
        this.actuatorInfoPattern = parser.parse("/actuator/info");
        this.actuatorInfoPublic = publicSurface.isActuatorInfoEnabled();
        List<Rule> built = new ArrayList<>(List.of(
                new Rule(HttpMethod.POST, parser.parse("/api/v1/auth/register")),
                new Rule(HttpMethod.POST, parser.parse("/api/v1/auth/login")),
                new Rule(HttpMethod.POST, parser.parse("/api/v1/auth/verify-email")),
                new Rule(HttpMethod.POST, parser.parse("/api/v1/auth/resend-verification")),
                new Rule(HttpMethod.POST, parser.parse("/api/v1/auth/forgot-password")),
                new Rule(HttpMethod.POST, parser.parse("/api/v1/auth/reset-password")),
                new Rule(HttpMethod.POST, parser.parse("/api/v1/auth/refresh-token")),
                new Rule(HttpMethod.POST, parser.parse("/api/v1/auth/logout")),
                new Rule(HttpMethod.GET, parser.parse("/api/v1/auth/registration-mode")),
                new Rule(HttpMethod.GET, parser.parse("/api/v1/auth/.well-known/jwks.json")),
                new Rule(HttpMethod.POST, parser.parse("/api/v1/waitlist")),
                new Rule(null, parser.parse("/actuator/health/**"))));
        if (actuatorInfoPublic) {
            built.add(new Rule(null, actuatorInfoPattern));
        }
        if (publicSurface.isPublicExploreEnabled()) {
            built.add(new Rule(HttpMethod.GET, parser.parse("/api/v1/public/explore/facilities")));
            built.add(new Rule(HttpMethod.GET, parser.parse("/api/v1/public/explore/facilities/{facilityId}")));
        }
        this.rules = List.copyOf(built);
    }

    public boolean isPublic(ServerHttpRequest request) {
        PathContainer path = request.getPath().pathWithinApplication();
        HttpMethod method = request.getMethod();
        if (rules.stream().anyMatch(rule -> rule.matches(method, path))) {
            return true;
        }
        // Public-surface disabled: still allow loopback identity probes used by
        // invite-production dark / public-staged smoke (127.0.0.1:8080).
        return !actuatorInfoPublic
                && actuatorInfoPattern.matches(path)
                && isLoopback(request);
    }

    static boolean isLoopback(ServerHttpRequest request) {
        InetSocketAddress remote = request.getRemoteAddress();
        if (remote == null) {
            return false;
        }
        InetAddress address = remote.getAddress();
        return address != null && address.isLoopbackAddress();
    }
}
