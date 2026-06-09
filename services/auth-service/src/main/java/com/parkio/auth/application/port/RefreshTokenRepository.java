package com.parkio.auth.application.port;

import com.parkio.auth.domain.RefreshToken;
import com.parkio.auth.domain.RefreshTokenRevocationReason;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for refresh tokens (stored hashed). */
public interface RefreshTokenRepository {

    RefreshToken save(RefreshToken token);

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    int revokeActiveFamily(UUID tokenFamilyId, RefreshTokenRevocationReason reason, Instant revokedAt);

    /** Revokes every active token across all of the user's families (e.g. on suspension). */
    int revokeAllActiveForUser(UUID userId, RefreshTokenRevocationReason reason, Instant revokedAt);
}
