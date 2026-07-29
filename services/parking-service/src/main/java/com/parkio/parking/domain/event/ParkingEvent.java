package com.parkio.parking.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Common shape for parking domain events written to the transactional outbox. The
 * outbox adapter reads {@link #aggregateType()}, {@link #aggregateId()} and
 * {@link #eventType()} and serializes the concrete event as the payload
 * (ai-context/06). Sealed so the set of parking events is closed and explicit.
 */
public sealed interface ParkingEvent permits
        ParkingSpotCreatedEvent,
        ParkingSpotActivatedEvent,
        ParkingSpotVerifiedEvent,
        ParkingSpotMarkedFilledEvent,
        ParkingSpotClaimedEvent,
        ParkingSpotExpiredEvent,
        ParkingSpotModerationRetryRequestedEvent,
        ParkingSpotReviewFailedEvent,
        ParkingSessionStartedEvent,
        ParkingSessionCompletedEvent,
        ParkingSessionCancelledEvent,
        ParkingSessionReminderRequestedEvent,
        ParkingHistoryDeletedEvent {

    /** Aggregate type for spot lifecycle events (default {@link #aggregateType()}). */
    String AGGREGATE_TYPE = "ParkingSpot";

    /** Aggregate type for private ParkingSession lifecycle events. */
    String SESSION_AGGREGATE_TYPE = "ParkingSession";

    UUID eventId();

    UUID aggregateId();

    String eventType();

    Instant occurredAt();

    default String aggregateType() {
        return AGGREGATE_TYPE;
    }
}
