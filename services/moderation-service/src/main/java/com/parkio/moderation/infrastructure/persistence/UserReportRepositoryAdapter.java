package com.parkio.moderation.infrastructure.persistence;

import com.parkio.moderation.application.port.UserReportRepository;
import com.parkio.moderation.domain.ModerationReason;
import com.parkio.moderation.domain.ModerationTargetType;
import com.parkio.moderation.domain.UserReport;
import com.parkio.moderation.infrastructure.persistence.jpa.UserReportJpaRepository;
import com.parkio.moderation.infrastructure.persistence.mapper.ModerationPersistenceMapper;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Adapts the {@link UserReportRepository} port to Spring Data JPA. */
@Component
public class UserReportRepositoryAdapter implements UserReportRepository {

    private final UserReportJpaRepository jpa;

    public UserReportRepositoryAdapter(UserReportJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public UserReport save(UserReport report) {
        return ModerationPersistenceMapper.toDomain(jpa.save(ModerationPersistenceMapper.toEntity(report)));
    }

    @Override
    public boolean existsByReporterAndTargetAndReason(UUID reporterUserId, ModerationTargetType targetType,
                                                      UUID targetId, ModerationReason reason) {
        return jpa.existsByReporterUserIdAndTargetTypeAndTargetIdAndReason(reporterUserId, targetType, targetId, reason);
    }

    @Override
    public List<UserReport> findByReporterUserId(UUID reporterUserId) {
        return jpa.findByReporterUserIdOrderByCreatedAtDesc(reporterUserId).stream()
                .map(ModerationPersistenceMapper::toDomain)
                .toList();
    }
}
