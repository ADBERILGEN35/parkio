package com.parkio.auth.presentation.dto.admin;

import java.util.List;

public record AdminUserDetailResponse(
        AdminUserSummaryResponse user,
        List<AdminSessionResponse> sessions,
        List<AdminAuditEventResponse> recentAuditEvents) {
}
