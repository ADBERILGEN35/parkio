package com.parkio.moderation.application.port;

import com.parkio.moderation.domain.event.UserErasureAcknowledgedEvent;

/**
 * Port for queuing an erasure ACK in the transactional outbox. The implementation must enlist
 * in the caller's transaction so the ACK commits atomically with the local erase, and must not
 * append an eventId that is already queued (docs/architecture/erasure-ack-outbox-contract.md).
 */
public interface ErasureAckOutbox {

    void append(UserErasureAcknowledgedEvent event);
}
