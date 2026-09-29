package com.parkio.user.presentation.dto;

import com.parkio.user.domain.place.RecentParking;
import com.parkio.user.domain.place.RecentParkingTargetKind;
import java.time.Instant;
import java.util.UUID;

public record RecentParkingResponse(
        UUID id,
        RecentParkingTargetKind targetKind,
        UUID targetId,
        Instant firstUsedAt,
        Instant lastUsedAt,
        long useCount) {

    public static RecentParkingResponse from(RecentParking recent) {
        return new RecentParkingResponse(
                recent.id(),
                recent.targetKind(),
                recent.targetId(),
                recent.firstUsedAt(),
                recent.lastUsedAt(),
                recent.useCount());
    }
}
