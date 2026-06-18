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
 * (used to carve out the user-facing moderation endpoints and a user's own analytics
 * before the broader role-gated rules). A matched {@code REQUIRE_ROLES} rule demands
 * at least one of the listed roles. When no rule matches, the route is open to any
 * authenticated user (authentication having already been enforced upstream).
 *
 * <p>Two privilege tiers (ai-context/07, separation of duties): {@link #PRIVILEGED_ROLES}
 * ({@code MODERATOR}/{@code ADMIN}) for moderator surfaces (moderation queue, AI
 * findings), and {@link #ADMIN_ONLY} for platform analytics. Account-level actions
 * (suspend/restore/trust/score, appeal resolution) cannot be distinguished at the edge
 * by URL alone, so they are gated ADMIN-only inside moderation-service; the edge keeps
 * the coarse MODERATOR gate and downstream layers tighten it (defense in depth).
 */
@Component
public class RouteAuthorizationRules {

    /** Roles permitted to reach moderator surfaces (moderation queue, AI findings). */
    static final Set<String> PRIVILEGED_ROLES = Set.of("MODERATOR", "ADMIN");

    /** Admin-only surfaces (platform analytics, account-level operations). */
    static final Set<String> ADMIN_ONLY = Set.of("ADMIN");

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
                // Account-level actions (suspend/restore/trust/score, appeal resolution)
                // are further restricted to ADMIN inside moderation-service (the gateway
                // cannot inspect the request body), so MODERATOR is allowed through here.
                new Rule(null, parser.parse("/api/v1/moderation/**"), Type.REQUIRE_ROLES, PRIVILEGED_ROLES),
                // A user may read their own analytics; this carve-out must precede the
                // admin-only platform-analytics rule (first match wins). Ownership is
                // enforced in analytics-service.
                new Rule(HttpMethod.GET, parser.parse("/api/v1/analytics/users/**"), Type.AUTHENTICATED, Set.of()),
                // Platform analytics is admin-only reporting (separation of duties).
                new Rule(null, parser.parse("/api/v1/analytics/**"), Type.REQUIRE_ROLES, ADMIN_ONLY),
                // AI validation findings are advisory/moderation data, so both reads
                // and manual validation are limited to moderator/admin roles.
                new Rule(null, parser.parse("/api/v1/ai-validations/**"), Type.REQUIRE_ROLES,
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
