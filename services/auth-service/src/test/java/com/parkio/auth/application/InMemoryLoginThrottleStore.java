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
 * SET with a lifetime, SADD + EXPIRE, sorted set scored by member expiry), driven by a test clock.
 * {@link #failing} simulates the store being unavailable.
 */
final class InMemoryLoginThrottleStore implements LoginThrottleStore {

    private final Supplier<Instant> now;
    private final Map<String, Long> counters = new HashMap<>();
    private final Set<String> markers = new HashSet<>();
    private final Map<String, Set<String>> sets = new HashMap<>();
    private final Map<String, Map<String, Instant>> timedSets = new HashMap<>();
    private final Map<String, Instant> expiresAt = new HashMap<>();
    boolean failing;

    InMemoryLoginThrottleStore(Clock clock) {
        this(clock::instant);
    }

    InMemoryLoginThrottleStore(Supplier<Instant> now) {
        this.now = now;
    }

    @Override
    public long increment(String key, Duration window) {
        live(key);
        long value = counters.merge(key, 1L, Long::sum);
        expiresAt.put(key, now.get().plus(window));
        return value;
    }

    @Override
    public void hold(String key, Duration duration) {
        live(key);
        markers.add(key);
        expiresAt.put(key, now.get().plus(duration));
    }

    @Override
    public Duration remaining(String key) {
        live(key);
        Instant at = expiresAt.get(key);
        return markers.contains(key) && at != null ? Duration.between(now.get(), at) : Duration.ZERO;
    }

    @Override
    public void addToSet(String key, String member, Duration keyTtl) {
        live(key);
        sets.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(member);
        expiresAt.put(key, now.get().plus(keyTtl));
    }

    @Override
    public Set<String> setMembers(String key) {
        live(key);
        return Set.copyOf(sets.getOrDefault(key, Set.of()));
    }

    @Override
    public void markTimed(String key, String member, Duration ttl) {
        live(key);
        Instant current = now.get();
        Map<String, Instant> members = timedSets.computeIfAbsent(key, k -> new HashMap<>());
        members.put(member, current.plus(ttl));
        members.values().removeIf(at -> !current.isBefore(at));
        expiresAt.put(key, current.plus(ttl));
    }

    @Override
    public boolean isTimedMember(String key, String member) {
        live(key);
        Instant at = timedSets.getOrDefault(key, Map.of()).get(member);
        return at != null && now.get().isBefore(at);
    }

    @Override
    public void delete(Collection<String> keys) {
        available();
        keys.forEach(this::drop);
    }

    long counter(String key) {
        live(key);
        return counters.getOrDefault(key, 0L);
    }

    /** Keys that hold something right now. */
    Set<String> keys() {
        Set<String> all = new TreeSet<>();
        all.addAll(counters.keySet());
        all.addAll(markers);
        all.addAll(sets.keySet());
        all.addAll(timedSets.keySet());
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
        timedSets.remove(key);
        expiresAt.remove(key);
    }
}
