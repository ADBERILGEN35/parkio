package com.parkio.auth.presentation;

import com.parkio.auth.application.result.AdminAuditSummary;
import com.parkio.auth.application.result.AdminDashboardSummary;
import com.parkio.auth.application.result.AdminSecuritySummary;
import com.parkio.auth.application.result.AdminSessionSummary;
import com.parkio.auth.application.result.AdminUserDetail;
import com.parkio.auth.application.result.AdminUserSummary;
import com.parkio.auth.application.result.PageResult;
import com.parkio.auth.presentation.dto.admin.AdminAuditEventResponse;
import com.parkio.auth.presentation.dto.admin.AdminDashboardResponse;
import com.parkio.auth.presentation.dto.admin.AdminPageResponse;
import com.parkio.auth.presentation.dto.admin.AdminSecuritySummaryResponse;
import com.parkio.auth.presentation.dto.admin.AdminSessionResponse;
import com.parkio.auth.presentation.dto.admin.AdminUserDetailResponse;
import com.parkio.auth.presentation.dto.admin.AdminUserSummaryResponse;

final class AdminResponseMapper {

    private AdminResponseMapper() {
    }

    static AdminDashboardResponse toDashboard(AdminDashboardSummary summary) {
        return new AdminDashboardResponse(
                summary.totalUsers(),
                summary.usersByStatus(),
                summary.verifiedUsers(),
                summary.unverifiedUsers(),
                summary.registrationsToday(),
                summary.registrationsLast7Days(),
                summary.registrationsLast30Days(),
                summary.verificationConversionRate(),
                summary.activeSessionCount());
    }

    static AdminUserSummaryResponse toUserSummary(AdminUserSummary summary) {
        return new AdminUserSummaryResponse(
                summary.id(),
                summary.email(),
                summary.status(),
                summary.emailVerified(),
                summary.roles(),
                summary.createdAt(),
                summary.activeSessionCount());
    }

    static AdminSessionResponse toSession(AdminSessionSummary session) {
        return new AdminSessionResponse(
                session.sessionId(),
                session.createdAt(),
                session.revoked(),
                session.revokedReason(),
                session.expiresAt());
    }

    static AdminAuditEventResponse toAudit(AdminAuditSummary audit) {
        return new AdminAuditEventResponse(
                audit.id(),
                audit.occurredAt(),
                audit.actorUserId(),
                audit.actorRoles(),
                audit.actionType(),
                audit.targetResourceType(),
                audit.targetResourceId(),
                audit.result(),
                audit.reason(),
                audit.correlationId());
    }

    static AdminUserDetailResponse toUserDetail(AdminUserDetail detail) {
        return new AdminUserDetailResponse(
                toUserSummary(detail.user()),
                detail.sessions().stream().map(AdminResponseMapper::toSession).toList(),
                detail.recentAuditEvents().stream().map(AdminResponseMapper::toAudit).toList());
    }

    static <T, R> AdminPageResponse<R> toPage(PageResult<T> page, java.util.function.Function<T, R> mapper) {
        return new AdminPageResponse<>(
                page.content().stream().map(mapper).toList(),
                page.page(),
                page.size(),
                page.totalElements(),
                page.totalPages());
    }

    static AdminSecuritySummaryResponse toSecurity(AdminSecuritySummary summary) {
        return new AdminSecuritySummaryResponse(
                summary.suspendedUsers(),
                summary.pendingVerificationUsers(),
                summary.activeSessionCount(),
                summary.reuseDetectedSessionCount());
    }
}
