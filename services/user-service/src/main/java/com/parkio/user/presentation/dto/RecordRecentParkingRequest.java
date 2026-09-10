package com.parkio.user.presentation.dto;

import com.parkio.user.domain.place.RecentParkingTargetKind;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record RecordRecentParkingRequest(
        RecentParkingTargetKind targetKind,
        @NotNull UUID targetId) {
}
