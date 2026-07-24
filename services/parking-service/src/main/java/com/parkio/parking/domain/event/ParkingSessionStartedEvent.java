package com.parkio.parking.domain.event;

import com.parkio.parking.domain.ParkingSession;
import com.parkio.parking.domain.ParkingSessionStatus;
import com.parkio.parking.domain.ParkingSource;
import java.time.Instant;
import java.util.UUID;

/**
 * Authoritative fact that a ParkingSession row was successfully created as ACTIVE.
 * Privacy-minimized: no coordinates, fees, reminders, or idempotency keys.
 */
public record ParkingSessionStartedEvent(
        UUID eventId,
        UUID sessionId,
        UUID userId,
        ParkingSessionStatus status,
        ParkingSource source,
        Instant startedAt,
        Instant occurredAt) implements ParkingEvent {

    public static final String TYPE = "ParkingSessionStarted";

    public static ParkingSessionStartedEvent of(ParkingSession session, Instant occurredAt) {
        return new ParkingSessionStartedEvent(
                UUID.randomUUID(),
                session.getId(),
                session.getUserId(),
                session.getStatus(),
                session.getParkingSource(),
                session.getStartedAt(),
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