package com.parkio.auth.domain;

/**
 * Authorization roles recognised by Parkio. Persisted and carried in the JWT
 * {@code roles} claim by these unprefixed names; the Spring Security
 * {@code ROLE_} prefix is applied only when building authorities (see the
 * authentication filter). Seeded in the {@code roles} table. New registrations
 * receive {@link #USER}.
 */
public enum RoleName {
    USER,
    MODERATOR,
    ADMIN
}
