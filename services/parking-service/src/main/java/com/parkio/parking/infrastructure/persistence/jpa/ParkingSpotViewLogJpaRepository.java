package com.parkio.parking.infrastructure.persistence.jpa;

import com.parkio.parking.infrastructure.persistence.entity.ParkingSpotViewLogEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ParkingSpotViewLogJpaRepository extends JpaRepository<ParkingSpotViewLogEntity, UUID> {
}
