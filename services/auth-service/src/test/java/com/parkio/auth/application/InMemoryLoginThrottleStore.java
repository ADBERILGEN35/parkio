package com.parkio.auth.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;

/**
 * In-memory {@link LoginThrottleStore} with the Redis semantics the throttle relies on (INCR + EXPIRE,
 * SET with a lifetime, SADD + EXPIRE, an atomic check-and-set of markers), driven by a test clock. Every
 * operation is synchronized, so concurrency tests see the same atomicity as the Redis script.
 * {@link #failing} simulates the store being unavailable.
 */
final class InMemoryLoginThrottleStore implements LoginThrottleStore {

    private final Supplier<Instant> now;
    private final Map<String, Long> counters = new HashMap<>();
    private final Set<String> markers = new HashSet<>();
    private final Map<String, Set<String>> sets = new HashMap<>();
    private final Map<String, Instant> expiresAt = new HashMap<>();
    volatile boolean failing;

    InMemoryLoginThrottleStore(Clock clock) {
        this(clock::instant);
    }

    InMemoryLoginThrottleStore(Supplier<Instant> now) {
        this.now = now;
    }

    @Override
    public synchronized long increment(String key, Duration window) {
        live(key);
        long value = counters.merge(key, 1L, Long::sum);
        expiresAt.put(key, now.get().plus(window));
        return value;
    }

    @Override
    public synchronized long read(String key) {
        live(key);
        return counters.getOrDefault(key, 0L);
    }

    @Override
    public synchronized void hold(String key, Duration duration) {
        live(key);
        markers.add(key);
        expiresAt.put(key, now.get().plus(duration));
    }

    @Override
    public synchronized Duration remaining(String key) {
        live(key);
        Instant at = expiresAt.get(key);
        return markers.contains(key) && at != null ? Duration.between(now.get(), at) : Duration.ZERO;
    }

    @Override
    public synchronized Duration tryHold(Map<String, Duration> waits) {
        Duration longest = Duration.ZERO;
        for (String key : waits.keySet()) {
            Duration left = remaining(key);
            if (left.compareTo(longest) > 0) {
                longest = left;
            }
        }
        if (!longest.isZero()) {
            return longest;
        }
        waits.forEach((key, duration) -> {
            if (duration.compareTo(Duration.ZERO) > 0) {
                hold(key, duration);
            }
        });
        return Duration.ZERO;
    }

    @Override
    public synchronized void addToSet(String key, String member, Duration keyTtl) {
        live(key);
        sets.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(member);
        expiresAt.put(key, now.get().plus(keyTtl));
    }

    @Override
    public synchronized Set<String> setMembers(String key) {
        live(key);
        return Set.copyOf(sets.getOrDefault(key, Set.of()));
    }

    @Override
    public synchronized Set<String> keysWithPrefix(String prefix) {
        Set<String> found = new TreeSet<>();
        for (String key : keys()) {
            if (key.startsWith(prefix)) {
                found.add(key);
            }
        }
        return found;
    }

    @Override
    public synchronized void delete(Collection<String> keys) {
        available();
        keys.forEach(this::drop);
    }

    synchronized long counter(String key) {
        return read(key);
    }

    /** Keys that hold something right now. */
    synchronized Set<String> keys() {
        available();
        Set<String> all = new TreeSet<>();
        all.addAll(counters.keySet());
        all.addAll(markers);
        all.addAll(sets.keySet());
        all.removeIf(key -> {
            Instant at = expiresAt.get(key);
            return at != null && !now.get().isBefore(at);
        });
        return all;
    }

    private void live(String key) {
        available();
        Instant at = expiresAt.get(key);
        if (at != null && !now.get().isBefore(at)) {
            drop(key);
        }
    }

    private void available() {
        if (failing) {
            throw new IllegalStateException("throttle store unavailable");
        }
    }

    private void drop(String key) {
        counters.remove(key);
        markers.remove(key);
        sets.remove(key);
        expiresAt.remove(key);
    }
}
