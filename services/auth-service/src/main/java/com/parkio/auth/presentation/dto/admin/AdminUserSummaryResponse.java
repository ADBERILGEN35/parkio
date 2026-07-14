package com.parkio.auth.presentation.dto.admin;

import com.parkio.auth.domain.AuthUserStatus;
import com.parkio.auth.domain.RoleName;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AdminUserSummaryResponse(
        UUID id,
        String email,
        AuthUserStatus status,
        boolean emailVerified,
        List<RoleName> roles,
        Instant createdAt,
        long activeSessionCount) {
}
