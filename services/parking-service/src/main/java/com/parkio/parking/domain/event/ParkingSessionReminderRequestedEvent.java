package com.parkio.parking.domain.event;

import com.parkio.parking.domain.ParkingSession;
import com.parkio.parking.domain.ParkingSessionReminderStage;
import java.time.Instant;
import java.util.UUID;

/**
 * Request for notification-service to create an in-app (+ push) reminder for a
 * stale ACTIVE parking session. Parking-service never calls notification-service
 * directly — this event is written to the session outbox topic.
 */
public record ParkingSessionReminderRequestedEvent(
        UUID eventId,
        UUID sessionId,
        UUID userId,
        ParkingSessionReminderStage stage,
        Instant startedAt,
        Instant lastConfirmedAt,
        Instant occurredAt) implements ParkingEvent {

    public static final String TYPE = "ParkingSessionReminderRequested";

    public static ParkingSessionReminderRequestedEvent of(
            ParkingSession session, ParkingSessionReminderStage stage, Instant occurredAt) {
        return new ParkingSessionReminderRequestedEvent(
                UUID.randomUUID(),
                session.getId(),
                session.getUserId(),
                stage,
                session.getStartedAt(),
                session.getLastConfirmedAt(),
                occurredAt);
    }

    @Override
    public UUID aggregateId() {
        return sessionId;
    }

    @Override
    public String eventType() {
        return TYPE;
    }

    @Override
    public String aggregateType() {
        return ParkingEvent.SESSION_AGGREGATE_TYPE;
    }
}