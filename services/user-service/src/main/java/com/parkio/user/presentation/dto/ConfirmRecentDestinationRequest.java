package com.parkio.user.presentation.dto;

import com.parkio.user.domain.place.PlaceDestinationSource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Confirm a canonical Destination into recent destinations (WP-SPA-07). */
public record ConfirmRecentDestinationRequest(
        @NotBlank @Size(max = 512) String label,
        @NotNull @DecimalMin("-90.0") @DecimalMax("90.0") Double latitude,
        @NotNull @DecimalMin("-180.0") @DecimalMax("180.0") Double longitude,
        PlaceDestinationSource source,
        @Valid UpsertSavedPlaceRequest.PlaceIdentityRequest placeIdentity,
        @Size(max = 256) String subtitle) {
}
