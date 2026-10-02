package com.parkio.moderation.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Participant erasure ACK written to the transactional outbox in the same transaction as the
 * local erase, so a SUCCESS exists only if the erase committed. {@code ModerationOutboxRelay}
 * publishes it to the auth coordinator's {@code parkio.privacy.erasure} topic; the payload
 * matches auth's {@code UserErasureAcknowledgedEvent}
 * (docs/architecture/erasure-ack-outbox-contract.md).
 */
public record UserErasureAcknowledgedEvent(
        UUID eventId,
        UUID erasureRequestId,
        UUID authUserId,
        String serviceName,
        String status,
        Instant occurredAt) {

    public static final String TYPE = "UserErasureAcknowledged";
    public static final String AGGREGATE_TYPE = "AccountErasure";
}
