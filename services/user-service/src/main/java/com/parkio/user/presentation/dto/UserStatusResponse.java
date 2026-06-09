package com.parkio.user.presentation.dto;

import com.parkio.user.application.result.AccountStatusView;
import java.util.UUID;

/**
 * Internal status response for the gateway's per-request account-status check.
 * Exposes only the platform-wide {@code userId} and the account {@code status} —
 * no private profile data. Not part of the public {@code /api/v1} surface.
 */
public record UserStatusResponse(UUID userId, String status) {

    public static UserStatusResponse from(AccountStatusView view) {
        return new UserStatusResponse(view.userId(), view.status().name());
    }
}
