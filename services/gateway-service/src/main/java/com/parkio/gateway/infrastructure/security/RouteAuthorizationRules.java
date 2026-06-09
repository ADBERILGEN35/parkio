package com.parkio.gateway.infrastructure.security;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * Edge role-based authorization matrix. Authentication (a valid JWT) is enforced
 * earlier by {@link AuthenticationGlobalFilter}; these rules add coarse-grained,
 * route-level <em>role</em> gating at the edge so privileged surfaces are never
 * reachable by an ordinary {@code USER}, independent of each downstream service's
 * own per-endpoint checks (defense in depth, ai-context/07).
 *
 * <p>Rules are evaluated in order; the <strong>first match wins</strong>. A matched
 * {@code AUTHENTICATED} rule short-circuits to "any authenticated user is allowed"
 * (used to carve out the user-facing moderation endpoints before the broad
 * moderator-only rule). A matched {@code REQUIRE_ROLES} rule demands at least one of
 * the listed roles. When no rule matches, the route is open to any authenticated
 * user (authentication having already been enforced upstream).
 */
@Component
public class RouteAuthorizationRules {

    /** Roles permitted to reach privileged moderation/analytics/manual-AI surfaces. */
    static final Set<String> PRIVILEGED_ROLES = Set.of("MODERATOR", "ADMIN");

    private enum Type {
        /** Any authenticated user may pass (no role restriction). */
        AUTHENTICATED,
        /** The caller must hold at least one of {@link Rule#roles}. */
        REQUIRE_ROLES
    }

    private record Rule(HttpMethod method, PathPattern pattern, Type type, Set<String> roles) {

        boolean matches(HttpMethod requestMethod, PathContainer path) {
            return (method == null || method.equals(requestMethod)) && pattern.matches(path);
        }
    }

    private final List<Rule> rules;

    public RouteAuthorizationRules() {
        PathPatternParser parser = PathPatternParser.defaultInstance;
        this.rules = List.of(
                // User-facing moderation endpoints: authenticated USER is enough. These
                // must precede the broad moderation rule below (first match wins).
                new Rule(HttpMethod.POST, parser.parse("/api/v1/moderation/reports"), Type.AUTHENTICATED, Set.of()),
                new Rule(HttpMethod.GET, parser.parse("/api/v1/moderation/reports/me"), Type.AUTHENTICATED, Set.of()),
                new Rule(HttpMethod.POST, parser.parse("/api/v1/moderation/appeals"), Type.AUTHENTICATED, Set.of()),
                // Everything else under moderation (cases, appeal resolution, assignment).
                new Rule(null, parser.parse("/api/v1/moderation/**"), Type.REQUIRE_ROLES, PRIVILEGED_ROLES),
                // Analytics is staff-only reporting.
                new Rule(null, parser.parse("/api/v1/analytics/**"), Type.REQUIRE_ROLES, PRIVILEGED_ROLES),
                // Manually forcing an AI validation is a moderator/admin action; the
                // read-only ai-validation lookups fall through (authenticated user only).
                new Rule(HttpMethod.POST, parser.parse("/api/v1/ai-validations/manual"), Type.REQUIRE_ROLES,
                        PRIVILEGED_ROLES));
    }

    /**
     * Resolves the roles required for a request.
     *
     * @return {@code Optional.empty()} when any authenticated user may proceed (no
     *         matching rule, or a matched {@code AUTHENTICATED} rule); otherwise the
     *         set of roles of which the caller must hold at least one.
     */
    public Optional<Set<String>> requiredRoles(HttpMethod method, PathContainer path) {
        for (Rule rule : rules) {
            if (rule.matches(method, path)) {
                return rule.type() == Type.REQUIRE_ROLES ? Optional.of(rule.roles()) : Optional.empty();
            }
        }
        return Optional.empty();
    }
}
