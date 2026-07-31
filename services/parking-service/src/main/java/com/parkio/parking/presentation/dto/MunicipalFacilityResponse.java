package com.parkio.parking.presentation.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.parkio.parking.application.MunicipalFacilityQueryService;
import com.parkio.parking.application.RegistryPublicationService;
import com.parkio.parking.externalsource.MunicipalFacilityType;
import com.parkio.parking.externalsource.MunicipalOccupancyFreshness;
import com.parkio.parking.externalsource.MunicipalSourceIdentity;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
        Instant lastUpdatedAt,
        List<String> contributingSourceKeys,
        Map<String, String> selectedFieldProvenanceSummary,
        /** Retained for JSON compatibility; DATA-WP-09 never publishes a value (always null). */
        String registryConfidenceOrReviewStatus,
        String availabilitySource,
        MunicipalOccupancyFreshness availabilityFreshness,
        Instant availabilityObservationTimestamp) {

    public static MunicipalFacilityResponse from(MunicipalFacilityQueryService.FacilityView view) {
        return from(view, RegistryPublicationService.Enrichment.hidden());
    }

    public static MunicipalFacilityResponse from(
            MunicipalFacilityQueryService.FacilityView view,
            RegistryPublicationService.Enrichment enrichment) {
        boolean hasAvailability = view.availableSpaces() != null;
        return new MunicipalFacilityResponse(
                view.id(),
                view.displayName(),
                view.operatorName(),
                view.facilityType(),
                view.addressText(),
                view.latitude(),
                view.longitude(),
                view.capacityTotal(),
                view.availableSpaces(),
                view.freshness(),
                view.attribution(),
                view.sourceLabel(),
                view.lastUpdatedAt(),
                enrichment.contributingSourceKeys(),
                enrichment.selectedFieldProvenanceSummary(),
                enrichment.registryConfidenceOrReviewStatus(),
                hasAvailability ? MunicipalSourceIdentity.IZUM : null,
                hasAvailability ? view.freshness() : null,
                hasAvailability ? view.lastUpdatedAt() : null);
    }
}
