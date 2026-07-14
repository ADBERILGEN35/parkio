package com.parkio.auth.infrastructure.persistence.jpa;

import com.parkio.auth.domain.admin.AdminAuditAction;
import com.parkio.auth.domain.admin.AdminAuditResult;
import com.parkio.auth.infrastructure.persistence.entity.AdminAuditEventEntity;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface AdminAuditEventJpaRepository
        extends JpaRepository<AdminAuditEventEntity, UUID>, JpaSpecificationExecutor<AdminAuditEventEntity> {

    List<AdminAuditEventEntity> findByTargetResourceTypeAndTargetResourceIdOrderByOccurredAtDesc(
            String targetResourceType, UUID targetResourceId, org.springframework.data.domain.Pageable pageable);
}
