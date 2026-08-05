package com.parkio.parking.presentation.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.parking.application.MunicipalFacilityQueryService;
import com.parkio.parking.application.RegistryPublicationService;
import com.parkio.parking.externalsource.MunicipalFacilityType;
import com.parkio.parking.externalsource.MunicipalOccupancyFreshness;
import com.parkio.parking.externalsource.MunicipalSourceIdentity;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MunicipalFacilityResponseTest {
    private final MunicipalFacilityQueryService.FacilityView view = new MunicipalFacilityQueryService.FacilityView(
            UUID.fromString("00000000-0000-0000-0000-000000000101"),
            "Konak Parking", "IZUM", MunicipalFacilityType.OFF_STREET, "Konak", 38.42, 27.14,
            100, 25, 75, MunicipalOccupancyFreshness.LIVE, "Attribution", "IZUM",
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
                        List.of(MunicipalSourceIdentity.IZUM, MunicipalSourceIdentity.OSM),
                        Map.of(
                                "NAME", MunicipalSourceIdentity.IZUM,
                                "COORDINATES", MunicipalSourceIdentity.OSM),
                        null));
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(response);
        JsonNode root = new ObjectMapper().findAndRegisterModules().readTree(json);

        assertThat(response.contributingSourceKeys()).hasSize(2);
        assertThat(response.selectedFieldProvenanceSummary())
                .containsEntry("NAME", MunicipalSourceIdentity.IZUM)
                .containsEntry("COORDINATES", MunicipalSourceIdentity.OSM);
        assertThat(response.registryConfidenceOrReviewStatus()).isNull();
        assertThat(root.get("registryConfidenceOrReviewStatus").isNull()).isTrue();
        assertThat(json).doesNotContain(
                "totalScore", "scoreComponents", "reviewedBy", "rejectionReason",
                "REVIEW_REQUIRED", "PROVENANCE_RECORDED", "confidence", "candidate",
                "source_record_id", "selection_reason");
        assertThat(json).doesNotContain("izelman-open-parking-facilities:CURRENT");
    }

    @Test
    void osmNullAvailabilityRemainsCompatibleWithProvenance() {
        MunicipalFacilityQueryService.FacilityView osmView = new MunicipalFacilityQueryService.FacilityView(
                UUID.fromString("00000000-0000-0000-0000-000000000102"),
                "OSM Lot", null, MunicipalFacilityType.OFF_STREET, "Alsancak", 38.43, 27.15,
                40, null, null, MunicipalOccupancyFreshness.UNAVAILABLE,
                "OpenStreetMap contributors", "OpenStreetMap",
                Instant.parse("2026-07-30T12:00:00Z"));

        MunicipalFacilityResponse response = MunicipalFacilityResponse.from(
                osmView,
                new RegistryPublicationService.Enrichment(
                        List.of(MunicipalSourceIdentity.OSM),
                        Map.of("NAME", MunicipalSourceIdentity.OSM),
                        null));

        assertThat(response.availableSpaces()).isNull();
        assertThat(response.occupiedSpaces()).isNull();
        assertThat(response.availabilitySource()).isNull();
        assertThat(response.selectedFieldProvenanceSummary())
                .containsEntry("NAME", MunicipalSourceIdentity.OSM);
        assertThat(response.id()).isEqualTo(osmView.id());
        assertThat(response.displayName()).isEqualTo("OSM Lot");
    }

    @Test
    void liveOccupancyPublishesOptionalOccupiedSpacesWithoutInventing() {
        MunicipalFacilityResponse response = MunicipalFacilityResponse.from(view);

        assertThat(response.availableSpaces()).isEqualTo(25);
        assertThat(response.occupiedSpaces()).isEqualTo(75);
        assertThat(response.capacityTotal()).isEqualTo(100);
    }
}
