package com.parkio.user.presentation.dto;

import com.parkio.user.domain.UserProfile;
import java.time.Instant;
import java.util.UUID;

/** The caller's own profile (includes private fields the owner may see). */
public record ProfileResponse(
        UUID id,
        UUID authUserId,
        String email,
        String displayName,
        String phoneNumber,
        String city,
        String status,
        Instant createdAt) {

    public static ProfileResponse from(UserProfile p) {
        return new ProfileResponse(p.id(), p.authUserId(), p.email(), p.displayName(),
                p.phoneNumber(), p.city(), p.status().name(), p.createdAt());
    }
}
