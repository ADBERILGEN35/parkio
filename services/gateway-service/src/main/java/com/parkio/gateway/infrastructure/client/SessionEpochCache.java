package com.parkio.gateway.infrastructure.client;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Tiny in-memory TTL cache of resolved session epochs, keyed by {@code userId}. Keeps
 * the per-request access-token revocation check cheap without standing up Redis or
 * Caffeine for one short-lived value (ai-context/09 — don't overengineer).
 *
 * <p>Only resolved epochs are cached; "unavailable" is never cached, so a transient
 * auth-service outage is re-checked on the next request. The TTL is small (default 30s),
 * so a security event (logout-all, reuse detection, suspension) that bumps the epoch
 * takes effect within the window — this TTL is the access-token revocation lag. Cache
 * loss is safe — it is never the source of truth (ai-context/05).
 */
@Component
public class SessionEpochCache {

    private record Entry(long epoch, Instant expiresAt) {
    }

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final Clock clock;
    private final Duration ttl;

    public SessionEpochCache(Clock clock, SessionEpochProperties properties) {
        this.clock = clock;
        this.ttl = properties.getCacheTtl();
    }

    /** Returns the cached epoch if present and unexpired; prunes it on expiry. */
    public OptionalLong get(String userId) {
        Entry entry = entries.get(userId);
        if (entry == null) {
            return OptionalLong.empty();
        }
        if (!clock.instant().isBefore(entry.expiresAt())) {
            entries.remove(userId, entry);
            return OptionalLong.empty();
        }
        return OptionalLong.of(entry.epoch());
    }

    public void put(String userId, long epoch) {
        entries.put(userId, new Entry(epoch, clock.instant().plus(ttl)));
    }
}
