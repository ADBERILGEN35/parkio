package com.parkio.user.application.result;

import com.parkio.user.domain.UserProfile;
import com.parkio.user.domain.UserStatus;
import java.util.UUID;

/**
 * Minimal account-status projection for the internal gateway status check. Carries
 * only the platform-wide {@code userId} (the {@code authUserId}) and the account
 * {@link UserStatus} — deliberately no profile data (display name, email, phone,
 * city, vehicle, trust), so the internal endpoint cannot leak anything sensitive.
 */
public record AccountStatusView(UUID userId, UserStatus status) {

    public static AccountStatusView of(UserProfile profile) {
        return new AccountStatusView(profile.authUserId(), profile.status());
    }
}
