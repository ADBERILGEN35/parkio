package com.parkio.parking.infrastructure.persistence;

import com.parkio.parking.application.port.ParkingSpotRepository;
import com.parkio.parking.domain.ParkingSpot;
import com.parkio.parking.infrastructure.persistence.jpa.ParkingSpotJpaRepository;
import com.parkio.parking.infrastructure.persistence.mapper.ParkingPersistenceMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Adapts the {@link ParkingSpotRepository} port to Spring Data JPA + PostGIS. */
@Component
public class ParkingSpotRepositoryAdapter implements ParkingSpotRepository {

    private final ParkingSpotJpaRepository jpa;

    public ParkingSpotRepositoryAdapter(ParkingSpotJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public ParkingSpot save(ParkingSpot spot) {
        return ParkingPersistenceMapper.toDomain(jpa.save(ParkingPersistenceMapper.toEntity(spot)));
    }

    @Override
    public Optional<ParkingSpot> findById(UUID id) {
        return jpa.findById(id).map(ParkingPersistenceMapper::toDomain);
    }

    @Override
    public List<ParkingSpot> findByOwnerUserId(UUID ownerUserId) {
        return jpa.findByOwnerUserIdOrderByCreatedAtDesc(ownerUserId).stream()
                .map(ParkingPersistenceMapper::toDomain)
                .toList();
    }

    @Override
    public List<ParkingSpot> findExpiredCandidates(Instant now, int batchSize) {
        return jpa.findExpiredCandidates(now, batchSize).stream()
                .map(ParkingPersistenceMapper::toDomain)
                .toList();
    }

    @Override
    public List<ParkingSpot> findNearby(double latitude, double longitude, double radiusMeters, int limit) {
        return jpa.findNearby(latitude, longitude, radiusMeters, limit).stream()
                .map(ParkingPersistenceMapper::toDomain)
                .toList();
    }
}
