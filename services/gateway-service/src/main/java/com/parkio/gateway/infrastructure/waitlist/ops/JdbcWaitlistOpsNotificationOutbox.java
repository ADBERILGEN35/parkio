package com.parkio.gateway.infrastructure.waitlist.ops;

import com.parkio.gateway.application.waitlist.WaitlistHasher;
import com.parkio.gateway.application.waitlist.WaitlistOpsNotifier;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Transactional outbox for waitlist operational notifications.
 *
 * <p>{@link #subscriptionConfirmed} runs inside the caller's confirmation
 * transaction, guarded by a savepoint: the row commits only if the confirmation
 * commits, and an outbox write failure rolls back to the savepoint instead of
 * failing the confirmation.
 */
@Component
public class JdbcWaitlistOpsNotificationOutbox implements WaitlistOpsNotifier {

    static final String EVENT_SUBSCRIPTION_CONFIRMED = "waitlist.subscription_confirmed";
    static final String METRIC = "parkio.waitlist.ops.notifications";

    private static final Logger log = LoggerFactory.getLogger(JdbcWaitlistOpsNotificationOutbox.class);

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate savepoint;
    private final WaitlistHasher hasher;
    private final WaitlistOpsNotificationProperties properties;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    public JdbcWaitlistOpsNotificationOutbox(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager,
            WaitlistHasher hasher,
            WaitlistOpsNotificationProperties properties,
            MeterRegistry meterRegistry,
            Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.savepoint = new TransactionTemplate(transactionManager);
        this.savepoint.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        this.hasher = hasher;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    /** Enabled and pointed at an export directory; otherwise nothing is recorded. */
    public boolean isActive() {
        return properties.isEnabled()
                && properties.getExportDir() != null
                && !properties.getExportDir().isBlank();
    }

    @Override
    public void subscriptionConfirmed(UUID interestId, Instant confirmedAt) {
        if (!isActive()) {
            return;
        }
        String dedupKey = "waitlist:subscription_confirmed:"
                + hasher.hash(EVENT_SUBSCRIPTION_CONFIRMED + ":" + interestId);
        Instant now = clock.instant();
        try {
            savepoint.executeWithoutResult(status -> jdbcTemplate.update("""
                    INSERT INTO waitlist_ops_notification_outbox (
                        id, event_type, dedup_key, occurred_at, status, attempts,
                        next_attempt_at, created_at
                    )
                    VALUES (?, ?, ?, ?, 'PENDING', 0, ?, ?)
                    """,
                    UUID.randomUUID(),
                    EVENT_SUBSCRIPTION_CONFIRMED,
                    dedupKey,
                    Timestamp.from(confirmedAt),
                    Timestamp.from(now),
                    Timestamp.from(now)));
            count("recorded");
        } catch (DuplicateKeyException ex) {
            count("duplicate_suppressed");
        } catch (RuntimeException ex) {
            // Never fail the subscriber's confirmation because of an ops notification.
            count("record_failed");
            log.warn("Waitlist ops notification not recorded; category={}", ex.getClass().getSimpleName());
        }
    }

    List<OutboxRow> findDue(Instant now, int limit) {
        return jdbcTemplate.query("""
                SELECT id, event_type, dedup_key, occurred_at, attempts
                FROM waitlist_ops_notification_outbox
                WHERE status = 'PENDING' AND next_attempt_at <= ?
                ORDER BY next_attempt_at ASC, created_at ASC
                LIMIT ?
                """,
                (rs, rowNum) -> new OutboxRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("event_type"),
                        rs.getString("dedup_key"),
                        rs.getTimestamp("occurred_at").toInstant(),
                        rs.getInt("attempts")),
                Timestamp.from(now),
                limit);
    }

    long countPending() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM waitlist_ops_notification_outbox WHERE status = 'PENDING'", Long.class);
        return count == null ? 0 : count;
    }

    void markExported(UUID id, Instant now) {
        jdbcTemplate.update("""
                UPDATE waitlist_ops_notification_outbox
                SET status = 'EXPORTED', exported_at = ?, attempts = attempts + 1, last_error_category = NULL
                WHERE id = ? AND status = 'PENDING'
                """, Timestamp.from(now), id);
    }

    void markRetry(UUID id, int attempts, Instant nextAttemptAt, String errorCategory) {
        jdbcTemplate.update("""
                UPDATE waitlist_ops_notification_outbox
                SET attempts = ?, next_attempt_at = ?, last_error_category = ?
                WHERE id = ? AND status = 'PENDING'
                """, attempts, Timestamp.from(nextAttemptAt), errorCategory, id);
    }

    void markFailed(UUID id, int attempts, String errorCategory) {
        jdbcTemplate.update("""
                UPDATE waitlist_ops_notification_outbox
                SET status = 'FAILED', attempts = ?, last_error_category = ?
                WHERE id = ? AND status = 'PENDING'
                """, attempts, errorCategory, id);
    }

    int purgeTerminalBefore(Instant cutoff) {
        return jdbcTemplate.update("""
                DELETE FROM waitlist_ops_notification_outbox
                WHERE status IN ('EXPORTED', 'FAILED') AND created_at < ?
                """, Timestamp.from(cutoff));
    }

    void count(String outcome) {
        meterRegistry.counter(METRIC, "outcome", outcome).increment();
    }

    record OutboxRow(UUID id, String eventType, String dedupKey, Instant occurredAt, int attempts) {
    }
}
