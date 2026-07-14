package com.parkio.auth.application.result;

import com.parkio.auth.domain.admin.AdminAuditAction;
import com.parkio.auth.domain.admin.AdminAuditResult;
import java.time.Instant;
import java.util.UUID;

public record AdminAuditSummary(
        UUID id,
        Instant occurredAt,
        UUID actorUserId,
        String actorRoles,
        AdminAuditAction actionType,
        String targetResourceType,
        UUID targetResourceId,
        AdminAuditResult result,
        String reason,
        String correlationId) {
}
