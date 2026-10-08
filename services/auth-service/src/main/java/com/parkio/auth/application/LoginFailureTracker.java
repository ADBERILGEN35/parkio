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
     * Admission of a login attempt, before its password is checked: atomically claims the waits that
     * apply to this (account, client) pair, so that however many attempts arrive at once, only one of them
     * is evaluated per running wait.
     *
     * @return {@link Duration#ZERO} when the attempt may be evaluated (the waits are now claimed for it),
     *     otherwise how long the pair must still wait. Includes the account-wide wait unless the client is
     *     known for the account.
     */
    Duration admit(String normalizedEmail, String clientKey, Instant now);

    /**
     * Read-only view of the same waits (claims nothing): how long this pair must still wait before an
     * attempt can be admitted, {@link Duration#ZERO} when it can.
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

    /**
     * Keeps (or makes) the client known for the account after a successful refresh-token rotation, so an
     * active session keeps its exemption from the account wait without a password login. Called only
     * after the presented refresh token was validated and rotated; never on a failed refresh.
     */
    void refreshKnownClient(String normalizedEmail, String clientKey);

    /** Account erasure: removes everything the throttle keeps for the account, known clients included. */
    void forgetAccount(String normalizedEmail);

    record LoginFailureOutcome(long pairFailures, long accountFailures, Duration delay, Instant retryAt) {
        public boolean throttled() {
            return !delay.isZero();
        }
    }
}
