package com.parkio.auth.application.port;

import com.parkio.auth.application.admin.AdminUserSearchQuery;
import com.parkio.auth.application.result.PageResult;
import com.parkio.auth.domain.AuthUser;
import com.parkio.auth.domain.AuthUserStatus;
import com.parkio.auth.domain.RoleName;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence port for {@link AuthUser}. Implemented by an infrastructure
 * adapter; the application depends only on this interface.
 */
public interface AuthUserRepository {

    AuthUser save(AuthUser user);

    Optional<AuthUser> findById(UUID id);

    Optional<AuthUser> findByEmail(String email);

    Optional<AuthUser> findByEmailVerificationTokenHash(String tokenHash);

    boolean existsByEmail(String email);

    PageResult<AuthUser> search(AdminUserSearchQuery query);

    long count();

    long countByStatus(AuthUserStatus status);

    long countVerified();

    long countUnverified();

    long countCreatedSince(Instant since);

    long countByRole(RoleName roleName);
}
