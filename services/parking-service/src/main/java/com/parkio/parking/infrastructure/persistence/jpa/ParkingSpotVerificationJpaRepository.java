package com.parkio.parking.infrastructure.persistence.jpa;

import com.parkio.parking.infrastructure.persistence.entity.ParkingSpotVerificationEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ParkingSpotVerificationJpaRepository
        extends JpaRepository<ParkingSpotVerificationEntity, UUID> {

    boolean existsBySpotIdAndVerifierUserId(UUID spotId, UUID verifierUserId);
}
