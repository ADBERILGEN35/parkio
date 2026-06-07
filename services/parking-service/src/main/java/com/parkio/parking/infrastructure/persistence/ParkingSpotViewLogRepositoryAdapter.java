package com.parkio.parking.infrastructure.persistence;

import com.parkio.parking.application.port.ParkingSpotViewLogRepository;
import com.parkio.parking.domain.ParkingSpotViewLog;
import com.parkio.parking.infrastructure.persistence.jpa.ParkingSpotViewLogJpaRepository;
import com.parkio.parking.infrastructure.persistence.mapper.ParkingPersistenceMapper;
import org.springframework.stereotype.Component;

/** Adapts the {@link ParkingSpotViewLogRepository} port to Spring Data JPA. */
@Component
public class ParkingSpotViewLogRepositoryAdapter implements ParkingSpotViewLogRepository {

    private final ParkingSpotViewLogJpaRepository jpa;

    public ParkingSpotViewLogRepositoryAdapter(ParkingSpotViewLogJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public ParkingSpotViewLog save(ParkingSpotViewLog viewLog) {
        jpa.save(ParkingPersistenceMapper.toEntity(viewLog));
        return viewLog;
    }
}
