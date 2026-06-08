package com.parkio.analytics.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Per-day, per-metric rolling aggregate. Mutable accumulator: {@link #increment}
 * adds an event's count and value. Pure domain.
 */
public final class DailyAnalyticsSnapshot {

    private final UUID id;
    private final LocalDate snapshotDate;
    private final AnalyticsMetricType metricType;
    private long eventCount;
    private long sumValue;
    private Instant updatedAt;
    private final Long version;

    public DailyAnalyticsSnapshot(UUID id, LocalDate snapshotDate, AnalyticsMetricType metricType,
                                  long eventCount, long sumValue, Instant updatedAt, Long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.snapshotDate = Objects.requireNonNull(snapshotDate, "snapshotDate");
        this.metricType = Objects.requireNonNull(metricType, "metricType");
        this.eventCount = eventCount;
        this.sumValue = sumValue;
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        this.version = version;
    }

    public static DailyAnalyticsSnapshot start(LocalDate snapshotDate, AnalyticsMetricType metricType, Instant now) {
        return new DailyAnalyticsSnapshot(UUID.randomUUID(), snapshotDate, metricType, 0, 0, now, null);
    }

    public void increment(long countDelta, long valueDelta, Instant now) {
        this.eventCount += countDelta;
        this.sumValue += valueDelta;
        this.updatedAt = now;
    }

    public UUID id() {
        return id;
    }

    public LocalDate snapshotDate() {
        return snapshotDate;
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
