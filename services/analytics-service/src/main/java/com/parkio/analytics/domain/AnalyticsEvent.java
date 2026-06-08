package com.parkio.analytics.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Raw, immutable record of one ingested upstream event, kept for auditability and so
 * snapshots can be recomputed. {@code sourceEventId} is the originating event's id;
 * {@code value} carries the metric magnitude (e.g. points earned), else 0. Pure
 * domain — no framework dependencies.
 */
public final class AnalyticsEvent {

    private final UUID id;
    private final UUID sourceEventId;
    private final AnalyticsMetricType metricType;
    private final UUID userId;
    private final UUID relatedEntityId;
    private final long value;
    private final Instant occurredAt;
    private final Instant createdAt;

    public AnalyticsEvent(UUID id, UUID sourceEventId, AnalyticsMetricType metricType, UUID userId,
                          UUID relatedEntityId, long value, Instant occurredAt, Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.sourceEventId = Objects.requireNonNull(sourceEventId, "sourceEventId");
        this.metricType = Objects.requireNonNull(metricType, "metricType");
        this.userId = userId;
        this.relatedEntityId = relatedEntityId;
        this.value = value;
        this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    public static AnalyticsEvent record(UUID sourceEventId, AnalyticsMetricType metricType, UUID userId,
                                        UUID relatedEntityId, long value, Instant occurredAt, Instant now) {
        return new AnalyticsEvent(UUID.randomUUID(), sourceEventId, metricType, userId, relatedEntityId,
                value, occurredAt, now);
    }

    public UUID id() {
        return id;
    }

    public UUID sourceEventId() {
        return sourceEventId;
    }

    public AnalyticsMetricType metricType() {
        return metricType;
    }

    public UUID userId() {
        return userId;
    }

    public UUID relatedEntityId() {
        return relatedEntityId;
    }

    public long value() {
        return value;
    }

    public Instant occurredAt() {
        return occurredAt;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
