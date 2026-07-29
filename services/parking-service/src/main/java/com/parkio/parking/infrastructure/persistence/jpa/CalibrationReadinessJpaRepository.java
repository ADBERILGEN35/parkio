package com.parkio.parking.infrastructure.persistence.jpa;

import com.parkio.parking.infrastructure.persistence.entity.CalibrationReadinessEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CalibrationReadinessJpaRepository extends JpaRepository<CalibrationReadinessEntity, UUID> {

    Optional<CalibrationReadinessEntity> findByAssessmentId(UUID assessmentId);
}