package com.parkio.auth.application;

import java.time.Instant;

/** Shared failed-login tracker used to apply per-account brute-force protection. */
public interface LoginFailureTracker {

    boolean isLocked(String normalizedEmail, Instant now);

    LoginFailureOutcome recordFailure(String normalizedEmail, Instant now);

    void reset(String normalizedEmail);

    record LoginFailureOutcome(long failureCount, boolean lockoutApplied, Instant lockedUntil) {
    }
}
