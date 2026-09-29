package com.parkio.auth.application.port;

import com.parkio.auth.domain.RegistrationInvite;
import java.time.Instant;

/** Persistence port for registration invites (stored hashed). */
public interface RegistrationInviteRepository {

    RegistrationInvite save(RegistrationInvite invite);

    /**
     * Atomically marks the invite consumed when it is still active. Returns {@code true}
     * when exactly one row was updated.
     */
    boolean consumeIfValid(String tokenHash, Instant now);
}
