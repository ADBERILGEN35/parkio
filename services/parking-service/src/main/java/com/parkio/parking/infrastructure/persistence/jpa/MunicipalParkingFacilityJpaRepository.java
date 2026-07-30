package com.parkio.parking.infrastructure.persistence.jpa;

import com.parkio.parking.infrastructure.persistence.entity.MunicipalParkingFacilityEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MunicipalParkingFacilityJpaRepository extends JpaRepository<MunicipalParkingFacilityEntity, UUID> {}
