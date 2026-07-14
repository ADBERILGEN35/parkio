package com.parkio.auth.application.result;

public record AdminSecuritySummary(
        long suspendedUsers,
        long pendingVerificationUsers,
        long activeSessionCount,
        long reuseDetectedSessionCount) {
}
