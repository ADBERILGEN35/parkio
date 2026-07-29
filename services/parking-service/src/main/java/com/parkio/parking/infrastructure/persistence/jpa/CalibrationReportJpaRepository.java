package com.parkio.parking.infrastructure.persistence.jpa;

import com.parkio.parking.infrastructure.persistence.entity.CalibrationReportEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CalibrationReportJpaRepository extends JpaRepository<CalibrationReportEntity, UUID> {

    Optional<CalibrationReportEntity> findByReportId(UUID reportId);

    Optional<CalibrationReportEntity> findFirstByEngineTypeAndBaselinePolicyVersionOrderByGeneratedAtDescIdDesc(
            String engineType, String baselinePolicyVersion);
}