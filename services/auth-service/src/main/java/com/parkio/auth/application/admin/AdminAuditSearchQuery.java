package com.parkio.auth.application.admin;

import com.parkio.auth.domain.admin.AdminAuditAction;
import com.parkio.auth.domain.admin.AdminAuditResult;
import java.time.Instant;
import java.util.UUID;

public record AdminAuditSearchQuery(
        UUID actorUserId,
        UUID targetResourceId,
        AdminAuditAction actionType,
        AdminAuditResult result,
        Instant occurredFrom,
        Instant occurredTo,
        int page,
        int size,
        String sort) {

    private static final int MAX_PAGE_SIZE = 100;

    public AdminAuditSearchQuery {
        page = Math.max(page, 0);
        size = size < 1 ? 20 : Math.min(size, MAX_PAGE_SIZE);
    }
}
