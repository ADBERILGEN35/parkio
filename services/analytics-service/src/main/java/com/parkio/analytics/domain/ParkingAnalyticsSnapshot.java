package com.parkio.analytics.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Global parking-funnel aggregate: one instance per parking {@link AnalyticsMetricType}.
 * Mutable accumulator. Pure domain.
 */
public final class ParkingAnalyticsSnapshot {

    private final UUID id;
    private final AnalyticsMetricType metricType;
    private long eventCount;
    private long sumValue;
    private Instant updatedAt;
    private final Long version;

    public ParkingAnalyticsSnapshot(UUID id, AnalyticsMetricType metricType, long eventCount,
                                    long sumValue, Instant updatedAt, Long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.metricType = Objects.requireNonNull(metricType, "metricType");
        if (!metricType.isParking()) {
            throw new IllegalArgumentException("Not a parking metric: " + metricType);
        }
        this.eventCount = eventCount;
        this.sumValue = sumValue;
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        this.version = version;
    }

    public static ParkingAnalyticsSnapshot start(AnalyticsMetricType metricType, Instant now) {
        return new ParkingAnalyticsSnapshot(UUID.randomUUID(), metricType, 0, 0, now, null);
    }

    public void increment(long countDelta, long valueDelta, Instant now) {
        this.eventCount += countDelta;
        this.sumValue += valueDelta;
        this.updatedAt = now;
    }

    public UUID id() {
        return id;
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
