package com.parkio.auth.shared;

import java.util.List;
import java.util.UUID;

/**
 * Authenticated caller extracted from a validated JWT. Lives in {@code shared}
 * so the infrastructure filter that produces it and the presentation layer that
 * consumes it (via {@code @AuthenticationPrincipal}) can both depend on it
 * without crossing layer boundaries. Carries no secrets.
 */
public record AuthPrincipal(UUID userId, String email, List<String> roles, String status) {
}
