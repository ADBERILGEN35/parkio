package com.parkio.parking.infrastructure.persistence.jpa;

import com.parkio.parking.infrastructure.persistence.entity.ParkingSpotVerificationEntity;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ParkingSpotVerificationJpaRepository
        extends JpaRepository<ParkingSpotVerificationEntity, UUID> {

    boolean existsBySpotIdAndVerifierUserId(UUID spotId, UUID verifierUserId);

    List<ParkingSpotVerificationEntity> findBySpotIdAndCreatedAtLessThanEqualOrderByCreatedAtAscIdAsc(
            UUID spotId,
            Instant createdAt);
}