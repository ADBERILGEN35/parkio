package com.parkio.moderation.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Common shape for moderation domain events written to the transactional outbox. The
 * outbox adapter reads {@link #aggregateType()}, {@link #aggregateId()} and
 * {@link #eventType()} and serializes the concrete event as the payload
 * (ai-context/06). {@code aggregateId} is chosen as the most relevant partition key
 * per event (case id, parking spot id, user id or appeal id). Sealed so the set of
 * events is closed and explicit.
 */
public sealed interface ModerationEvent permits
        ModerationCaseOpenedEvent,
        ModerationCaseResolvedEvent,
        ParkingSpotRejectedByModeratorEvent,
        UserSuspendedEvent,
        UserRestoredEvent,
        AppealCreatedEvent,
        AppealResolvedEvent {

    UUID eventId();

    String aggregateType();

    UUID aggregateId();

    String eventType();

    Instant occurredAt();
}
