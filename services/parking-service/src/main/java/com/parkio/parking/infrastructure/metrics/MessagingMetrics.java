package com.parkio.parking.infrastructure.metrics;

import com.parkio.parking.infrastructure.persistence.jpa.OutboxEventJpaRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Outbox/inbox backlog gauges exported at {@code /actuator/prometheus}.
 *
 * <p>Each gauge issues one cheap COUNT/MIN query per scrape (15s locally), never on the
 * request path. Alert on a growing {@code parkio.outbox.unpublished.count} or
 * {@code parkio.outbox.oldest.unpublished.age.seconds}: it means the outbox relay is
 * not draining to Kafka.
 *
 * <p>parking-service has no JPA mapping for {@code inbox_events} (the Kafka consumer
 * dedups with a native INSERT), so the inbox gauge counts via {@link JdbcTemplate}.
 */
@Component
public class MessagingMetrics {

    private final OutboxEventJpaRepository outbox;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public MessagingMetrics(OutboxEventJpaRepository outbox, JdbcTemplate jdbc,
                            Clock clock, MeterRegistry registry) {
        this.outbox = outbox;
        this.jdbc = jdbc;
        this.clock = clock;
        Gauge.builder("parkio.outbox.unpublished.count", this,
                        m -> m.outbox.countByPublishedFalseAndDeadLetteredFalse())
                .description("Relayable outbox rows not yet published to Kafka (excludes dead-lettered)")
                .register(registry);
        Gauge.builder("parkio.outbox.deadlettered.count", this, m -> m.outbox.countByDeadLetteredTrue())
                .description("Dead-lettered (poison) outbox rows retained for inspection/redrive")
                .register(registry);
        Gauge.builder("parkio.outbox.oldest.unpublished.age.seconds", this,
                        MessagingMetrics::oldestUnpublishedAgeSeconds)
                .description("Age of the oldest relayable outbox row (0 when the backlog is empty)")
                .baseUnit("seconds")
                .register(registry);
        Gauge.builder("parkio.inbox.processed.count", this, MessagingMetrics::inboxProcessedCount)
                .description("Processed inbox rows currently retained for consumer dedup")
                .register(registry);
    }

    double oldestUnpublishedAgeSeconds() {
        Instant oldest = outbox.findOldestUnpublishedCreatedAt();
        if (oldest == null) {
            return 0.0;
        }
        return Math.max(0.0, Duration.between(oldest, clock.instant()).toMillis() / 1000.0);
    }

    double inboxProcessedCount() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM inbox_events", Long.class);
        return count == null ? 0.0 : count;
    }
}
