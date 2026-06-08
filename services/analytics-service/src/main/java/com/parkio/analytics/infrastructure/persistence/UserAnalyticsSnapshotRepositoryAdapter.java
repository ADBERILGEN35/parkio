package com.parkio.analytics.infrastructure.persistence;

import com.parkio.analytics.application.port.UserAnalyticsSnapshotRepository;
import com.parkio.analytics.domain.AnalyticsMetricType;
import com.parkio.analytics.domain.UserAnalyticsSnapshot;
import com.parkio.analytics.infrastructure.persistence.jpa.UserAnalyticsSnapshotJpaRepository;
import com.parkio.analytics.infrastructure.persistence.mapper.AnalyticsPersistenceMapper;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Adapts the {@link UserAnalyticsSnapshotRepository} port to Spring Data JPA. */
@Component
public class UserAnalyticsSnapshotRepositoryAdapter implements UserAnalyticsSnapshotRepository {

    private final UserAnalyticsSnapshotJpaRepository jpa;

    public UserAnalyticsSnapshotRepositoryAdapter(UserAnalyticsSnapshotJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public UserAnalyticsSnapshot save(UserAnalyticsSnapshot snapshot) {
        return AnalyticsPersistenceMapper.toDomain(jpa.save(AnalyticsPersistenceMapper.toEntity(snapshot)));
    }

    @Override
    public Optional<UserAnalyticsSnapshot> findByUserIdAndMetricType(UUID userId, AnalyticsMetricType metricType) {
        return jpa.findByUserIdAndMetricType(userId, metricType).map(AnalyticsPersistenceMapper::toDomain);
    }

    @Override
    public List<UserAnalyticsSnapshot> findByUserId(UUID userId) {
        return jpa.findByUserId(userId).stream().map(AnalyticsPersistenceMapper::toDomain).toList();
    }
}
