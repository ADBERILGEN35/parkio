package com.parkio.analytics.infrastructure.persistence;

import com.parkio.analytics.application.port.DailyAnalyticsSnapshotRepository;
import com.parkio.analytics.domain.AnalyticsMetricType;
import com.parkio.analytics.domain.DailyAnalyticsSnapshot;
import com.parkio.analytics.infrastructure.persistence.jpa.DailyAnalyticsSnapshotJpaRepository;
import com.parkio.analytics.infrastructure.persistence.mapper.AnalyticsPersistenceMapper;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Adapts the {@link DailyAnalyticsSnapshotRepository} port to Spring Data JPA. */
@Component
public class DailyAnalyticsSnapshotRepositoryAdapter implements DailyAnalyticsSnapshotRepository {

    private final DailyAnalyticsSnapshotJpaRepository jpa;

    public DailyAnalyticsSnapshotRepositoryAdapter(DailyAnalyticsSnapshotJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public DailyAnalyticsSnapshot save(DailyAnalyticsSnapshot snapshot) {
        return AnalyticsPersistenceMapper.toDomain(jpa.save(AnalyticsPersistenceMapper.toEntity(snapshot)));
    }

    @Override
    public Optional<DailyAnalyticsSnapshot> findByDateAndMetricType(LocalDate snapshotDate,
                                                                    AnalyticsMetricType metricType) {
        return jpa.findBySnapshotDateAndMetricType(snapshotDate, metricType)
                .map(AnalyticsPersistenceMapper::toDomain);
    }

    @Override
    public List<DailyAnalyticsSnapshot> findAll() {
        return jpa.findAllByOrderBySnapshotDateAscMetricTypeAsc().stream()
                .map(AnalyticsPersistenceMapper::toDomain)
                .toList();
    }
}
