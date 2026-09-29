package com.parkio.parking.infrastructure.persistence.jpa;

import com.parkio.parking.infrastructure.persistence.entity.MunicipalLinkReviewAuditEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MunicipalLinkReviewAuditJpaRepository
        extends JpaRepository<MunicipalLinkReviewAuditEntity, UUID> {}
