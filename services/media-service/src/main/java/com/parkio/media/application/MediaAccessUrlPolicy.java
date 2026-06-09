package com.parkio.media.application;

import java.time.Duration;

/**
 * Policy for generated media access URLs. {@code ttl} bounds how long a presigned
 * GET URL stays valid (configured via {@code parkio.media.access-url-ttl}, default
 * 5 minutes). Plain value type so the application layer stays free of Spring
 * configuration classes.
 */
public record MediaAccessUrlPolicy(Duration ttl) {

    public MediaAccessUrlPolicy {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("Access URL ttl must be positive");
        }
    }
}
