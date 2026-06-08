package com.parkio.analytics.infrastructure.persistence.entity;

import com.parkio.analytics.domain.AnalyticsMetricType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** JPA mapping for the raw {@code analytics_events} audit log. */
@Entity
@Table(name = "analytics_events")
public class AnalyticsEventEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "source_event_id", nullable = false, updatable = false)
    private UUID sourceEventId;

    @Enumerated(EnumType.STRING)
    @Column(name = "metric_type", nullable = false, updatable = false)
    private AnalyticsMetricType metricType;

    @Column(name = "user_id", updatable = false)
    private UUID userId;

    @Column(name = "related_entity_id", updatable = false)
    private UUID relatedEntityId;

    @Column(name = "metric_value", nullable = false, updatable = false)
    private long value;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AnalyticsEventEntity() {
        // for JPA
    }

    public AnalyticsEventEntity(UUID id, UUID sourceEventId, AnalyticsMetricType metricType, UUID userId,
                                UUID relatedEntityId, long value, Instant occurredAt, Instant createdAt) {
        this.id = id;
        this.sourceEventId = sourceEventId;
        this.metricType = metricType;
        this.userId = userId;
        this.relatedEntityId = relatedEntityId;
        this.value = value;
        this.occurredAt = occurredAt;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getSourceEventId() {
        return sourceEventId;
    }

    public AnalyticsMetricType getMetricType() {
        return metricType;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getRelatedEntityId() {
        return relatedEntityId;
    }

    public long getValue() {
        return value;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
