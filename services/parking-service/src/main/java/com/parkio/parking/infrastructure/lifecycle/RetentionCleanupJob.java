package com.parkio.parking.infrastructure.lifecycle;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Bounded retention cleanup for this service's outbox and inbox transport rows and, when enabled,
 * for the per-user location logs ({@code parking_spot_search_logs}, {@code parking_spot_view_logs};
 * CL-F17 / PRIV-002). Location-log cleanup is off by default: enabling it is a release decision,
 * because the first run deletes production rows older than the retention period.
 */
@Component
@EnableScheduling
public class RetentionCleanupJob {

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final boolean outboxEnabled;
    private final boolean inboxEnabled;
    private final boolean locationLogsEnabled;
    private final Duration outboxRetention;
    private final Duration inboxRetention;
    private final Duration locationLogRetention;
    private final int batchSize;

    public RetentionCleanupJob(
            JdbcTemplate jdbc,
            Clock clock,
            @Value("${parkio.lifecycle.retention.outbox-enabled:true}") boolean outboxEnabled,
            @Value("${parkio.lifecycle.retention.inbox-enabled:true}") boolean inboxEnabled,
            @Value("${parkio.lifecycle.retention.location-logs-enabled:false}") boolean locationLogsEnabled,
            @Value("${parkio.lifecycle.retention.outbox-retention:P7D}") Duration outboxRetention,
            @Value("${parkio.lifecycle.retention.inbox-retention:P30D}") Duration inboxRetention,
            @Value("${parkio.lifecycle.retention.location-log-retention:P30D}") Duration locationLogRetention,
            @Value("${parkio.lifecycle.retention.batch-size:1000}") int batchSize) {
        if (locationLogRetention == null || locationLogRetention.isZero() || locationLogRetention.isNegative()) {
            throw new IllegalArgumentException(
                    "parkio.lifecycle.retention.location-log-retention must be a positive duration");
        }
        this.jdbc = jdbc;
        this.clock = clock;
        this.outboxEnabled = outboxEnabled;
        this.inboxEnabled = inboxEnabled;
        this.locationLogsEnabled = locationLogsEnabled;
        this.outboxRetention = outboxRetention;
        this.inboxRetention = inboxRetention;
        this.locationLogRetention = locationLogRetention;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${parkio.lifecycle.retention.fixed-delay-ms:3600000}")
    public void cleanup() {
        cleanupOutbox();
        cleanupInbox();
        cleanupSearchLogs();
        cleanupViewLogs();
    }

    public int cleanupOutbox() {
        if (!outboxEnabled) {
            return 0;
        }
        Instant cutoff = clock.instant().minus(outboxRetention);
        return jdbc.update("""
                DELETE FROM outbox_events
                WHERE id IN (
                    SELECT id FROM outbox_events
                    WHERE published = true AND created_at < ?
                    ORDER BY created_at
                    LIMIT ?
                )
                """, Timestamp.from(cutoff), batchSize);
    }

    public int cleanupInbox() {
        if (!inboxEnabled) {
            return 0;
        }
        Instant cutoff = clock.instant().minus(inboxRetention);
        return jdbc.update("""
                DELETE FROM inbox_events
                WHERE id IN (
                    SELECT id FROM inbox_events
                    WHERE processed_at < ?
                    ORDER BY processed_at
                    LIMIT ?
                )
                """, Timestamp.from(cutoff), batchSize);
    }

    /** Nearby-search logs (searcher id, coordinates, radius) older than the location-log retention. */
    public int cleanupSearchLogs() {
        if (!locationLogsEnabled) {
            return 0;
        }
        Instant cutoff = clock.instant().minus(locationLogRetention);
        return jdbc.update("""
                DELETE FROM parking_spot_search_logs
                WHERE id IN (
                    SELECT id FROM parking_spot_search_logs
                    WHERE created_at < ?
                    ORDER BY created_at
                    LIMIT ?
                )
                """, Timestamp.from(cutoff), batchSize);
    }

    /** Spot detail-view logs (viewer id, spot id) older than the location-log retention. */
    public int cleanupViewLogs() {
        if (!locationLogsEnabled) {
            return 0;
        }
        Instant cutoff = clock.instant().minus(locationLogRetention);
        return jdbc.update("""
                DELETE FROM parking_spot_view_logs
                WHERE id IN (
                    SELECT id FROM parking_spot_view_logs
                    WHERE created_at < ?
                    ORDER BY created_at
                    LIMIT ?
                )
                """, Timestamp.from(cutoff), batchSize);
    }
}
