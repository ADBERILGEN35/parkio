package com.parkio.moderation.infrastructure.persistence.jpa;

import com.parkio.moderation.domain.ModerationStatus;
import com.parkio.moderation.domain.ModerationTargetType;
import com.parkio.moderation.infrastructure.persistence.entity.ModerationCaseEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ModerationCaseJpaRepository extends JpaRepository<ModerationCaseEntity, UUID> {

    Optional<ModerationCaseEntity> findFirstByTargetTypeAndTargetIdAndStatusIn(
            ModerationTargetType targetType, UUID targetId, List<ModerationStatus> statuses);

    List<ModerationCaseEntity> findTop200ByOrderByOpenedAtDesc();

    List<ModerationCaseEntity> findByStatusOrderByOpenedAtDesc(ModerationStatus status);
}
