package com.parkio.auth.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** JPA mapping for the transactional {@code outbox_events} table. */
@Entity
@Table(name = "outbox_events")
public class OutboxEventEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /**
     * The domain event's {@code eventId} (the consumer dedup key), stored as a column so
     * a future Kafka relay can read it without parsing the JSON {@code payload}.
     */
    @Column(name = "event_id", updatable = false)
    private UUID eventId;

    @Column(name = "aggregate_type", nullable = false)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "payload", nullable = false)
    private String payload;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "trace_id")
    private String traceId;

    @Column(name = "published", nullable = false)
    private boolean published;

    /** Number of failed publish attempts (DLQ tracking). */
    @Column(name = "failure_count", nullable = false)
    private int failureCount;

    /** Short reason for the most recent publish failure (bounded). */
    @Column(name = "last_failure_reason")
    private String lastFailureReason;

    /** When the most recent publish attempt failed. */
    @Column(name = "last_failed_at")
    private Instant lastFailedAt;

    /**
     * True once the row has failed {@code max-attempts} times: the relay skips it (so it
     * never blocks later events) but the row is retained for inspection / manual redrive.
     */
    @Column(name = "dead_lettered", nullable = false)
    private boolean deadLettered;

    /** Upper bound on the stored failure reason so a stack trace can't bloat the row. */
    private static final int MAX_REASON_LENGTH = 2000;

    protected OutboxEventEntity() {
        // for JPA
    }

    public OutboxEventEntity(UUID id, UUID eventId, String aggregateType, UUID aggregateId, String eventType,
                             String payload, Instant occurredAt, boolean published) {
        this(id, eventId, aggregateType, aggregateId, eventType, payload, occurredAt, null, published);
    }

    public OutboxEventEntity(UUID id, UUID eventId, String aggregateType, UUID aggregateId, String eventType,
                             String payload, Instant occurredAt, String traceId, boolean published) {
        this.id = id;
        this.eventId = eventId;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payload = payload;
        this.occurredAt = occurredAt;
        this.traceId = traceId;
        this.published = published;
    }

    public UUID getId() {
        return id;
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public UUID getAggregateId() {
        return aggregateId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getPayload() {
        return payload;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public String getTraceId() {
        return traceId;
    }

    public boolean isPublished() {
        return published;
    }

    public int getFailureCount() {
        return failureCount;
    }

    public String getLastFailureReason() {
        return lastFailureReason;
    }

    public Instant getLastFailedAt() {
        return lastFailedAt;
    }

    public boolean isDeadLettered() {
        return deadLettered;
    }

    /** Marks this row as published after a successful Kafka send (the outbox relay). */
    public void markPublished() {
        this.published = true;
    }

    /**
     * Records a failed publish attempt. After {@code maxAttempts} failures the row is
     * dead-lettered so the relay stops retrying it (it no longer blocks later events),
     * while the row and its payload are retained for inspection / manual redrive.
     *
     * @return {@code true} if this call transitioned the row into the dead-lettered state.
     */
    public boolean recordPublishFailure(String reason, Instant failedAt, int maxAttempts) {
        this.failureCount += 1;
        this.lastFailureReason = truncate(reason);
        this.lastFailedAt = failedAt;
        if (!this.deadLettered && this.failureCount >= maxAttempts) {
            this.deadLettered = true;
            return true;
        }
        return false;
    }

    private static String truncate(String reason) {
        if (reason == null) {
            return null;
        }
        return reason.length() <= MAX_REASON_LENGTH ? reason : reason.substring(0, MAX_REASON_LENGTH);
    }
}
