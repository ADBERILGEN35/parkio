package com.parkio.parking.presentation.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import java.io.IOException;
import java.util.Iterator;
import java.util.Set;

/** Enforces the frozen JSON types and rejects client-controlled aggregate fields. */
final class StartParkingSessionRequestDeserializer extends StdDeserializer<StartParkingSessionRequest> {

    private static final Set<String> ALLOWED_FIELDS =
            Set.of("latitude", "longitude", "estimatedFee");

    StartParkingSessionRequestDeserializer() {
        super(StartParkingSessionRequest.class);
    }

    @Override
    public StartParkingSessionRequest deserialize(JsonParser parser, DeserializationContext context)
            throws IOException {
        JsonNode root = parser.getCodec().readTree(parser);
        if (!root.isObject()) {
            throw JsonMappingException.from(parser, "Parking session request must be a JSON object");
        }
        Iterator<String> fieldNames = root.fieldNames();
        while (fieldNames.hasNext()) {
            String fieldName = fieldNames.next();
            if (!ALLOWED_FIELDS.contains(fieldName)) {
                throw JsonMappingException.from(
                        parser, "Unsupported parking session request field: " + fieldName);
            }
        }

        return new StartParkingSessionRequest(
                number(root, "latitude", parser),
                number(root, "longitude", parser),
                decimalString(root, "estimatedFee", parser));
    }

    private static Double number(JsonNode root, String fieldName, JsonParser parser)
            throws JsonMappingException {
        JsonNode value = root.get(fieldName);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isNumber()) {
            throw JsonMappingException.from(parser, fieldName + " must be a JSON number");
        }
        return value.doubleValue();
    }

    private static String decimalString(JsonNode root, String fieldName, JsonParser parser)
            throws JsonMappingException {
        JsonNode value = root.get(fieldName);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw JsonMappingException.from(parser, fieldName + " must be a JSON string or null");
        }
        return value.textValue();
    }
}
