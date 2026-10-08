package com.parkio.auth.application;

import java.time.Duration;
import java.util.Collection;
import java.util.Set;

/**
 * The storage primitives the login throttle needs (CL-F15). The rules live in
 * {@link ThrottledLoginFailureTracker}; implementations only keep counters, timed markers, plain sets
 * and timed sets, so the same rule code runs on Redis in production and on an in-memory store with a
 * virtual clock in the tests and the policy simulation.
 */
public interface LoginThrottleStore {

    /** Increments the counter and (re)sets its lifetime to {@code window}; returns the new value. */
    long increment(String key, Duration window);

    /** Sets a marker that expires after {@code duration}. */
    void hold(String key, Duration duration);

    /** Remaining lifetime of a marker; zero when it is absent or expired. */
    Duration remaining(String key);

    /** Adds {@code member} to the plain set {@code key} and (re)sets the set's lifetime to {@code keyTtl}. */
    void addToSet(String key, String member, Duration keyTtl);

    /** The members of the plain set {@code key}; empty when it is absent. */
    Set<String> setMembers(String key);

    /** Adds or refreshes {@code member} in the timed set {@code key}; the member expires after {@code ttl}. */
    void markTimed(String key, String member, Duration ttl);

    /** True when {@code member} is in the timed set {@code key} and has not expired. */
    boolean isTimedMember(String key, String member);

    void delete(Collection<String> keys);
}
