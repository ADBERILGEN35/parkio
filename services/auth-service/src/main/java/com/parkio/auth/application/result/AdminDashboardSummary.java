package com.parkio.auth.application.result;

import com.parkio.auth.domain.AuthUserStatus;
import java.util.Map;

public record AdminDashboardSummary(
        long totalUsers,
        Map<AuthUserStatus, Long> usersByStatus,
        long verifiedUsers,
        long unverifiedUsers,
        long registrationsToday,
        long registrationsLast7Days,
        long registrationsLast30Days,
        double verificationConversionRate,
        long activeSessionCount) {
}
