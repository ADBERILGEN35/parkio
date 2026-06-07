package com.parkio.user.application.port;

import com.parkio.user.domain.event.UserProfileCreatedEvent;

/**
 * Port for appending domain events to the transactional outbox. The
 * implementation must enlist in the caller's transaction so the event is
 * persisted atomically with the state change (ai-context/06).
 */
public interface OutboxEventAppender {

    void append(UserProfileCreatedEvent event);
}
