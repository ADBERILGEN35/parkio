package com.parkio.aivalidation.infrastructure.persistence.jpa;

import com.parkio.aivalidation.infrastructure.persistence.entity.AiValidationFindingEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiValidationFindingJpaRepository extends JpaRepository<AiValidationFindingEntity, UUID> {

    List<AiValidationFindingEntity> findByValidationResultIdOrderByCreatedAt(UUID validationResultId);
}
