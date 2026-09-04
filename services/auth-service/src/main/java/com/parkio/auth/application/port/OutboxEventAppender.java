package com.parkio.auth.application.port;

import com.parkio.auth.application.event.UserRestoredEvent;
import com.parkio.auth.application.event.UserSuspendedEvent;
import com.parkio.auth.domain.event.UserErasureRequestedEvent;
import com.parkio.auth.domain.event.UserRegisteredEvent;

/**
 * Port for appending domain events to the transactional outbox. The
 * implementation must enlist in the caller's transaction so the event is
 * persisted atomically with the state change (ai-context/06).
 */
public interface OutboxEventAppender {

    void append(UserRegisteredEvent event);

    void append(UserSuspendedEvent event);

    void append(UserRestoredEvent event);

    void append(UserErasureRequestedEvent event);
}
