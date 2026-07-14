package com.parkio.auth.infrastructure.persistence.mapper;

import com.parkio.auth.domain.admin.AdminAuditEvent;
import com.parkio.auth.infrastructure.persistence.entity.AdminAuditEventEntity;

public final class AdminAuditPersistenceMapper {

    private AdminAuditPersistenceMapper() {
    }

    public static AdminAuditEvent toDomain(AdminAuditEventEntity entity) {
        return new AdminAuditEvent(
                entity.getId(),
                entity.getOccurredAt(),
                entity.getActorUserId(),
                entity.getActorRoles(),
                entity.getActionType(),
                entity.getTargetResourceType(),
                entity.getTargetResourceId(),
                entity.getResult(),
                entity.getReason(),
                entity.getCorrelationId(),
                entity.getTraceId(),
                entity.getMetadataJson());
    }

    public static AdminAuditEventEntity toEntity(AdminAuditEvent event) {
        return new AdminAuditEventEntity(
                event.id(),
                event.occurredAt(),
                event.actorUserId(),
                event.actorRoles(),
                event.actionType(),
                event.targetResourceType(),
                event.targetResourceId(),
                event.result(),
                event.reason(),
                event.correlationId(),
                event.traceId(),
                event.metadataJson());
    }
}
