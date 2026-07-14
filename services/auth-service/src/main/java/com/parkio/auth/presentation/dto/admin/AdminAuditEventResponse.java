package com.parkio.auth.presentation.dto.admin;

import com.parkio.auth.domain.admin.AdminAuditAction;
import com.parkio.auth.domain.admin.AdminAuditResult;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AdminAuditEventResponse(
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
