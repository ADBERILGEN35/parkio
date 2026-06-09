package com.parkio.notification.infrastructure.persistence.jpa;

import com.parkio.notification.domain.DeliveryStatus;
import com.parkio.notification.domain.NotificationChannel;
import com.parkio.notification.infrastructure.persistence.entity.NotificationDeliveryAttemptEntity;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationDeliveryAttemptJpaRepository
        extends JpaRepository<NotificationDeliveryAttemptEntity, UUID> {

    /**
     * Claims a batch of due PENDING attempts for this worker instance, oldest due first.
     * {@code FOR UPDATE SKIP LOCKED} lets multiple notification-service replicas drain
     * the queue without ever picking the same row. Must run inside a transaction that
     * stays open while the batch is processed.
     */
    @Query(value = """
            SELECT * FROM notification_delivery_attempts
            WHERE status = 'PENDING' AND next_attempt_at <= :now
            ORDER BY next_attempt_at, id
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<NotificationDeliveryAttemptEntity> claimDueForUpdate(@Param("now") Instant now, @Param("limit") int limit);

    boolean existsByNotificationIdAndChannel(UUID notificationId, NotificationChannel channel);

    /** Per-status backlog size for the {@code parkio.notification.delivery.*.count} gauges. */
    long countByStatus(DeliveryStatus status);
}
