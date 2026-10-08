package com.parkio.auth.application;

import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

/**
 * The storage primitives the login throttle needs (CL-F15). The rules live in
 * {@link ThrottledLoginFailureTracker}; implementations keep counters, timed markers and plain sets,
 * so the same rule code runs on Redis in production and on an in-memory store with a virtual clock in
 * the tests and the policy simulation.
 */
public interface LoginThrottleStore {

    /** Increments the counter and (re)sets its lifetime to {@code window}; returns the new value. */
    long increment(String key, Duration window);

    /** The counter's value; zero when it is absent. */
    long read(String key);

    /** Sets a marker that expires after {@code duration}. */
    void hold(String key, Duration duration);

    /** Remaining lifetime of a marker; zero when it is absent or expired. */
    Duration remaining(String key);

    /**
     * Atomically: when any of the {@code markers} is still running, sets nothing and returns the longest
     * remaining time; otherwise sets every marker whose duration is positive and returns zero. However many
     * requests arrive at once, only one of them gets through each running window.
     */
    Duration tryHold(Map<String, Duration> markers);

    /** Adds {@code member} to the plain set {@code key} and (re)sets the set's lifetime to {@code keyTtl}. */
    void addToSet(String key, String member, Duration keyTtl);

    /** The members of the plain set {@code key}; empty when it is absent. */
    Set<String> setMembers(String key);

    /** The keys that start with {@code prefix} (an incremental scan; used by account erasure only). */
    Set<String> keysWithPrefix(String prefix);

    void delete(Collection<String> keys);
}
