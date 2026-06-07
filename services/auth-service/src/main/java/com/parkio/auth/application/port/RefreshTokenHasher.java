package com.parkio.auth.application.port;

/**
 * Port for hashing opaque refresh tokens. Refresh tokens are high-entropy
 * random values, so a fast deterministic hash (SHA-256) is appropriate and
 * allows lookup by hash.
 */
public interface RefreshTokenHasher {

    String hash(String rawToken);
}
