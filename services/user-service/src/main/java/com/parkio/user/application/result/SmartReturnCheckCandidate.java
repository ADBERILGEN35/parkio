package com.parkio.user.application.result;

import java.time.Instant;
import java.util.UUID;

public record SmartReturnCheckCandidate(
        UUID userId,
        double homeLatitude,
        double homeLongitude,
        String homeLabel,
        int radiusMeters,
        Instant expectedReturnAt,
        boolean claimRetried) {
}
