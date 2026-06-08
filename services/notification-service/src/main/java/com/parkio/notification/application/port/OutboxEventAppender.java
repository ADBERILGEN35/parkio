package com.parkio.notification.application.port;

import com.parkio.notification.domain.event.NotificationCreatedEvent;

/**
 * Port for appending notification events to the transactional outbox. The
 * implementation must enlist in the caller's transaction so the event is persisted
 * atomically with the state change (ai-context/06).
 */
public interface OutboxEventAppender {

    void append(NotificationCreatedEvent event);
}
