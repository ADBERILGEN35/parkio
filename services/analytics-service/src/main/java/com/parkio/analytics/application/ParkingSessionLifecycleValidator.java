package com.parkio.analytics.application;

import com.parkio.analytics.application.event.ParkingHistoryDeletedEvent;
import com.parkio.analytics.application.event.ParkingHistoryDeletionScope;
import com.parkio.analytics.application.event.ParkingSessionCancelledEvent;
import com.parkio.analytics.application.event.ParkingSessionCompletedEvent;
import com.parkio.analytics.application.event.ParkingSessionStartedEvent;
import com.parkio.analytics.domain.exception.AnalyticsContractException;
import com.parkio.platform.messaging.EventEnvelope;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Contract validation for ParkingSession lifecycle envelopes and payloads (v1). */
public final class ParkingSessionLifecycleValidator {

    public static final int SUPPORTED_VERSION = 1;

    private ParkingSessionLifecycleValidator() {
    }

    public static void validateEnvelope(EventEnvelope envelope, String expectedWireType) {
        if (envelope == null) {
            throw new AnalyticsContractException("envelope is required");
        }
        if (envelope.eventId() == null) {
            throw new AnalyticsContractException("envelope.eventId is required");
        }
        if (envelope.eventType() == null || envelope.eventType().isBlank()) {
            throw new AnalyticsContractException("envelope.eventType is required");
        }
        if (!expectedWireType.equals(envelope.eventType())) {
            throw new AnalyticsContractException(
                    "envelope.eventType mismatch: expected " + expectedWireType
                            + " got " + envelope.eventType());
        }
        if (!ParkingSessionLifecycleMapper.AGGREGATE_TYPE.equals(envelope.aggregateType())) {
            throw new AnalyticsContractException(
                    "aggregateType must be ParkingSession, got " + envelope.aggregateType());
        }
        if (envelope.aggregateId() == null) {
            throw new AnalyticsContractException("envelope.aggregateId is required");
        }
        if (envelope.occurredAt() == null) {
            throw new AnalyticsContractException("envelope.occurredAt is required");
        }
        if (envelope.version() != SUPPORTED_VERSION) {
            throw new AnalyticsContractException(
                    "Unsupported event version: " + envelope.version());
        }
        if (envelope.payload() == null || envelope.payload().isNull()) {
            throw new AnalyticsContractException("envelope.payload is required");
        }
    }

    public static void validateStarted(EventEnvelope envelope, ParkingSessionStartedEvent event) {
        validateEnvelope(envelope, ParkingSessionLifecycleMapper.WIRE_STARTED);
        requirePayloadIdentity(envelope, event.eventId(), event.sessionId(), event.userId(),
                event.occurredAt(), event.startedAt());
        if (!"ACTIVE".equals(event.status())) {
            throw new AnalyticsContractException("ParkingSessionStarted status must be ACTIVE");
        }
        ParkingSessionLifecycleMapper.requireSource(event.source());
    }

    public static void validateCompleted(EventEnvelope envelope, ParkingSessionCompletedEvent event) {
        validateEnvelope(envelope, ParkingSessionLifecycleMapper.WIRE_COMPLETED);
        requirePayloadIdentity(envelope, event.eventId(), event.sessionId(), event.userId(),
                event.occurredAt(), event.startedAt());
        if (!"COMPLETED".equals(event.status())) {
            throw new AnalyticsContractException("ParkingSessionCompleted status must be COMPLETED");
        }
        ParkingSessionLifecycleMapper.requireSource(event.source());
        if (event.endedAt() == null) {
            throw new AnalyticsContractException("endedAt is required for ParkingSessionCompleted");
        }
        ParkingSessionLifecycleMapper.durationSeconds(event.startedAt(), event.endedAt());
    }

    public static void validateCancelled(EventEnvelope envelope, ParkingSessionCancelledEvent event) {
        validateEnvelope(envelope, ParkingSessionLifecycleMapper.WIRE_CANCELLED);
        requirePayloadIdentity(envelope, event.eventId(), event.sessionId(), event.userId(),
                event.occurredAt(), event.startedAt());
        if (!"CANCELLED".equals(event.status())) {
            throw new AnalyticsContractException("ParkingSessionCancelled status must be CANCELLED");
        }
        ParkingSessionLifecycleMapper.requireSource(event.source());
        if (event.endedAt() == null) {
            throw new AnalyticsContractException("endedAt is required for ParkingSessionCancelled");
        }
        ParkingSessionLifecycleMapper.durationSeconds(event.startedAt(), event.endedAt());
    }

    public static void validateHistoryDeleted(EventEnvelope envelope, ParkingHistoryDeletedEvent event) {
        validateEnvelope(envelope, ParkingSessionLifecycleMapper.WIRE_HISTORY_DELETED);
        if (event.eventId() == null) {
            throw new AnalyticsContractException("payload.eventId is required");
        }
        if (!Objects.equals(envelope.eventId(), event.eventId())) {
            throw new AnalyticsContractException("payload.eventId must match envelope.eventId");
        }
        if (event.userId() == null) {
            throw new AnalyticsContractException("userId is required");
        }
        if (event.scope() == null) {
            throw new AnalyticsContractException("scope is required");
        }
        if (event.occurredAt() == null) {
            throw new AnalyticsContractException("payload.occurredAt is required");
        }
        if (event.deletedCount() == null || event.deletedCount() < 1) {
            throw new AnalyticsContractException("deletedCount must be positive");
        }
        if (event.scope() == ParkingHistoryDeletionScope.SINGLE_TERMINAL_SESSION) {
            if (event.sessionId() == null) {
                throw new AnalyticsContractException("sessionId is required for SINGLE_TERMINAL_SESSION");
            }
            if (!Objects.equals(envelope.aggregateId(), event.sessionId())) {
                throw new AnalyticsContractException("sessionId must match envelope.aggregateId");
            }
            if (!Objects.equals(event.deletedCount(), 1)) {
                throw new AnalyticsContractException("deletedCount must be 1 for SINGLE_TERMINAL_SESSION");
            }
            return;
        }
        if (event.scope() != ParkingHistoryDeletionScope.ALL_TERMINAL_HISTORY) {
            throw new AnalyticsContractException("Unsupported ParkingHistoryDeletionScope: " + event.scope());
        }
        if (event.sessionId() != null) {
            throw new AnalyticsContractException("sessionId must be null for ALL_TERMINAL_HISTORY");
        }
        if (!Objects.equals(envelope.aggregateId(), event.userId())) {
            throw new AnalyticsContractException("userId must match envelope.aggregateId for ALL_TERMINAL_HISTORY");
        }
    }

    private static void requirePayloadIdentity(
            EventEnvelope envelope,
            UUID payloadEventId,
            UUID sessionId,
            UUID userId,
            Instant occurredAt,
            Instant startedAt) {
        if (payloadEventId == null) {
            throw new AnalyticsContractException("payload.eventId is required");
        }
        if (!Objects.equals(envelope.eventId(), payloadEventId)) {
            throw new AnalyticsContractException("payload.eventId must match envelope.eventId");
        }
        if (sessionId == null) {
            throw new AnalyticsContractException("sessionId is required");
        }
        if (!Objects.equals(envelope.aggregateId(), sessionId)) {
            throw new AnalyticsContractException("sessionId must match envelope.aggregateId");
        }
        if (userId == null) {
            throw new AnalyticsContractException("userId is required");
        }
        if (startedAt == null) {
            throw new AnalyticsContractException("startedAt is required");
        }
        if (occurredAt == null) {
            throw new AnalyticsContractException("payload.occurredAt is required");
        }
    }
}