package com.parkio.notification.infrastructure.lifecycle;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
public class RetentionCleanupJob {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final boolean outboxEnabled;
    private final boolean inboxEnabled;
    private final Duration outboxRetention;
    private final Duration inboxRetention;
    private final int batchSize;

    public RetentionCleanupJob(
            JdbcTemplate jdbc, Clock clock,
            @Value("${parkio.lifecycle.retention.outbox-enabled:true}") boolean outboxEnabled,
            @Value("${parkio.lifecycle.retention.inbox-enabled:true}") boolean inboxEnabled,
            @Value("${parkio.lifecycle.retention.outbox-retention:P7D}") Duration outboxRetention,
            @Value("${parkio.lifecycle.retention.inbox-retention:P30D}") Duration inboxRetention,
            @Value("${parkio.lifecycle.retention.batch-size:1000}") int batchSize) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.outboxEnabled = outboxEnabled;
        this.inboxEnabled = inboxEnabled;
        this.outboxRetention = outboxRetention;
        this.inboxRetention = inboxRetention;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${parkio.lifecycle.retention.fixed-delay-ms:3600000}")
    public void cleanup() {
        cleanupOutbox();
        cleanupInbox();
    }

    public int cleanupOutbox() {
        if (!outboxEnabled) return 0;
        Instant cutoff = clock.instant().minus(outboxRetention);
        return jdbc.update("""
                DELETE FROM outbox_events WHERE id IN (
                    SELECT id FROM outbox_events
                    WHERE published = true AND created_at < ?
                    ORDER BY created_at LIMIT ?
                )
                """, Timestamp.from(cutoff), batchSize);
    }

    public int cleanupInbox() {
        if (!inboxEnabled) return 0;
        Instant cutoff = clock.instant().minus(inboxRetention);
        return jdbc.update("""
                DELETE FROM inbox_events WHERE id IN (
                    SELECT id FROM inbox_events
                    WHERE processed_at < ?
                    ORDER BY processed_at LIMIT ?
                )
                """, Timestamp.from(cutoff), batchSize);
    }
}
