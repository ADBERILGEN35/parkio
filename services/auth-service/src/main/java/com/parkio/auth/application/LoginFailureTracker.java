package com.parkio.auth.application;

import java.time.Duration;
import java.time.Instant;

/**
 * Shared failed-login tracker (CL-F15). Failures are keyed by (normalized e-mail, client key)
 * and by e-mail; the delays come from {@link LoginThrottlePolicy}. The client key is the
 * gateway-resolved client IP, or {@link #UNKNOWN_CLIENT} when the request carried none; all
 * unknown clients of an account share one pair.
 */
public interface LoginFailureTracker {

    String UNKNOWN_CLIENT = "unknown";

    /**
     * @return how long this (account, client) pair must still wait before an attempt is
     *     evaluated, {@link Duration#ZERO} when it may proceed. Includes the account-wide
     *     soft delay.
     */
    Duration retryAfter(String normalizedEmail, String clientKey, Instant now);

    LoginFailureOutcome recordFailure(String normalizedEmail, String clientKey, Instant now);

    /** A successful login: clears this client's failures and the account-wide counter and delay. */
    void clearAfterSuccess(String normalizedEmail, String clientKey);

    /** A password reset: clears every client's failures and delays for the account. */
    void clearAccount(String normalizedEmail);

    record LoginFailureOutcome(long pairFailures, long accountFailures, Duration delay, Instant retryAt) {
        public boolean throttled() {
            return !delay.isZero();
        }
    }
}
