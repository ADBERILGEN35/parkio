package com.parkio.user.infrastructure.metrics;

import com.parkio.user.infrastructure.persistence.jpa.InboxEventJpaRepository;
import com.parkio.user.infrastructure.persistence.jpa.OutboxEventJpaRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * Outbox/inbox backlog gauges exported at {@code /actuator/prometheus}.
 *
 * <p>Each gauge issues one cheap COUNT/MIN query per scrape (15s locally), never on the
 * request path. Alert on a growing {@code parkio.outbox.unpublished.count} or
 * {@code parkio.outbox.oldest.unpublished.age.seconds}: it means the outbox relay is
 * not draining to Kafka.
 */
@Component
public class MessagingMetrics {

    private final OutboxEventJpaRepository outbox;
    private final InboxEventJpaRepository inbox;
    private final Clock clock;

    public MessagingMetrics(OutboxEventJpaRepository outbox, InboxEventJpaRepository inbox,
                            Clock clock, MeterRegistry registry) {
        this.outbox = outbox;
        this.inbox = inbox;
        this.clock = clock;
        Gauge.builder("parkio.outbox.unpublished.count", this, m -> m.outbox.countByPublishedFalse())
                .description("Outbox rows not yet published to Kafka")
                .register(registry);
        Gauge.builder("parkio.outbox.oldest.unpublished.age.seconds", this,
                        MessagingMetrics::oldestUnpublishedAgeSeconds)
                .description("Age of the oldest unpublished outbox row (0 when the backlog is empty)")
                .baseUnit("seconds")
                .register(registry);
        Gauge.builder("parkio.inbox.processed.count", this, m -> m.inbox.count())
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
}
