package com.parkio.user.presentation.dto;

import com.parkio.user.application.result.PublicProfileView;
import java.time.Instant;
import java.util.UUID;

/**
 * Public profile of another user. Privacy-safe by construction: no email, phone
 * number or vehicle/plate. Maps 1:1 from {@link PublicProfileView}, which already
 * excludes sensitive data in the application layer.
 *
 * <p>{@code userId} is the platform-wide id (the {@code authUserId}); the internal
 * {@code user_profiles.id} is never exposed.
 */
public record PublicProfileResponse(
        UUID userId,
        String displayName,
        String city,
        String trustBand,
        int currentLevel,
        String status,
        Instant memberSince) {

    public static PublicProfileResponse from(PublicProfileView v) {
        return new PublicProfileResponse(v.userId(), v.displayName(), v.city(),
                v.trustBand().name(), v.currentLevel(), v.status().name(), v.memberSince());
    }
}
