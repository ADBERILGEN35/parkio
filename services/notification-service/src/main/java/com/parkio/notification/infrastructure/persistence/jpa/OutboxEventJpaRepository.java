package com.parkio.notification.infrastructure.persistence.jpa;

import com.parkio.notification.infrastructure.persistence.entity.OutboxEventEntity;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface OutboxEventJpaRepository extends JpaRepository<OutboxEventEntity, UUID> {

    /** Backlog size for the {@code parkio.outbox.unpublished.count} gauge (cheap COUNT). */
    long countByPublishedFalse();

    /** Oldest unpublished row's creation time (gauge input); {@code null} when the backlog is empty. */
    @Query(value = "SELECT MIN(created_at) FROM outbox_events WHERE published = false", nativeQuery = true)
    Instant findOldestUnpublishedCreatedAt();
}
