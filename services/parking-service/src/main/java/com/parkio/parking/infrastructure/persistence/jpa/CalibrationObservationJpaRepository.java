package com.parkio.parking.infrastructure.persistence.jpa;

import com.parkio.parking.infrastructure.persistence.entity.CalibrationObservationEntity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CalibrationObservationJpaRepository extends JpaRepository<CalibrationObservationEntity, UUID> {

    Optional<CalibrationObservationEntity> findByObservationId(UUID observationId);

    List<CalibrationObservationEntity> findByEngineTypeAndPredictedAtBetweenOrderByPredictedAtAscIdAsc(
            String engineType, Instant windowStart, Instant windowEnd);
}