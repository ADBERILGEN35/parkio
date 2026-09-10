package com.parkio.user.presentation.dto;

import com.parkio.user.domain.place.FavouriteParkingTargetKind;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record CreateFavouriteParkingRequest(
        FavouriteParkingTargetKind targetKind,
        @NotNull UUID targetId) {
}
