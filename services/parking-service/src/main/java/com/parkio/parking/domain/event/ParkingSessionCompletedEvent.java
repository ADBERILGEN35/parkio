package com.parkio.parking.domain.event;

import com.parkio.parking.domain.ParkingSession;
import com.parkio.parking.domain.ParkingSessionCompletionReason;
import com.parkio.parking.domain.ParkingSessionStatus;
import com.parkio.parking.domain.ParkingSource;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Authoritative fact that an ACTIVE ParkingSession transitioned to COMPLETED.
 * Privacy-minimized: no coordinates or idempotency keys.
 *
 * <p>Additive fields ({@code completionReason}, {@code confirmedAt}, {@code sessionDurationSeconds})
 * are optional for older consumers — Jackson ignores unknown fields on the consumer side,
 * and missing fields deserialize as null.
 */
public record ParkingSessionCompletedEvent(
        UUID eventId,
        UUID sessionId,
        UUID userId,
        ParkingSessionStatus status,
        ParkingSource source,
        Instant startedAt,
        Instant endedAt,
        Instant occurredAt,
        ParkingSessionCompletionReason completionReason,
        Instant confirmedAt,
        Long sessionDurationSeconds) implements ParkingEvent {

    public static final String TYPE = "ParkingSessionCompleted";

    public static ParkingSessionCompletedEvent of(ParkingSession session, Instant occurredAt) {
        Instant started = session.getStartedAt();
        Instant ended = session.getEndedAt();
        Long durationSeconds = null;
        if (started != null && ended != null && !ended.isBefore(started)) {
            durationSeconds = Duration.between(started, ended).toSeconds();
        }
        return new ParkingSessionCompletedEvent(
                UUID.randomUUID(),
                session.getId(),
                session.getUserId(),
                session.getStatus(),
                session.getParkingSource(),
                started,
                ended,
                occurredAt,
                session.getCompletionReason(),
                session.getLastConfirmedAt(),
                durationSeconds);
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