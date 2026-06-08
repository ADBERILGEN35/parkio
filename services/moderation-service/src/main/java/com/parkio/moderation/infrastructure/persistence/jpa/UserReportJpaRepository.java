package com.parkio.moderation.infrastructure.persistence.jpa;

import com.parkio.moderation.domain.ModerationReason;
import com.parkio.moderation.domain.ModerationTargetType;
import com.parkio.moderation.infrastructure.persistence.entity.UserReportEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserReportJpaRepository extends JpaRepository<UserReportEntity, UUID> {

    boolean existsByReporterUserIdAndTargetTypeAndTargetIdAndReason(
            UUID reporterUserId, ModerationTargetType targetType, UUID targetId, ModerationReason reason);

    List<UserReportEntity> findByReporterUserIdOrderByCreatedAtDesc(UUID reporterUserId);
}
