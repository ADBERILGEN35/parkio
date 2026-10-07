package com.parkio.auth.application;

import java.time.Duration;

/**
 * Login throttling tiers (CL-F15). Failures are counted per (account, client) pair and per
 * account. A pair that keeps failing waits progressively longer before the next attempt is
 * evaluated; the account as a whole gets only a short, bounded delay once many clients fail
 * against it, so one client can slow the others down but can never lock them out.
 *
 * <p>A single client cannot reach the account cap on its own: its pair delays allow at most
 * 20 failures in the first hour and one per hour after that, which stays below
 * {@link #ACCOUNT_SOFT_CAP} inside {@link #ACCOUNT_WINDOW}. Reaching the cap needs a
 * distributed attacker, and the residual cost to the legitimate user is then
 * {@link #ACCOUNT_SOFT_DELAY} per attempt while that attack lasts (the challenge, P3 item 4,
 * is the product-level answer and is deliberately not part of this change).
 */
public final class LoginThrottlePolicy {

    /** Pair failure counters live this long after the last failure (unchanged from the lock era). */
    public static final Duration PAIR_WINDOW = Duration.ofHours(24);
    /** Account-wide failure counters live this long after the last failure. */
    public static final Duration ACCOUNT_WINDOW = Duration.ofHours(1);
    /** Account-wide failures (all clients) from which every client waits {@link #ACCOUNT_SOFT_DELAY}. */
    public static final long ACCOUNT_SOFT_CAP = 50;
    public static final Duration ACCOUNT_SOFT_DELAY = Duration.ofSeconds(10);

    private LoginThrottlePolicy() {
    }

    /** Delay the (account, client) pair must wait after its {@code pairFailures}-th failure. */
    public static Duration pairDelay(long pairFailures) {
        if (pairFailures >= 20) {
            return Duration.ofHours(1);
        }
        if (pairFailures >= 10) {
            return Duration.ofMinutes(5);
        }
        if (pairFailures >= 5) {
            return Duration.ofSeconds(30);
        }
        return Duration.ZERO;
    }

    /** Delay every client of the account must wait after its {@code accountFailures}-th failure. */
    public static Duration accountDelay(long accountFailures) {
        return accountFailures >= ACCOUNT_SOFT_CAP ? ACCOUNT_SOFT_DELAY : Duration.ZERO;
    }

    public static Duration max(Duration a, Duration b) {
        return a.compareTo(b) >= 0 ? a : b;
    }
}
