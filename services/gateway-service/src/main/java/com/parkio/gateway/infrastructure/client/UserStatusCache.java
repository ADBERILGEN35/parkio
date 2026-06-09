package com.parkio.gateway.infrastructure.client;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Tiny in-memory TTL cache of resolved account statuses, keyed by {@code authUserId}.
 * Keeps the per-request status check cheap without standing up Redis or Caffeine for
 * one short-lived value (ai-context/09 — don't overengineer).
 *
 * <p>Only <em>resolved (found)</em> statuses are cached; "not found" and "unavailable"
 * are never cached, so a freshly-provisioned account becomes active immediately and a
 * transient outage is re-checked on the next request. The TTL is small (default 30s),
 * so a status change (e.g. suspension) takes effect within the window. Cache loss is
 * safe — it is never the source of truth (ai-context/05).
 */
@Component
public class UserStatusCache {

    private record Entry(String status, Instant expiresAt) {
    }

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final Clock clock;
    private final Duration ttl;

    public UserStatusCache(Clock clock, UserStatusProperties properties) {
        this.clock = clock;
        this.ttl = properties.getCacheTtl();
    }

    /** Returns the cached status if present and unexpired; prunes it on expiry. */
    public Optional<String> get(String authUserId) {
        Entry entry = entries.get(authUserId);
        if (entry == null) {
            return Optional.empty();
        }
        if (!clock.instant().isBefore(entry.expiresAt())) {
            entries.remove(authUserId, entry);
            return Optional.empty();
        }
        return Optional.of(entry.status());
    }

    public void put(String authUserId, String status) {
        entries.put(authUserId, new Entry(status, clock.instant().plus(ttl)));
    }
}
