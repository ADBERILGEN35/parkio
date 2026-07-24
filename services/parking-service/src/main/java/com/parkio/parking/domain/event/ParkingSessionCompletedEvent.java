package com.parkio.parking.domain.event;

import com.parkio.parking.domain.ParkingSession;
import com.parkio.parking.domain.ParkingSessionStatus;
import com.parkio.parking.domain.ParkingSource;
import java.time.Instant;
import java.util.UUID;

/**
 * Authoritative fact that an ACTIVE ParkingSession transitioned to COMPLETED.
 * Privacy-minimized: no coordinates or idempotency keys. Consumers may derive
 * duration from startedAt and endedAt.
 */
public record ParkingSessionCompletedEvent(
        UUID eventId,
        UUID sessionId,
        UUID userId,
        ParkingSessionStatus status,
        ParkingSource source,
        Instant startedAt,
        Instant endedAt,
        Instant occurredAt) implements ParkingEvent {

    public static final String TYPE = "ParkingSessionCompleted";

    public static ParkingSessionCompletedEvent of(ParkingSession session, Instant occurredAt) {
        return new ParkingSessionCompletedEvent(
                UUID.randomUUID(),
                session.getId(),
                session.getUserId(),
                session.getStatus(),
                session.getParkingSource(),
                session.getStartedAt(),
                session.getEndedAt(),
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