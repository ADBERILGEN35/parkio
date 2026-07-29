package com.parkio.parking.infrastructure.persistence.jpa;

import com.parkio.parking.infrastructure.persistence.entity.ParkingSpotStatusHistoryEntity;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ParkingSpotStatusHistoryJpaRepository
        extends JpaRepository<ParkingSpotStatusHistoryEntity, UUID> {

    List<ParkingSpotStatusHistoryEntity> findBySpotIdAndCreatedAtLessThanEqualOrderByCreatedAtAscIdAsc(
            UUID spotId,
            Instant createdAt);
}