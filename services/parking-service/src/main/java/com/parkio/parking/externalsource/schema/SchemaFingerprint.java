package com.parkio.parking.externalsource.schema;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** Stable SHA-256 over sorted top-level record keys. */
public record SchemaFingerprint(String value, List<String> fields) {
    public static SchemaFingerprint fromArray(JsonNode payload) {
        if (payload == null || !payload.isArray() || payload.isEmpty() || !payload.get(0).isObject()) {
            throw new IllegalArgumentException("Expected a non-empty JSON array");
        }
        List<String> fields = new ArrayList<>();
        payload.get(0).fieldNames().forEachRemaining(fields::add);
        fields.sort(String::compareTo);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(String.join(",", fields).getBytes(StandardCharsets.UTF_8));
            return new SchemaFingerprint(HexFormat.of().formatHex(digest), List.copyOf(fields));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
