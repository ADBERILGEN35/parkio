package com.parkio.auth.presentation.dto.admin;

import com.parkio.auth.domain.AuthUserStatus;
import com.parkio.auth.domain.RoleName;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record AdminDashboardResponse(
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
