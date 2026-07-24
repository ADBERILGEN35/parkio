package com.parkio.parking.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.parking.application.ParkingSessionHistoryCursor;
import com.parkio.parking.domain.exception.ParkingErrorCode;
import com.parkio.parking.domain.exception.ParkingException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ParkingSessionHistoryCursorCodecTest {

    private static final Instant STARTED_AT = Instant.parse("2026-07-21T09:00:00Z");
    private static final UUID ID = UUID.fromString("f0000000-0000-0000-0000-000000000001");

    private ParkingSessionHistoryCursorCodec codec;

    @BeforeEach
    void setUp() {
        codec = new ParkingSessionHistoryCursorCodec(new ObjectMapper());
    }

    @Test
    void roundTripsVersionedCursorWithoutPadding() {
        ParkingSessionHistoryCursor cursor = new ParkingSessionHistoryCursor(STARTED_AT, ID);

        String encoded = codec.encode(cursor);

        assertThat(encoded).doesNotContain("=");
        assertThat(codec.decode(encoded)).isEqualTo(cursor);
    }

    @Test
    void rejectsUnsupportedVersionAndAdditionalFields() {
        assertInvalid(jsonCursor("{\"v\":2,\"startedAt\":\"2026-07-21T09:00:00Z\",\"id\":\""
                + ID + "\"}"));
        assertInvalid(jsonCursor("{\"v\":1,\"startedAt\":\"2026-07-21T09:00:00Z\",\"id\":\""
                + ID + "\",\"offset\":1}"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            " ",
            "not+base64",
            "eyJ2IjoxfQ=="
    })
    void rejectsInvalidEncoding(String cursor) {
        assertInvalid(cursor);
    }

    @Test
    void rejectsOversizedMalformedAndIncompletePayloads() {
        assertInvalid("a".repeat(ParkingSessionHistoryCursorCodec.MAX_ENCODED_LENGTH + 1));
        assertInvalid(jsonCursor("not-json"));
        assertInvalid(jsonCursor("{\"v\":1,\"startedAt\":\"2026-07-21T09:00:00Z\"}"));
        assertInvalid(jsonCursor("{\"v\":1,\"startedAt\":\"invalid\",\"id\":\"" + ID + "\"}"));
        assertInvalid(jsonCursor("{\"v\":1,\"startedAt\":\"2026-07-21T09:00:00Z\",\"id\":\"invalid\"}"));
    }

    private void assertInvalid(String cursor) {
        assertThatThrownBy(() -> codec.decode(cursor))
                .isInstanceOf(ParkingException.class)
                .extracting(exception -> ((ParkingException) exception).errorCode())
                .isEqualTo(ParkingErrorCode.INVALID_PARKING_SESSION_CURSOR);
    }

    private static String jsonCursor(String json) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}
