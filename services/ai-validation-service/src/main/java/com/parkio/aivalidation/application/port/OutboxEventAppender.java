package com.parkio.aivalidation.application.port;

import com.parkio.aivalidation.domain.event.AiValidationCompletedEvent;

/**
 * Port for appending the advisory completion event to the transactional outbox. The
 * implementation must enlist in the caller's transaction so the event is persisted
 * atomically with the result (ai-context/06).
 */
public interface OutboxEventAppender {

    void append(AiValidationCompletedEvent event);
}
