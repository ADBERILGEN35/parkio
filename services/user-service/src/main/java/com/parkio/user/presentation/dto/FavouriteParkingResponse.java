package com.parkio.user.presentation.dto;

import com.parkio.user.domain.place.FavouriteParking;
import com.parkio.user.domain.place.FavouriteParkingTargetKind;
import java.time.Instant;
import java.util.UUID;

public record FavouriteParkingResponse(
        UUID id,
        FavouriteParkingTargetKind targetKind,
        UUID targetId,
        Instant createdAt) {

    public static FavouriteParkingResponse from(FavouriteParking favourite) {
        return new FavouriteParkingResponse(
                favourite.id(), favourite.targetKind(), favourite.targetId(), favourite.createdAt());
    }
}
