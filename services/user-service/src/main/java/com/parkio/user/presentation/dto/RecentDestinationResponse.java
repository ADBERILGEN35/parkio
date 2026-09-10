package com.parkio.user.presentation.dto;

import com.parkio.user.domain.place.PlaceDestinationSource;
import com.parkio.user.domain.place.RecentDestination;
import java.time.Instant;
import java.util.UUID;

public record RecentDestinationResponse(
        UUID id,
        String label,
        double latitude,
        double longitude,
        PlaceDestinationSource source,
        FavouriteDestinationResponse.PlaceIdentityResponse placeIdentity,
        String subtitle,
        Instant firstUsedAt,
        Instant lastUsedAt,
        long useCount) {

    public static RecentDestinationResponse from(RecentDestination recent) {
        return new RecentDestinationResponse(
                recent.id(),
                recent.label(),
                recent.latitude(),
                recent.longitude(),
                recent.source(),
                recent.placeIdentityOptional()
                        .map(FavouriteDestinationResponse.PlaceIdentityResponse::from)
                        .orElse(null),
                recent.subtitle(),
                recent.firstUsedAt(),
                recent.lastUsedAt(),
                recent.useCount());
    }
}
