package com.parkio.parking.infrastructure.persistence.jpa;

import com.parkio.parking.infrastructure.persistence.entity.MunicipalDataSourceEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MunicipalDataSourceJpaRepository extends JpaRepository<MunicipalDataSourceEntity, UUID> {}
