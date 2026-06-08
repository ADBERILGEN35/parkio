package com.parkio.analytics.infrastructure.persistence.entity;

import com.parkio.analytics.domain.AnalyticsMetricType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/** JPA mapping for {@code user_analytics_snapshots}. */
@Entity
@Table(name = "user_analytics_snapshots")
public class UserAnalyticsSnapshotEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "metric_type", nullable = false, updatable = false)
    private AnalyticsMetricType metricType;

    @Column(name = "event_count", nullable = false)
    private long eventCount;

    @Column(name = "sum_value", nullable = false)
    private long sumValue;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected UserAnalyticsSnapshotEntity() {
        // for JPA
    }

    public UserAnalyticsSnapshotEntity(UUID id, UUID userId, AnalyticsMetricType metricType,
                                       long eventCount, long sumValue, Instant updatedAt, Long version) {
        this.id = id;
        this.userId = userId;
        this.metricType = metricType;
        this.eventCount = eventCount;
        this.sumValue = sumValue;
        this.updatedAt = updatedAt;
        this.version = version;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public AnalyticsMetricType getMetricType() {
        return metricType;
    }

    public long getEventCount() {
        return eventCount;
    }

    public long getSumValue() {
        return sumValue;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Long getVersion() {
        return version;
    }
}
