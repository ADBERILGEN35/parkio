package com.parkio.parking.presentation.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.parking.application.MunicipalFacilityQueryService;
import com.parkio.parking.application.RegistryPublicationService;
import com.parkio.parking.externalsource.MunicipalFacilityType;
import com.parkio.parking.externalsource.MunicipalOccupancyFreshness;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MunicipalFacilityResponseTest {
    private final MunicipalFacilityQueryService.FacilityView view = new MunicipalFacilityQueryService.FacilityView(
            UUID.fromString("00000000-0000-0000-0000-000000000101"),
            "Konak Parking", "IZUM", MunicipalFacilityType.OFF_STREET, "Konak", 38.42, 27.14,
            100, 25, MunicipalOccupancyFreshness.LIVE, "Attribution", "IZUM",
            Instant.parse("2026-07-30T12:00:00Z"));

    @Test
    void provenanceDisabledKeepsAdditiveRegistryFieldsNull() {
        MunicipalFacilityResponse response = MunicipalFacilityResponse.from(
                view, RegistryPublicationService.Enrichment.hidden());

        assertThat(response.contributingSourceKeys()).isNull();
        assertThat(response.selectedFieldProvenanceSummary()).isNull();
        assertThat(response.registryConfidenceOrReviewStatus()).isNull();
    }

    @Test
    void publishedEnrichmentExposesOnlyBoundedProvenanceNotScoresOrReviewNotes() throws Exception {
        MunicipalFacilityResponse response = MunicipalFacilityResponse.from(
                view,
                new RegistryPublicationService.Enrichment(
                        List.of("izmir-izum-otoparklar", "osm-geofabrik-turkey"),
                        Map.of("NAME", "izmir-izum-otoparklar:CURRENT"),
                        "PROVENANCE_RECORDED"));
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(response);

        assertThat(response.contributingSourceKeys()).hasSize(2);
        assertThat(response.selectedFieldProvenanceSummary()).containsKey("NAME");
        assertThat(json).doesNotContain("totalScore", "scoreComponents", "reviewedBy", "rejectionReason");
    }
}