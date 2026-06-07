package com.parkio.auth.application.port;

import com.parkio.auth.domain.AuthUser;
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

    boolean existsByEmail(String email);
}
