package com.parkio.parking.infrastructure.persistence;

import com.parkio.parking.application.port.ParkingSpotVerificationRepository;
import com.parkio.parking.domain.ParkingSpotVerification;
import com.parkio.parking.infrastructure.persistence.jpa.ParkingSpotVerificationJpaRepository;
import com.parkio.parking.infrastructure.persistence.mapper.ParkingPersistenceMapper;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Adapts the {@link ParkingSpotVerificationRepository} port to Spring Data JPA. */
@Component
public class ParkingSpotVerificationRepositoryAdapter implements ParkingSpotVerificationRepository {

    private final ParkingSpotVerificationJpaRepository jpa;

    public ParkingSpotVerificationRepositoryAdapter(ParkingSpotVerificationJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public ParkingSpotVerification save(ParkingSpotVerification verification) {
        return ParkingPersistenceMapper.toDomain(jpa.save(ParkingPersistenceMapper.toEntity(verification)));
    }

    @Override
    public boolean existsBySpotIdAndVerifierUserId(UUID spotId, UUID verifierUserId) {
        return jpa.existsBySpotIdAndVerifierUserId(spotId, verifierUserId);
    }
}
