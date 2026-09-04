package com.parkio.parking.presentation.dto;

import com.parkio.parking.application.PublicExploreQueryService;

/** Deliberate mapping boundary between internal facility views and anonymous JSON. */
public final class PublicExploreFacilityMapper {
    private PublicExploreFacilityMapper() {}

    public static PublicExploreFacilityResponse from(PublicExploreQueryService.FacilityView view) {
        if (view.sourceLabel() == null || view.sourceLabel().isBlank()
                || view.attribution() == null || view.attribution().isBlank()) {
            throw new IllegalStateException("Public explore attribution is required");
        }
        return new PublicExploreFacilityResponse(
                view.id(),
                view.displayName(),
                view.operatorName(),
                view.facilityType().name(),
                view.addressText(),
                view.latitude(),
                view.longitude(),
                view.capacityTotal(),
                view.availableSpaces(),
                view.availabilityFreshness(),
                view.dataUpdatedAt(),
                view.sourceLabel(),
                view.attribution());
    }
}
