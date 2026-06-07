package com.parkio.parking.infrastructure.persistence.jpa;

import com.parkio.parking.infrastructure.persistence.entity.ParkingSpotStatusHistoryEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ParkingSpotStatusHistoryJpaRepository
        extends JpaRepository<ParkingSpotStatusHistoryEntity, UUID> {
}
