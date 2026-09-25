package com.parkio.user.presentation.dto;

import com.parkio.user.domain.place.PlaceDestinationSource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpsertSavedPlaceRequest(
        @NotNull @DecimalMin("-90.0") @DecimalMax("90.0") Double latitude,
        @NotNull @DecimalMin("-180.0") @DecimalMax("180.0") Double longitude,
        @Size(max = 512) String label,
        PlaceDestinationSource source,
        @Valid PlaceIdentityRequest placeIdentity,
        @Size(max = 256) String subtitle) {

    public record PlaceIdentityRequest(
            @NotNull @Size(min = 1, max = 64) String provider,
            @NotNull @Size(min = 1, max = 256) String providerPlaceId) {
    }
}
