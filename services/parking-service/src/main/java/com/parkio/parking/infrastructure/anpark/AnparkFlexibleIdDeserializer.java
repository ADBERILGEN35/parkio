package com.parkio.parking.infrastructure.anpark;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import java.io.IOException;

/** Accepts numeric or string stable facility IDs from ANPARK JSON. */
final class AnparkFlexibleIdDeserializer extends JsonDeserializer<String> {
    @Override
    public String deserialize(JsonParser parser, DeserializationContext ctxt) throws IOException {
        if (parser.currentToken() != null && parser.getCurrentToken().isNumeric()) {
            return parser.getValueAsString();
        }
        String text = parser.getValueAsString();
        if (text == null || text.isBlank()) {
            return null;
        }
        return text.trim();
    }
}
