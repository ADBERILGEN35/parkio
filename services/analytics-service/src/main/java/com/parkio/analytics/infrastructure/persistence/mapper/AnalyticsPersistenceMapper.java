package com.parkio.analytics.infrastructure.persistence.mapper;

import com.parkio.analytics.domain.AnalyticsEvent;
import com.parkio.analytics.domain.DailyAnalyticsSnapshot;
import com.parkio.analytics.domain.ParkingAnalyticsSnapshot;
import com.parkio.analytics.domain.UserAnalyticsSnapshot;
import com.parkio.analytics.infrastructure.persistence.entity.AnalyticsEventEntity;
import com.parkio.analytics.infrastructure.persistence.entity.DailyAnalyticsSnapshotEntity;
import com.parkio.analytics.infrastructure.persistence.entity.ParkingAnalyticsSnapshotEntity;
import com.parkio.analytics.infrastructure.persistence.entity.UserAnalyticsSnapshotEntity;

/**
 * Translates between domain models and JPA entities, keeping adapters thin and the
 * domain persistence-agnostic.
 */
public final class AnalyticsPersistenceMapper {

    private AnalyticsPersistenceMapper() {
    }

    public static AnalyticsEventEntity toEntity(AnalyticsEvent e) {
        return new AnalyticsEventEntity(e.id(), e.sourceEventId(), e.metricType(), e.userId(),
                e.relatedEntityId(), e.value(), e.occurredAt(), e.createdAt());
    }

    public static DailyAnalyticsSnapshot toDomain(DailyAnalyticsSnapshotEntity e) {
        return new DailyAnalyticsSnapshot(e.getId(), e.getSnapshotDate(), e.getMetricType(),
                e.getEventCount(), e.getSumValue(), e.getUpdatedAt(), e.getVersion());
    }

    public static DailyAnalyticsSnapshotEntity toEntity(DailyAnalyticsSnapshot s) {
        return new DailyAnalyticsSnapshotEntity(s.id(), s.snapshotDate(), s.metricType(),
                s.eventCount(), s.sumValue(), s.updatedAt(), s.version());
    }

    public static UserAnalyticsSnapshot toDomain(UserAnalyticsSnapshotEntity e) {
        return new UserAnalyticsSnapshot(e.getId(), e.getUserId(), e.getMetricType(),
                e.getEventCount(), e.getSumValue(), e.getUpdatedAt(), e.getVersion());
    }

    public static UserAnalyticsSnapshotEntity toEntity(UserAnalyticsSnapshot s) {
        return new UserAnalyticsSnapshotEntity(s.id(), s.userId(), s.metricType(),
                s.eventCount(), s.sumValue(), s.updatedAt(), s.version());
    }

    public static ParkingAnalyticsSnapshot toDomain(ParkingAnalyticsSnapshotEntity e) {
        return new ParkingAnalyticsSnapshot(e.getId(), e.getMetricType(), e.getEventCount(),
                e.getSumValue(), e.getUpdatedAt(), e.getVersion());
    }

    public static ParkingAnalyticsSnapshotEntity toEntity(ParkingAnalyticsSnapshot s) {
        return new ParkingAnalyticsSnapshotEntity(s.id(), s.metricType(), s.eventCount(),
                s.sumValue(), s.updatedAt(), s.version());
    }
}
