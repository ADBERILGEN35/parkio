package com.parkio.parking.infrastructure.ispark;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import java.io.IOException;

/** Accepts numeric or string coordinate values from İSPARK JSON. */
final class IsparkFlexibleDoubleDeserializer extends JsonDeserializer<Double> {
    @Override
    public Double deserialize(JsonParser parser, DeserializationContext ctxt) throws IOException {
        if (parser.currentToken() == null || parser.getCurrentToken().isNumeric()) {
            return parser.getValueAsDouble();
        }
        String text = parser.getValueAsString();
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(text.trim().replace(',', '.'));
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
