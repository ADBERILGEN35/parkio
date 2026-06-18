package com.parkio.auth.application.port;

import com.parkio.auth.domain.PasswordResetToken;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for password reset tokens (stored hashed). */
public interface PasswordResetRepository {

    PasswordResetToken save(PasswordResetToken token);

    Optional<PasswordResetToken> findByTokenHash(String tokenHash);

    int consumeActiveForUser(UUID userId, Instant consumedAt);
}
