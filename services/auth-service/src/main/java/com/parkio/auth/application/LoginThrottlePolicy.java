package com.parkio.auth.application;

import java.time.Duration;

/**
 * Login throttling policy v2 (CL-F15 follow-up; owner decision 2026-10-08 item 6, PROPOSED).
 *
 * <ul>
 *   <li><b>Per client.</b> Failures are counted per (account, client) pair. The client is the
 *       gateway-resolved IPv4 address or the IPv6 /64 network ({@link LoginClientKeys}), so rotating
 *       addresses inside one /64 is one client. A pair waits 30 s from its 5th failure, 5 min from its
 *       10th and 1 h from its 20th; its counter lives 24 h after the last failure.</li>
 *   <li><b>Per account, for clients that are not known.</b> Failures from all clients of an account are
 *       counted too (the counter lives 1 h after the last failure). From the 50th, each further failure
 *       makes the account's unknown clients wait 10 s; from the 100th, 60 s; from the 200th, 5 min. A
 *       distributed attack on one account therefore gets about 150 guesses in its first hour and 12 per
 *       hour once it passes 200, however many addresses it has ({@code LoginThrottleSimulationTest}).</li>
 *   <li><b>Known clients</b> logged into the account, or completed its password reset, within the last
 *       30 days. They are exempt from the account wait, so a distributed attacker cannot keep the owner
 *       out of a network the owner used before; their own pair tiers still apply. (NIST SP 800-63B
 *       5.2.2 lists this allowlist of previously authenticated addresses among the measures against
 *       lockout.)</li>
 *   <li><b>Recovery.</b> Completing a password reset clears every pair of the account and marks the
 *       resetting client known, so the owner can log in from that client at once, also during an
 *       attack. The account counter stays, so neither a reset nor a successful login gives a
 *       distributed attacker a fresh budget.</li>
 * </ul>
 */
public final class LoginThrottlePolicy {

    /** Pair failure counters live this long after the last failure. */
    public static final Duration PAIR_WINDOW = Duration.ofHours(24);
    /** Account-wide failure counters live this long after the last failure. */
    public static final Duration ACCOUNT_WINDOW = Duration.ofHours(1);
    /** Account failures from which unknown clients wait {@link #ACCOUNT_FIRST_DELAY} per further failure. */
    public static final long ACCOUNT_FIRST_CAP = 50;
    public static final Duration ACCOUNT_FIRST_DELAY = Duration.ofSeconds(10);
    public static final long ACCOUNT_SECOND_CAP = 100;
    public static final Duration ACCOUNT_SECOND_DELAY = Duration.ofSeconds(60);
    public static final long ACCOUNT_THIRD_CAP = 200;
    public static final Duration ACCOUNT_THIRD_DELAY = Duration.ofMinutes(5);
    /** How long a successful login or a completed reset keeps a client known for the account. */
    public static final Duration KNOWN_CLIENT_TTL = Duration.ofDays(30);
    /** IPv6 clients are keyed by their network of this prefix length. */
    public static final int IPV6_CLIENT_PREFIX_BITS = 64;

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

    /** Delay the account's unknown clients must wait after its {@code accountFailures}-th failure. */
    public static Duration accountDelay(long accountFailures) {
        if (accountFailures >= ACCOUNT_THIRD_CAP) {
            return ACCOUNT_THIRD_DELAY;
        }
        if (accountFailures >= ACCOUNT_SECOND_CAP) {
            return ACCOUNT_SECOND_DELAY;
        }
        if (accountFailures >= ACCOUNT_FIRST_CAP) {
            return ACCOUNT_FIRST_DELAY;
        }
        return Duration.ZERO;
    }

    public static Duration max(Duration a, Duration b) {
        return a.compareTo(b) >= 0 ? a : b;
    }
}
