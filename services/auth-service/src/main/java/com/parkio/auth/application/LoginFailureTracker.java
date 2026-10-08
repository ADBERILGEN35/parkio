package com.parkio.auth.application;

import java.time.Duration;
import java.time.Instant;

/**
 * Failed-login tracker (CL-F15). Failures are keyed by (normalized e-mail, client key) and by e-mail;
 * the rules are {@link LoginThrottlePolicy}. The client key comes from {@link LoginClientKeys} (an IPv4
 * address or an IPv6 /64), or is {@link #UNKNOWN_CLIENT} when the request carried none: all unknown
 * clients of an account share one pair, and that pair is never a known client.
 */
public interface LoginFailureTracker {

    String UNKNOWN_CLIENT = "unknown";

    /**
     * @return how long this (account, client) pair must still wait before an attempt is evaluated,
     *     {@link Duration#ZERO} when it may proceed. Includes the account-wide delay unless the client is
     *     known for the account.
     */
    Duration retryAfter(String normalizedEmail, String clientKey, Instant now);

    LoginFailureOutcome recordFailure(String normalizedEmail, String clientKey, Instant now);

    /** A successful login: clears this client's pair and marks the client known for the account. */
    void clearAfterSuccess(String normalizedEmail, String clientKey);

    /**
     * A completed password reset: clears every client's pair for the account and marks the resetting
     * client known ({@code resettingClientKey} may be null or {@link #UNKNOWN_CLIENT}: nothing is marked).
     * The account-wide counter stays.
     */
    void clearAfterPasswordReset(String normalizedEmail, String resettingClientKey);

    /** Account erasure: removes everything the throttle keeps for the account, known clients included. */
    void forgetAccount(String normalizedEmail);

    record LoginFailureOutcome(long pairFailures, long accountFailures, Duration delay, Instant retryAt) {
        public boolean throttled() {
            return !delay.isZero();
        }
    }
}
