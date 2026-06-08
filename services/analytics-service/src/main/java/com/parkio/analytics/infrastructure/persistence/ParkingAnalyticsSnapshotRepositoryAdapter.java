package com.parkio.analytics.infrastructure.persistence;

import com.parkio.analytics.application.port.ParkingAnalyticsSnapshotRepository;
import com.parkio.analytics.domain.AnalyticsMetricType;
import com.parkio.analytics.domain.ParkingAnalyticsSnapshot;
import com.parkio.analytics.infrastructure.persistence.jpa.ParkingAnalyticsSnapshotJpaRepository;
import com.parkio.analytics.infrastructure.persistence.mapper.AnalyticsPersistenceMapper;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Adapts the {@link ParkingAnalyticsSnapshotRepository} port to Spring Data JPA. */
@Component
public class ParkingAnalyticsSnapshotRepositoryAdapter implements ParkingAnalyticsSnapshotRepository {

    private final ParkingAnalyticsSnapshotJpaRepository jpa;

    public ParkingAnalyticsSnapshotRepositoryAdapter(ParkingAnalyticsSnapshotJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public ParkingAnalyticsSnapshot save(ParkingAnalyticsSnapshot snapshot) {
        return AnalyticsPersistenceMapper.toDomain(jpa.save(AnalyticsPersistenceMapper.toEntity(snapshot)));
    }

    @Override
    public Optional<ParkingAnalyticsSnapshot> findByMetricType(AnalyticsMetricType metricType) {
        return jpa.findByMetricType(metricType).map(AnalyticsPersistenceMapper::toDomain);
    }

    @Override
    public List<ParkingAnalyticsSnapshot> findAll() {
        return jpa.findAll().stream().map(AnalyticsPersistenceMapper::toDomain).toList();
    }
}
