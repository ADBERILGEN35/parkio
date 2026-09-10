package com.parkio.parking.infrastructure.persistence.jpa;

import com.parkio.parking.infrastructure.persistence.entity.MunicipalFacilityFieldProvenanceEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MunicipalFacilityFieldProvenanceJpaRepository
        extends JpaRepository<MunicipalFacilityFieldProvenanceEntity, UUID> {}
