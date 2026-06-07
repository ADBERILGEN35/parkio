package com.parkio.gateway.infrastructure.security;

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
 * <p>Only the auth bootstrap endpoints (register/login/refresh/logout) and the
 * gateway's own health/info actuator endpoints are public. Note: any other
 * {@code /api/v1/auth/**} endpoint is therefore protected by default.
 */
@Component
public class PublicEndpoints {

    private record Rule(HttpMethod method, PathPattern pattern) {

        boolean matches(HttpMethod requestMethod, PathContainer path) {
            return (method == null || method.equals(requestMethod)) && pattern.matches(path);
        }
    }

    private final List<Rule> rules;

    public PublicEndpoints() {
        PathPatternParser parser = PathPatternParser.defaultInstance;
        this.rules = List.of(
                new Rule(HttpMethod.POST, parser.parse("/api/v1/auth/register")),
                new Rule(HttpMethod.POST, parser.parse("/api/v1/auth/login")),
                new Rule(HttpMethod.POST, parser.parse("/api/v1/auth/refresh-token")),
                new Rule(HttpMethod.POST, parser.parse("/api/v1/auth/logout")),
                new Rule(null, parser.parse("/actuator/health/**")),
                new Rule(null, parser.parse("/actuator/info")));
    }

    public boolean isPublic(ServerHttpRequest request) {
        PathContainer path = request.getPath().pathWithinApplication();
        HttpMethod method = request.getMethod();
        return rules.stream().anyMatch(rule -> rule.matches(method, path));
    }
}
