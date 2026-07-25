package com.parkio.parking.presentation.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.parkio.parking.domain.ParkingSession;
import com.parkio.parking.domain.ParkingSessionCompletionType;
import com.parkio.parking.domain.ParkingSource;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;

class ParkingSessionResponseTest {

    private static final Instant STARTED_AT = Instant.parse("2026-07-21T09:00:00Z");
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

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
        JsonNode json = MAPPER.valueToTree(response);
        Set<String> fieldNames = StreamSupport.stream(
                        ((Iterable<String>) () -> json.fieldNames()).spliterator(), false)
                .collect(Collectors.toSet());

        assertThat(fieldNames).containsExactlyInAnyOrder(
                "id", "status", "parkingSource", "startedAt", "endedAt",
                "latitude", "longitude", "estimatedFee", "lastConfirmedAt", "completionType");
        assertThat(json.path("estimatedFee").asText()).isEqualTo("1.20");
        assertThat(json.path("endedAt").isNull()).isTrue();
        assertThat(json.path("lastConfirmedAt").asText()).isEqualTo("2026-07-21T09:00:00Z");
        assertThat(json.path("completionType").isNull()).isTrue();
        assertThat(response.status()).isEqualTo("ACTIVE");
    }

    @Test
    void terminalResponseIncludesEndedAtCompletionTypeAndNullFeeRemainsNull() {
        ParkingSession session = ParkingSession.start(
                java.util.UUID.randomUUID(), ParkingSource.MANUAL,
                41.0082, 28.9784, null, null, STARTED_AT);
        session.complete(STARTED_AT.plusSeconds(60), ParkingSessionCompletionType.MANUAL);

        ParkingSessionResponse response = ParkingSessionResponse.from(session);

        assertThat(response.status()).isEqualTo("COMPLETED");
        assertThat(response.endedAt()).isEqualTo(STARTED_AT.plusSeconds(60));
        assertThat(response.estimatedFee()).isNull();
        assertThat(response.completionType()).isEqualTo("MANUAL");
        assertThat(response.lastConfirmedAt()).isEqualTo(STARTED_AT);
    }
}
