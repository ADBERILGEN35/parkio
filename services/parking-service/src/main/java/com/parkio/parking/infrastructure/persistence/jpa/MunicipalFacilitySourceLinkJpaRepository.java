package com.parkio.parking.infrastructure.persistence.jpa;

import com.parkio.parking.infrastructure.persistence.entity.MunicipalFacilitySourceLinkEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MunicipalFacilitySourceLinkJpaRepository extends JpaRepository<MunicipalFacilitySourceLinkEntity, UUID> {}
