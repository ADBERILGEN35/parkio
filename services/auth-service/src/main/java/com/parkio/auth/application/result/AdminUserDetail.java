package com.parkio.auth.application.result;

import java.util.List;

public record AdminUserDetail(
        AdminUserSummary user,
        List<AdminSessionSummary> sessions,
        List<AdminAuditSummary> recentAuditEvents) {
}
