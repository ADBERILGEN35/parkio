package com.parkio.notification.application.port;

import java.time.Instant;
import java.util.UUID;

/**
 * Hands a push token over between accounts (H-05 / #546). A push token addresses one app install,
 * so it is active for at most one account: registering it for a user deactivates it for every
 * other user.
 */
public interface DeviceTokenHandover {

    /**
     * Deactivates every other user's active row for {@code token} and returns how many it
     * deactivated. Call it inside the registering transaction, before the caller's own row is
     * saved: it also serialises concurrent registrations of the same token until that transaction
     * ends, so the registration that commits last keeps the token.
     */
    int deactivateForOtherUsers(String token, UUID userId, Instant now);
}
