package com.parkio.parking.infrastructure.persistence.jpa;

import com.parkio.parking.infrastructure.persistence.entity.MunicipalOccupancySnapshotEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MunicipalOccupancySnapshotJpaRepository extends JpaRepository<MunicipalOccupancySnapshotEntity, UUID> {}
