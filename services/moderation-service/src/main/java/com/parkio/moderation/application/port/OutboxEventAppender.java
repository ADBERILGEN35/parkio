package com.parkio.moderation.application.port;

import com.parkio.moderation.domain.event.ModerationEvent;

/**
 * Port for appending moderation events to the transactional outbox. The
 * implementation must enlist in the caller's transaction so the event is persisted
 * atomically with the state change (ai-context/06).
 */
public interface OutboxEventAppender {

    void append(ModerationEvent event);
}
