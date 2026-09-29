package com.parkio.parking.presentation.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.parking.application.PublicExploreQueryService;
import com.parkio.parking.externalsource.MunicipalAccessClassification;
import com.parkio.parking.externalsource.MunicipalFacilityType;
import com.parkio.parking.externalsource.MunicipalOccupancyFreshness;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class PublicExploreFacilityResponseTest {
    private static final Set<String> EXACT_FIELDS = Set.of(
            "id", "displayName", "operatorName", "facilityType", "addressText",
            "latitude", "longitude", "capacityTotal", "availableSpaces",
            "availabilityFreshness", "dataUpdatedAt", "sourceLabel", "attribution",
            "accessClassification");

    @Test
    void serializedContractIsTheExactReviewedAllowlist() throws Exception {
        var response = PublicExploreFacilityMapper.from(view("IZUM", "CC BY 4.0"));
        var json = new ObjectMapper().findAndRegisterModules().readTree(
                new ObjectMapper().findAndRegisterModules().writeValueAsString(response));
        Set<String> fields = java.util.stream.StreamSupport.stream(
                        ((Iterable<String>) () -> json.fieldNames()).spliterator(), false)
                .collect(Collectors.toSet());
        Set<String> reflected = java.util.Arrays.stream(response.getClass().getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .collect(Collectors.toSet());

        assertThat(fields).isEqualTo(EXACT_FIELDS);
        assertThat(reflected).isEqualTo(EXACT_FIELDS);
        assertThat(fields).doesNotContain(
                "contributingSourceKeys", "selectedFieldProvenanceSummary",
                "registryConfidenceOrReviewStatus", "externalSourceId", "sourceKey",
                "raw", "debug", "user", "ranking", "occupiedSpaces",
                "availabilitySource", "availabilityObservationTimestamp");
    }

    @Test
    void missingHumanReadableAttributionFailsMapping() {
        assertThatThrownBy(() -> PublicExploreFacilityMapper.from(view("IZUM", " ")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("attribution");
    }

    @Test
    void closedOccupancySerializesUnavailableAndNullAvailableSpaces() throws Exception {
        var response = PublicExploreFacilityMapper.from(new PublicExploreQueryService.FacilityView(
                UUID.fromString("81279bd3-5c60-42a1-81bc-8255e22a1a48"),
                "Avcılar İdo", "İSPARK", MunicipalFacilityType.OFF_STREET, "AVCILAR",
                40.9712, 28.7185, 270, null,
                MunicipalOccupancyFreshness.UNAVAILABLE, Instant.parse("2026-09-25T07:14:19Z"),
                "İstanbul Büyükşehir Belediyesi / İSPARK",
                "Includes public sector information from Istanbul Buyuksehir Belediyesi.",
                MunicipalAccessClassification.PUBLIC));
        var json = new ObjectMapper().findAndRegisterModules().readTree(
                new ObjectMapper().findAndRegisterModules().writeValueAsString(response));

        assertThat(json.get("availabilityFreshness").asText()).isEqualTo("UNAVAILABLE");
        assertThat(json.get("availableSpaces").isNull()).isTrue();
        assertThat(json.has("availableSpaces")).isTrue();
        assertThat(json.get("capacityTotal").asInt()).isEqualTo(270);
        assertThat(json.get("id").asText()).isEqualTo("81279bd3-5c60-42a1-81bc-8255e22a1a48");
    }

    private static PublicExploreQueryService.FacilityView view(String label, String attribution) {
        return new PublicExploreQueryService.FacilityView(
                UUID.fromString("00000000-0000-0000-0000-000000000901"),
                "Konak Otopark", "IZELMAN A.S.", MunicipalFacilityType.OFF_STREET,
                "Konak, Izmir", 38.4237, 27.1428, 100, 42,
                MunicipalOccupancyFreshness.LIVE, Instant.parse("2026-09-04T10:00:00Z"),
                label, attribution, MunicipalAccessClassification.PUBLIC);
    }
}
