package com.parkio.parking.presentation.dto;

import com.parkio.parking.externalsource.MunicipalOccupancyFreshness;
import java.time.Instant;
import java.util.UUID;

/** Exact anonymous v1 allowlist. Adding a field requires explicit contract review. */
public record PublicExploreFacilityResponse(
        UUID id,
        String displayName,
        String operatorName,
        String facilityType,
        String addressText,
        double latitude,
        double longitude,
        Integer capacityTotal,
        Integer availableSpaces,
        MunicipalOccupancyFreshness availabilityFreshness,
        Instant dataUpdatedAt,
        String sourceLabel,
        String attribution) {}
