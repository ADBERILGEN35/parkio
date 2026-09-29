package com.parkio.parking.infrastructure.persistence.jpa;

import com.parkio.parking.infrastructure.persistence.entity.MunicipalFacilityAliasEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MunicipalFacilityAliasJpaRepository
        extends JpaRepository<MunicipalFacilityAliasEntity, UUID> {}
