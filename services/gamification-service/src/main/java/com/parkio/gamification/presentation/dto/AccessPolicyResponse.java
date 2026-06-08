package com.parkio.gamification.presentation.dto;

import com.parkio.gamification.domain.AccessPolicy;
import java.util.UUID;

/** The access policy granted by a user's current level. */
public record AccessPolicyResponse(
        UUID userId,
        int currentLevel,
        int searchRadiusMeters,
        int resultLimit,
        int dailyViewLimit,
        boolean verifiedSpotPriority,
        boolean notificationPriority) {

    public static AccessPolicyResponse from(AccessPolicy p) {
        return new AccessPolicyResponse(p.userId(), p.currentLevel(), p.searchRadiusMeters(),
                p.resultLimit(), p.dailyViewLimit(), p.verifiedSpotPriority(), p.notificationPriority());
    }
}
