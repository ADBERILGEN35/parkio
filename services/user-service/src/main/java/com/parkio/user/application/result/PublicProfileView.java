package com.parkio.user.application.result;

import com.parkio.user.domain.TrustBand;
import com.parkio.user.domain.UserProfile;
import com.parkio.user.domain.UserTrustProfile;
import java.time.Instant;
import java.util.UUID;

/**
 * Public, privacy-safe view of a user. Privacy is enforced here in the
 * application layer: it deliberately excludes email, phone number, any
 * vehicle/plate data and the account's moderation status (visible only to the
 * owner via {@code /users/me} and to moderators), exposing only non-sensitive,
 * public fields.
 *
 * <p>{@code userId} is the platform-wide id — the {@code authUserId}. The internal
 * {@code user_profiles.id} is never exposed outside this service.
 */
public record PublicProfileView(
        UUID userId,
        String displayName,
        String city,
        TrustBand trustBand,
        int currentLevel,
        Instant memberSince) {

    public static PublicProfileView of(UserProfile profile, UserTrustProfile trust) {
        return new PublicProfileView(
                profile.authUserId(),
                profile.displayName(),
                profile.city(),
                trust.trustBand(),
                trust.currentLevel(),
                profile.createdAt());
    }
}
