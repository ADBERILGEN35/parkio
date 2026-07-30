package com.parkio.parking.presentation.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.parkio.parking.application.MunicipalFacilityQueryService;
import com.parkio.parking.externalsource.MunicipalFacilityType;
import com.parkio.parking.externalsource.MunicipalOccupancyFreshness;
import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record MunicipalFacilityResponse(
        UUID id,
        String displayName,
        String operatorName,
        MunicipalFacilityType facilityType,
        String addressText,
        double latitude,
        double longitude,
        Integer capacityTotal,
        Integer availableSpaces,
        MunicipalOccupancyFreshness freshness,
        String attribution,
        String sourceLabel,
        Instant lastUpdatedAt) {
    public static MunicipalFacilityResponse from(MunicipalFacilityQueryService.FacilityView view) {
        return new MunicipalFacilityResponse(view.id(), view.displayName(), view.operatorName(),
                view.facilityType(), view.addressText(), view.latitude(), view.longitude(),
                view.capacityTotal(), view.availableSpaces(), view.freshness(), view.attribution(),
                view.sourceLabel(), view.lastUpdatedAt());
    }
}
