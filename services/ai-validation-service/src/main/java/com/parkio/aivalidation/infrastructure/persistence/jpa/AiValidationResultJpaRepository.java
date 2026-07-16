package com.parkio.aivalidation.infrastructure.persistence.jpa;

import com.parkio.aivalidation.domain.AiValidationStatus;
import com.parkio.aivalidation.infrastructure.persistence.entity.AiValidationResultEntity;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiValidationResultJpaRepository extends JpaRepository<AiValidationResultEntity, UUID> {

    List<AiValidationResultEntity> findByMediaIdOrderByCreatedAtDesc(UUID mediaId);

    List<AiValidationResultEntity> findByParkingSpotIdOrderByCreatedAtDesc(UUID parkingSpotId);

    List<AiValidationResultEntity> findByStatusAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtAsc(
            AiValidationStatus status, Instant oldestInclusive, Instant newestExclusive, Pageable pageable);
}
