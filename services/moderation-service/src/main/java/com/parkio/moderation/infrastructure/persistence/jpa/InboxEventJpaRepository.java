package com.parkio.moderation.infrastructure.persistence.jpa;

import com.parkio.moderation.infrastructure.persistence.entity.InboxEventEntity;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InboxEventJpaRepository extends JpaRepository<InboxEventEntity, UUID> {

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            INSERT INTO inbox_events (id, event_type, processed_at)
            VALUES (:id, :eventType, :processedAt)
            ON CONFLICT (id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id,
                       @Param("eventType") String eventType,
                       @Param("processedAt") Instant processedAt);
}
