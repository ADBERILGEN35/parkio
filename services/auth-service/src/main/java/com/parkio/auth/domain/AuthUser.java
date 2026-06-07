package com.parkio.auth.domain;

import com.parkio.auth.domain.exception.AuthErrorCode;
import com.parkio.auth.domain.exception.AuthException;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Aggregate root for an authentication account. Holds the credentials
 * (as a hash), status and roles. Pure domain — no framework dependencies.
 *
 * <p>Email is the sole login identifier; auth-service holds no profile data
 * (ai-context/03).
 */
public final class AuthUser {

    private final UUID id;
    private final String email;
    private String passwordHash;
    private AuthUserStatus status;
    private final Set<Role> roles;
    private final Instant createdAt;
    private final Long version;

    public AuthUser(UUID id,
                    String email,
                    String passwordHash,
                    AuthUserStatus status,
                    Set<Role> roles,
                    Instant createdAt,
                    Long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.email = Objects.requireNonNull(email, "email");
        this.passwordHash = Objects.requireNonNull(passwordHash, "passwordHash");
        this.status = Objects.requireNonNull(status, "status");
        this.roles = new LinkedHashSet<>(Objects.requireNonNull(roles, "roles"));
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.version = version;
    }

    /**
     * Registers a new, active account with the given (already hashed) password
     * and initial roles. Email is normalised to lowercase.
     */
    public static AuthUser register(String email,
                                    String passwordHash,
                                    Set<Role> initialRoles,
                                    Instant now) {
        return new AuthUser(
                UUID.randomUUID(),
                normalizeEmail(email),
                passwordHash,
                AuthUserStatus.ACTIVE,
                initialRoles,
                now,
                null);
    }

    /** Guards authentication: the account must be allowed to authenticate. */
    public void ensureCanAuthenticate() {
        if (!status.canAuthenticate()) {
            throw new AuthException(AuthErrorCode.USER_NOT_ACTIVE);
        }
    }

    public static String normalizeEmail(String email) {
        return email.trim().toLowerCase();
    }

    public UUID id() {
        return id;
    }

    public String email() {
        return email;
    }

    /** The BCrypt hash. Never exposed beyond the persistence boundary. */
    public String passwordHash() {
        return passwordHash;
    }

    public AuthUserStatus status() {
        return status;
    }

    public Set<Role> roles() {
        return Collections.unmodifiableSet(roles);
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Long version() {
        return version;
    }
}
