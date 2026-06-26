package com.parkio.media.infrastructure.persistence.jpa;

import com.parkio.media.infrastructure.persistence.entity.OutboxEventEntity;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxEventJpaRepository extends JpaRepository<OutboxEventEntity, UUID> {

    /**
     * Claims a batch of unpublished rows for this relay instance, oldest first.
     * {@code FOR UPDATE SKIP LOCKED} lets multiple instances drain the outbox without
     * contending on the same rows. Must run inside a transaction.
     */
    @Query(value = """
            SELECT * FROM outbox_events
            WHERE published = false AND dead_lettered = false
            ORDER BY created_at, id
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEventEntity> findUnpublishedBatchForUpdate(@Param("limit") int limit);

    /**
     * Backlog size for the {@code parkio.outbox.unpublished.count} gauge (cheap COUNT).
     * Excludes dead-lettered rows — those are no longer relayable and are tracked
     * separately by {@code parkio.outbox.deadlettered.count}.
     */
    long countByPublishedFalseAndDeadLetteredFalse();

    /** Open dead-lettered rows awaiting inspection/redrive — {@code parkio.outbox.deadlettered.count}. */
    @Query(
            value =
                    "SELECT COUNT(*) FROM outbox_events WHERE dead_lettered = true AND acknowledged_deadletter = false",
            nativeQuery = true)
    long countByDeadLetteredTrue();

    /** Acknowledged dead-lettered rows retained for audit. */
    @Query(value = "SELECT COUNT(*) FROM outbox_events WHERE dead_lettered = true AND acknowledged_deadletter = true",
            nativeQuery = true)
    long countAcknowledgedDeadletters();

    /** Oldest open dead-letter creation time; {@code null} when no operator action is pending. */
    @Query(
            value =
                    "SELECT MIN(created_at) FROM outbox_events WHERE dead_lettered = true AND acknowledged_deadletter = false",
            nativeQuery = true)
    Instant findOldestOpenDeadletterCreatedAt();

    /** Recovery audit count by action for low-cardinality operator metrics. */
    @Query(value = "SELECT COUNT(*) FROM outbox_recovery_audit WHERE action = :action", nativeQuery = true)
    long countRecoveryAuditByAction(@Param("action") String action);

    /** Oldest relayable row's creation time (gauge input); {@code null} when the backlog is empty. */
    @Query(value = "SELECT MIN(created_at) FROM outbox_events WHERE published = false AND dead_lettered = false",
            nativeQuery = true)
    Instant findOldestUnpublishedCreatedAt();
}
