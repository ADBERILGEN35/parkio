package com.parkio.analytics.infrastructure.metrics;

import com.parkio.analytics.infrastructure.persistence.jpa.InboxEventJpaRepository;
import com.parkio.analytics.infrastructure.persistence.jpa.OutboxEventJpaRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * Outbox/inbox gauges exported at {@code /actuator/prometheus}. analytics-service consumes
 * events and, since U05, relays its participant erasure ACKs through the outbox; a growing
 * {@code parkio.outbox.unpublished.count} means those ACKs are not reaching auth, and a
 * dead-lettered row ({@code parkio.outbox.deadlettered.count}) holds up an account erasure
 * until an operator redrives it.
 *
 * <p>Each gauge issues one cheap query per scrape (15s locally), never on the request path.
 */
@Component
public class MessagingMetrics {

    private final OutboxEventJpaRepository outbox;
    private final InboxEventJpaRepository inbox;

    public MessagingMetrics(OutboxEventJpaRepository outbox, InboxEventJpaRepository inbox, MeterRegistry registry) {
        this.outbox = outbox;
        this.inbox = inbox;
        Gauge.builder("parkio.outbox.unpublished.count", this, m -> m.outbox.countByPublishedFalseAndDeadLetteredFalse())
                .description("Outbox rows not yet published to Kafka")
                .register(registry);
        Gauge.builder("parkio.outbox.oldest.unpublished.age.seconds", this, MessagingMetrics::oldestUnpublishedAgeSeconds)
                .description("Age of the oldest unpublished outbox row (0 when the backlog is empty)")
                .register(registry);
        Gauge.builder("parkio.outbox.deadlettered.count", this, m -> m.outbox.countByDeadLetteredTrue())
                .description("Open dead-lettered outbox rows awaiting operator inspection/redrive")
                .register(registry);
        Gauge.builder("parkio.outbox.deadlettered.acknowledged.count", this,
                        m -> m.outbox.countAcknowledgedDeadletters())
                .description("Dead-lettered outbox rows intentionally acknowledged/suppressed by an operator")
                .register(registry);
        Gauge.builder("parkio.outbox.deadlettered.oldest.age.seconds", this,
                        MessagingMetrics::oldestOpenDeadletterAgeSeconds)
                .description("Age of the oldest open dead-lettered outbox row (0 when none are open)")
                .baseUnit("seconds")
                .register(registry);
        Gauge.builder("parkio.outbox.recovery.retry.count", this,
                        m -> m.outbox.countRecoveryAuditByAction("RETRY"))
                .description("Outbox dead-letter retry actions recorded by operators")
                .register(registry);
        Gauge.builder("parkio.outbox.recovery.acknowledged.count", this,
                        m -> m.outbox.countRecoveryAuditByAction("ACKNOWLEDGE"))
                .description("Outbox dead-letter acknowledge actions recorded by operators")
                .register(registry);
        Gauge.builder("parkio.inbox.processed.count", this, m -> m.inbox.count())
                .description("Processed inbox rows currently retained for consumer dedup")
                .register(registry);
    }

    private double oldestUnpublishedAgeSeconds() {
        return ageSeconds(outbox.findOldestUnpublishedCreatedAt());
    }

    private double oldestOpenDeadletterAgeSeconds() {
        return ageSeconds(outbox.findOldestOpenDeadletterCreatedAt());
    }

    private static double ageSeconds(Instant oldest) {
        return oldest == null ? 0 : Math.max(0, Duration.between(oldest, Instant.now()).toSeconds());
    }
}
