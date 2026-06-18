package com.parkio.gateway.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * The epoch cache returns a resolved value within its TTL and drops it afterwards, so a
 * security event takes effect within at most the TTL window (the revocation lag).
 */
class SessionEpochCacheTest {

    private static final String USER_ID = "u1";

    @Test
    void returnsCachedEpochWithinTtl() {
        MutableClock clock = new MutableClock(Instant.parse("2026-06-09T00:00:00Z"));
        SessionEpochCache cache = new SessionEpochCache(clock, properties(Duration.ofSeconds(30)));
        cache.put(USER_ID, 4L);

        clock.advance(Duration.ofSeconds(29));

        assertThat(cache.get(USER_ID).isPresent()).isTrue();
        assertThat(cache.get(USER_ID).getAsLong()).isEqualTo(4L);
    }

    @Test
    void expiresCachedEpochAfterTtl() {
        MutableClock clock = new MutableClock(Instant.parse("2026-06-09T00:00:00Z"));
        SessionEpochCache cache = new SessionEpochCache(clock, properties(Duration.ofSeconds(30)));
        cache.put(USER_ID, 4L);

        clock.advance(Duration.ofSeconds(30));

        assertThat(cache.get(USER_ID).isEmpty()).isTrue();
    }

    private static SessionEpochProperties properties(Duration ttl) {
        SessionEpochProperties properties = new SessionEpochProperties();
        properties.setCacheTtl(ttl);
        return properties;
    }

    /** Minimal advanceable clock for deterministic TTL assertions. */
    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration delta) {
            this.now = this.now.plus(delta);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
