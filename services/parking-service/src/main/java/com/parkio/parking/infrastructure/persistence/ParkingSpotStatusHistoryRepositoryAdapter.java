package com.parkio.parking.infrastructure.persistence;

import com.parkio.parking.application.port.ParkingSpotStatusHistoryRepository;
import com.parkio.parking.domain.ParkingSpotStatusHistory;
import com.parkio.parking.infrastructure.persistence.jpa.ParkingSpotStatusHistoryJpaRepository;
import com.parkio.parking.infrastructure.persistence.mapper.ParkingPersistenceMapper;
import org.springframework.stereotype.Component;

/** Adapts the {@link ParkingSpotStatusHistoryRepository} port to Spring Data JPA. */
@Component
public class ParkingSpotStatusHistoryRepositoryAdapter implements ParkingSpotStatusHistoryRepository {

    private final ParkingSpotStatusHistoryJpaRepository jpa;

    public ParkingSpotStatusHistoryRepositoryAdapter(ParkingSpotStatusHistoryJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public ParkingSpotStatusHistory save(ParkingSpotStatusHistory history) {
        jpa.save(ParkingPersistenceMapper.toEntity(history));
        return history;
    }
}
