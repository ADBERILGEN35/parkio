package com.parkio.auth.presentation.dto.admin;

public record AdminSecuritySummaryResponse(
        long suspendedUsers,
        long pendingVerificationUsers,
        long activeSessionCount,
        long reuseDetectedSessionCount) {
}
