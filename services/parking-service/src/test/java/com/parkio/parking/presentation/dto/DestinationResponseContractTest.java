package com.parkio.parking.presentation.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.parking.domain.place.Destination;
import com.parkio.parking.domain.place.DestinationSource;
import com.parkio.parking.domain.place.PlaceIdentity;
import org.junit.jupiter.api.Test;

class DestinationResponseContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void serializesDestinationWithOptionalIdentity() throws Exception {
        Destination destination = Destination.of(
                "Forum Bornova",
                38.4501,
                27.2112,
                DestinationSource.GEOCODING,
                PlaceIdentity.osmNominatim("314159"),
                "Bornova, İzmir");

        DestinationResponse response = DestinationResponse.from(destination);
        String json = objectMapper.writeValueAsString(response);
        JsonNode node = objectMapper.readTree(json);

        assertThat(node.get("label").asText()).isEqualTo("Forum Bornova");
        assertThat(node.get("latitude").asDouble()).isEqualTo(38.4501);
        assertThat(node.get("longitude").asDouble()).isEqualTo(27.2112);
        assertThat(node.get("source").asText()).isEqualTo("GEOCODING");
        assertThat(node.get("subtitle").asText()).isEqualTo("Bornova, İzmir");
        assertThat(node.get("placeIdentity").get("provider").asText()).isEqualTo("osm-nominatim");
        assertThat(node.get("placeIdentity").get("providerPlaceId").asText()).isEqualTo("314159");
        assertThat(node.get("placeIdentity").get("canonicalKey").asText())
                .isEqualTo("osm-nominatim:314159");
    }

    @Test
    void serializesNullPlaceIdentityWhenAbsent() throws Exception {
        Destination destination = Destination.mapPin("Pin", 41.0, 29.0);
        String json = objectMapper.writeValueAsString(DestinationResponse.from(destination));
        JsonNode node = objectMapper.readTree(json);

        assertThat(node.get("placeIdentity").isNull()).isTrue();
        assertThat(node.get("source").asText()).isEqualTo("MAP_PIN");
        assertThat(node.has("score")).isFalse();
        assertThat(node.has("availability")).isFalse();
        assertThat(node.has("favourite")).isFalse();
    }

    @Test
    void geocodeResultResponseFieldsRemainUnchanged() throws Exception {
        // Compatibility: existing geocoding wire shape is untouched by DestinationResponse.
        GeocodeResultResponse legacy = new GeocodeResultResponse(
                "1", "Full", "Primary", "Secondary", 38.0, 27.0);
        JsonNode node = objectMapper.readTree(objectMapper.writeValueAsString(legacy));

        assertThat(node.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("id", "displayName", "primary", "secondary", "lat", "lng");
    }
}
