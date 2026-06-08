package com.parkio.analytics.infrastructure.persistence;

import com.parkio.analytics.application.port.AnalyticsEventRepository;
import com.parkio.analytics.domain.AnalyticsEvent;
import com.parkio.analytics.infrastructure.persistence.jpa.AnalyticsEventJpaRepository;
import com.parkio.analytics.infrastructure.persistence.mapper.AnalyticsPersistenceMapper;
import org.springframework.stereotype.Component;

/** Adapts the {@link AnalyticsEventRepository} port to Spring Data JPA. */
@Component
public class AnalyticsEventRepositoryAdapter implements AnalyticsEventRepository {

    private final AnalyticsEventJpaRepository jpa;

    public AnalyticsEventRepositoryAdapter(AnalyticsEventJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public AnalyticsEvent save(AnalyticsEvent event) {
        jpa.save(AnalyticsPersistenceMapper.toEntity(event));
        return event;
    }
}
