package com.parkio.user.infrastructure.persistence.jpa;

import com.parkio.user.infrastructure.persistence.entity.OutboxEventEntity;
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

    /** Dead-lettered (poison) rows awaiting inspection/redrive — {@code parkio.outbox.deadlettered.count}. */
    long countByDeadLetteredTrue();

    /** Oldest relayable row's creation time (gauge input); {@code null} when the backlog is empty. */
    @Query(value = "SELECT MIN(created_at) FROM outbox_events WHERE published = false AND dead_lettered = false",
            nativeQuery = true)
    Instant findOldestUnpublishedCreatedAt();
}
