package com.parkio.media.application;

import com.parkio.media.application.port.OutboxEventAppender;
import com.parkio.media.domain.event.MediaRejectedEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists a {@link MediaRejectedEvent} in its OWN transaction. A rejection always
 * ends by throwing (so the caller's upload transaction rolls back); recording the
 * event in a separate {@code REQUIRES_NEW} transaction ensures it survives that
 * rollback (ai-context/06). A separate bean is required so the proxy applies the
 * new-transaction semantics (self-invocation would bypass it).
 */
@Component
public class MediaRejectionRecorder {

    private final OutboxEventAppender outbox;

    public MediaRejectionRecorder(OutboxEventAppender outbox) {
        this.outbox = outbox;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(MediaRejectedEvent event) {
        outbox.append(event);
    }
}
