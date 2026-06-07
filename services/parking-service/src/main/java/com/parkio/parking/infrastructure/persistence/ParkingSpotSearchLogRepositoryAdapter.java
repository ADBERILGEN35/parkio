package com.parkio.parking.infrastructure.persistence;

import com.parkio.parking.application.port.ParkingSpotSearchLogRepository;
import com.parkio.parking.domain.ParkingSpotSearchLog;
import com.parkio.parking.infrastructure.persistence.jpa.ParkingSpotSearchLogJpaRepository;
import com.parkio.parking.infrastructure.persistence.mapper.ParkingPersistenceMapper;
import org.springframework.stereotype.Component;

/** Adapts the {@link ParkingSpotSearchLogRepository} port to Spring Data JPA. */
@Component
public class ParkingSpotSearchLogRepositoryAdapter implements ParkingSpotSearchLogRepository {

    private final ParkingSpotSearchLogJpaRepository jpa;

    public ParkingSpotSearchLogRepositoryAdapter(ParkingSpotSearchLogJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public ParkingSpotSearchLog save(ParkingSpotSearchLog searchLog) {
        jpa.save(ParkingPersistenceMapper.toEntity(searchLog));
        return searchLog;
    }
}
