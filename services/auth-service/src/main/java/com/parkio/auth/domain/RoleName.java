package com.parkio.auth.domain;

/**
 * Authorization roles recognised by Parkio. Persisted and carried in the JWT
 * {@code roles} claim by these unprefixed names; the Spring Security
 * {@code ROLE_} prefix is applied only when building authorities (see the
 * authentication filter). Seeded in the {@code roles} table. New registrations
 * receive {@link #USER}.
 *
 * <p>{@link #SUPER_ADMIN} is the elevated privilege tier for role management and
 * other critical administrative operations. Existing ADMIN-only routes also
 * accept SUPER_ADMIN (see {@link com.parkio.auth.application.admin.AdminAuthority}).
 */
public enum RoleName {
    USER,
    MODERATOR,
    ADMIN,
    SUPER_ADMIN
}
