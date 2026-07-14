package com.parkio.auth.application.admin;

import com.parkio.auth.domain.AuthUserStatus;
import com.parkio.auth.domain.RoleName;
import java.time.Instant;
import java.util.UUID;

public record AdminUserSearchQuery(
        String q,
        String emailContains,
        UUID userId,
        AuthUserStatus status,
        Boolean emailVerified,
        RoleName roleName,
        Instant createdFrom,
        Instant createdTo,
        int page,
        int size,
        String sort) {

    private static final int MAX_PAGE_SIZE = 100;

    public AdminUserSearchQuery {
        page = Math.max(page, 0);
        size = size < 1 ? 20 : Math.min(size, MAX_PAGE_SIZE);
    }
}
