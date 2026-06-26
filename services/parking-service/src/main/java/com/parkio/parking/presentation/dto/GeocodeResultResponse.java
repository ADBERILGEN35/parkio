package com.parkio.parking.presentation.dto;

import com.parkio.parking.application.geocoding.GeocodeResult;

/**
 * Wire shape for a single geocoding candidate. Field names and types match the
 * frontend's existing {@code GeocodeResult} contract exactly.
 */
public record GeocodeResultResponse(
        String id,
        String displayName,
        String primary,
        String secondary,
        double lat,
        double lng) {

    public static GeocodeResultResponse from(GeocodeResult result) {
        return new GeocodeResultResponse(
                result.id(),
                result.displayName(),
                result.primary(),
                result.secondary(),
                result.lat(),
                result.lng());
    }
}
