package com.parkio.parking.infrastructure.persistence.jpa;

import com.parkio.parking.infrastructure.persistence.entity.ParkingSpotSearchLogEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ParkingSpotSearchLogJpaRepository extends JpaRepository<ParkingSpotSearchLogEntity, UUID> {
}
