package com.parkio.auth.application;

import java.time.Duration;
import java.time.Instant;

/** Capped exponential backoff for durable-recording worker retries. */
final class DurableErasureRetryBackoff {

    private DurableErasureRetryBackoff() {}

    static Instant nextAttemptAfter(
            Instant now, int attemptCountAfterFailure, Duration baseBackoff, Duration maxBackoff) {
        if (attemptCountAfterFailure <= 0) {
            return now;
        }
        long multiplier = 1L << Math.min(attemptCountAfterFailure - 1, 30);
        Duration delay = baseBackoff.multipliedBy(multiplier);
        if (delay.compareTo(maxBackoff) > 0) {
            delay = maxBackoff;
        }
        return now.plus(delay);
    }
}
