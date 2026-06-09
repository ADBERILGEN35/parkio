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
            WHERE published = false
            ORDER BY created_at, id
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEventEntity> findUnpublishedBatchForUpdate(@Param("limit") int limit);

    /** Backlog size for the {@code parkio.outbox.unpublished.count} gauge (cheap COUNT). */
    long countByPublishedFalse();

    /** Oldest unpublished row's creation time (gauge input); {@code null} when the backlog is empty. */
    @Query(value = "SELECT MIN(created_at) FROM outbox_events WHERE published = false", nativeQuery = true)
    Instant findOldestUnpublishedCreatedAt();
}
