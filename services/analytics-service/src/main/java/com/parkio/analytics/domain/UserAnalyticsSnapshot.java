package com.parkio.analytics.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Per-user, per-metric rolling aggregate. {@code userId} is the authUserId. Mutable
 * accumulator. Pure domain.
 */
public final class UserAnalyticsSnapshot {

    private final UUID id;
    private final UUID userId;
    private final AnalyticsMetricType metricType;
    private long eventCount;
    private long sumValue;
    private Instant updatedAt;
    private final Long version;

    public UserAnalyticsSnapshot(UUID id, UUID userId, AnalyticsMetricType metricType,
                                 long eventCount, long sumValue, Instant updatedAt, Long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.userId = Objects.requireNonNull(userId, "userId");
        this.metricType = Objects.requireNonNull(metricType, "metricType");
        this.eventCount = eventCount;
        this.sumValue = sumValue;
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        this.version = version;
    }

    public static UserAnalyticsSnapshot start(UUID userId, AnalyticsMetricType metricType, Instant now) {
        return new UserAnalyticsSnapshot(UUID.randomUUID(), userId, metricType, 0, 0, now, null);
    }

    public void increment(long countDelta, long valueDelta, Instant now) {
        this.eventCount += countDelta;
        this.sumValue += valueDelta;
        this.updatedAt = now;
    }

    public UUID id() {
        return id;
    }

    public UUID userId() {
        return userId;
    }

    public AnalyticsMetricType metricType() {
        return metricType;
    }

    public long eventCount() {
        return eventCount;
    }

    public long sumValue() {
        return sumValue;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public Long version() {
        return version;
    }
}
