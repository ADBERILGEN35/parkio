package com.parkio.user.presentation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateFavouriteDestinationRequest(
        @NotBlank @Size(max = 512) String label,
        @Size(max = 256) String subtitle) {
}
