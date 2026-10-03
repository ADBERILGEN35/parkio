package com.parkio.user.application.port;

import com.parkio.user.domain.event.UserErasureAcknowledgedEvent;
import com.parkio.user.domain.event.UserErasureRestoreAcknowledgedEvent;

/**
 * Port for queuing an erasure ACK in the transactional outbox. The implementation must enlist
 * in the caller's transaction so the ACK commits atomically with the local erase, and must not
 * append an eventId that is already queued (docs/architecture/erasure-ack-outbox-contract.md).
 */
public interface ErasureAckOutbox {

    void append(UserErasureAcknowledgedEvent event);

    /** Queues a restore-replay ACK under the same rules; the row is keyed by {@code authUserId}. */
    void appendRestoreAck(UserErasureRestoreAcknowledgedEvent event);
}
