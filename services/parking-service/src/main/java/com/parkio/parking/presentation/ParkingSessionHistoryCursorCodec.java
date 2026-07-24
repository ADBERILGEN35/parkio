package com.parkio.parking.presentation;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.parkio.parking.application.ParkingSessionHistoryCursor;
import com.parkio.parking.domain.exception.ParkingErrorCode;
import com.parkio.parking.domain.exception.ParkingException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Encodes and validates the versioned opaque token used by the history HTTP contract. */
@Component
public class ParkingSessionHistoryCursorCodec {

    public static final int MAX_ENCODED_LENGTH = 512;

    private static final int CURRENT_VERSION = 1;
    private static final Pattern BASE64_URL_WITHOUT_PADDING =
            Pattern.compile("^[A-Za-z0-9_-]+$");

    private final ObjectMapper objectMapper;
    private final ObjectReader cursorReader;

    public ParkingSessionHistoryCursorCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.cursorReader = objectMapper.readerFor(CursorPayload.class)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    public String encode(ParkingSessionHistoryCursor cursor) {
        try {
            byte[] payload = objectMapper.writeValueAsBytes(new CursorPayload(
                    CURRENT_VERSION, cursor.startedAt().toString(), cursor.id().toString()));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(payload);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to encode parking session history cursor", exception);
        }
    }

    public ParkingSessionHistoryCursor decode(String encodedCursor) {
        try {
            if (encodedCursor == null
                    || encodedCursor.isBlank()
                    || encodedCursor.length() > MAX_ENCODED_LENGTH
                    || !BASE64_URL_WITHOUT_PADDING.matcher(encodedCursor).matches()) {
                throw new IllegalArgumentException("Invalid cursor encoding");
            }

            byte[] decoded = Base64.getUrlDecoder().decode(encodedCursor);
            String canonicalEncoding = Base64.getUrlEncoder().withoutPadding().encodeToString(decoded);
            if (!canonicalEncoding.equals(encodedCursor)) {
                throw new IllegalArgumentException("Non-canonical cursor encoding");
            }

            String json = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(decoded))
                    .toString();
            CursorPayload payload = cursorReader.readValue(json);
            if (payload == null
                    || payload.v() == null
                    || payload.v() != CURRENT_VERSION
                    || payload.startedAt() == null
                    || payload.id() == null) {
                throw new IllegalArgumentException("Unsupported or incomplete cursor");
            }
            return new ParkingSessionHistoryCursor(
                    Instant.parse(payload.startedAt()), UUID.fromString(payload.id()));
        } catch (IOException | IllegalArgumentException | DateTimeException exception) {
            throw invalidCursor();
        }
    }

    private static ParkingException invalidCursor() {
        return new ParkingException(
                ParkingErrorCode.INVALID_PARKING_SESSION_CURSOR,
                "Parking session history cursor is invalid.");
    }

    @JsonPropertyOrder({"v", "startedAt", "id"})
    private record CursorPayload(Integer v, String startedAt, String id) {
    }
}
