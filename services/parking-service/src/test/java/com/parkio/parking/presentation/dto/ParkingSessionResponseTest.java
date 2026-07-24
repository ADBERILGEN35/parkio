package com.parkio.parking.presentation.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.parking.domain.ParkingSession;
import com.parkio.parking.domain.ParkingSource;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;

class ParkingSessionResponseTest {

    private static final Instant STARTED_AT = Instant.parse("2026-07-21T09:00:00Z");

    @Test
    void exposesOnlyFrozenPublicFieldsAndNormalizesFee() throws Exception {
        ParkingSession session = ParkingSession.start(
                java.util.UUID.randomUUID(),
                ParkingSource.MANUAL,
                41.0082,
                28.9784,
                new BigDecimal("1.2"),
                null,
                STARTED_AT);

        ParkingSessionResponse response = ParkingSessionResponse.from(session);
        JsonNode json = new ObjectMapper().findAndRegisterModules().valueToTree(response);
        Set<String> fieldNames = StreamSupport.stream(
                        ((Iterable<String>) () -> json.fieldNames()).spliterator(), false)
                .collect(Collectors.toSet());

        assertThat(fieldNames).containsExactlyInAnyOrder(
                "id", "status", "parkingSource", "startedAt", "endedAt",
                "latitude", "longitude", "estimatedFee");
        assertThat(json.path("estimatedFee").asText()).isEqualTo("1.20");
        assertThat(json.path("endedAt").isNull()).isTrue();
        assertThat(response.status()).isEqualTo("ACTIVE");
    }

    @Test
    void terminalResponseIncludesEndedAtAndNullFeeRemainsNull() {
        ParkingSession session = ParkingSession.start(
                java.util.UUID.randomUUID(), ParkingSource.MANUAL,
                41.0082, 28.9784, null, null, STARTED_AT);
        session.complete(STARTED_AT.plusSeconds(60));

        ParkingSessionResponse response = ParkingSessionResponse.from(session);

        assertThat(response.status()).isEqualTo("COMPLETED");
        assertThat(response.endedAt()).isEqualTo(STARTED_AT.plusSeconds(60));
        assertThat(response.estimatedFee()).isNull();
    }
}
