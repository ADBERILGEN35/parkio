package com.parkio.notification.application.port;

import com.parkio.notification.domain.NotificationChannel;
import com.parkio.notification.domain.NotificationDeliveryAttempt;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Persistence port for {@link NotificationDeliveryAttempt}. */
public interface NotificationDeliveryAttemptRepository {

    NotificationDeliveryAttempt save(NotificationDeliveryAttempt attempt);

    /**
     * Claims a batch of due {@link com.parkio.notification.domain.DeliveryStatus#PENDING}
     * attempts ({@code nextAttemptAt <= now}), oldest due first, for exclusive processing.
     * Rows claimed by one worker instance are invisible to concurrent claimers
     * ({@code FOR UPDATE SKIP LOCKED}); must be called within a transaction that spans
     * processing and the subsequent {@link #save}.
     */
    List<NotificationDeliveryAttempt> claimDue(Instant now, int limit);

    /** Guards re-enqueueing: true if any attempt already exists for this notification + channel. */
    boolean existsByNotificationIdAndChannel(UUID notificationId, NotificationChannel channel);
}
