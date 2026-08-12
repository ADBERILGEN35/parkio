package com.parkio.parking.infrastructure.kayseri;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import java.io.IOException;
import java.math.BigDecimal;

/** Accepts CBNO as integer, decimal (2723.0), or string. */
final class KayseriFlexibleIdDeserializer extends JsonDeserializer<String> {
    @Override
    public String deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (parser.currentToken() == null || parser.getCurrentToken().isNumeric()) {
            BigDecimal value = parser.getDecimalValue();
            if (value == null) {
                return null;
            }
            return value.stripTrailingZeros().toPlainString();
        }
        String text = parser.getValueAsString();
        if (text == null || text.isBlank()) {
            return null;
        }
        text = text.trim();
        try {
            return new BigDecimal(text).stripTrailingZeros().toPlainString();
        } catch (NumberFormatException ex) {
            return text;
        }
    }
}
