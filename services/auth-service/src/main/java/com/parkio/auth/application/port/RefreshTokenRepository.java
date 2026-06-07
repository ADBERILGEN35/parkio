package com.parkio.auth.application.port;

import com.parkio.auth.domain.RefreshToken;
import java.util.Optional;

/** Persistence port for refresh tokens (stored hashed). */
public interface RefreshTokenRepository {

    RefreshToken save(RefreshToken token);

    Optional<RefreshToken> findByTokenHash(String tokenHash);
}
