package com.parkio.aivalidation.infrastructure.persistence.jpa;

import com.parkio.aivalidation.infrastructure.persistence.entity.VehicleFitEstimateEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VehicleFitEstimateJpaRepository extends JpaRepository<VehicleFitEstimateEntity, UUID> {

    List<VehicleFitEstimateEntity> findByValidationResultIdOrderByCreatedAt(UUID validationResultId);
}
