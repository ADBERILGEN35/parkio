package com.parkio.moderation.infrastructure.persistence.jpa;

import com.parkio.moderation.infrastructure.persistence.entity.AppealEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppealJpaRepository extends JpaRepository<AppealEntity, UUID> {

    boolean existsByCaseIdAndAppealUserId(UUID caseId, UUID appealUserId);

    List<AppealEntity> findTop200ByOrderByCreatedAtDesc();
}
