package com.parkio.auth.application.admin;

import com.parkio.auth.domain.RoleName;
import com.parkio.auth.domain.exception.AuthErrorCode;
import com.parkio.auth.domain.exception.AuthException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Normalizes role names and evaluates admin privilege tiers. Admin endpoints pass the
 * roles of the verified access token ({@link #normalize}), never a request header.
 * {@link RoleName#SUPER_ADMIN} satisfies every check that requires {@link RoleName#ADMIN}.
 */
public final class AdminAuthority {

    private AdminAuthority() {
    }

    public static Set<String> parseRoles(String rolesHeader) {
        if (rolesHeader == null || rolesHeader.isBlank()) {
            return Set.of();
        }
        return normalize(Arrays.asList(rolesHeader.split(",")));
    }

    /** Trims and upper-cases role names, dropping blanks (e.g. the roles claim of a verified token). */
    public static Set<String> normalize(Collection<String> roles) {
        if (roles == null) {
            return Set.of();
        }
        return roles.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.toUpperCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    public static boolean isAdmin(Set<String> roles) {
        return roles.contains(RoleName.ADMIN.name()) || roles.contains(RoleName.SUPER_ADMIN.name());
    }

    public static boolean isSuperAdmin(Set<String> roles) {
        return roles.contains(RoleName.SUPER_ADMIN.name());
    }

    public static void requireAdmin(Set<String> roles) {
        if (!isAdmin(roles)) {
            throw new AuthException(AuthErrorCode.FORBIDDEN, "Admin role required.");
        }
    }

    public static void requireSuperAdmin(Set<String> roles) {
        if (!isSuperAdmin(roles)) {
            throw new AuthException(AuthErrorCode.FORBIDDEN, "SUPER_ADMIN role required.");
        }
    }
}
