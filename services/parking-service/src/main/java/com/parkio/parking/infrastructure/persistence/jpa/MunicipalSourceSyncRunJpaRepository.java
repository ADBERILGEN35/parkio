package com.parkio.parking.infrastructure.persistence.jpa;

import com.parkio.parking.infrastructure.persistence.entity.MunicipalSourceSyncRunEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MunicipalSourceSyncRunJpaRepository extends JpaRepository<MunicipalSourceSyncRunEntity, UUID> {}
