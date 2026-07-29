package com.parkio.parking.domain.event;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Authoritative fact that owned terminal ParkingSession history was hard-deleted.
 * Privacy-minimized: no coordinates, session payloads, or idempotency keys.
 */
public record ParkingHistoryDeletedEvent(
        UUID eventId,
        UUID userId,
        ParkingHistoryDeletionScope scope,
        UUID sessionId,
        Integer deletedCount,
        Instant occurredAt) implements ParkingEvent {

    public static final String TYPE = "ParkingHistoryDeleted";

    public static ParkingHistoryDeletedEvent ofSingle(
            UUID userId, UUID sessionId, Instant occurredAt) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        return new ParkingHistoryDeletedEvent(
                UUID.randomUUID(),
                userId,
                ParkingHistoryDeletionScope.SINGLE_TERMINAL_SESSION,
                sessionId,
                1,
                occurredAt);
    }

    public static ParkingHistoryDeletedEvent ofAllTerminalHistory(
            UUID userId, int deletedCount, Instant occurredAt) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (deletedCount < 1) {
            throw new IllegalArgumentException("deletedCount must be positive");
        }
        return new ParkingHistoryDeletedEvent(
                UUID.randomUUID(),
                userId,
                ParkingHistoryDeletionScope.ALL_TERMINAL_HISTORY,
                null,
                deletedCount,
                occurredAt);
    }

    @Override
    public UUID aggregateId() {
        return scope == ParkingHistoryDeletionScope.SINGLE_TERMINAL_SESSION
                ? sessionId
                : userId;
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