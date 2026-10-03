package com.parkio.auth.application.durable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Pre-distributed trust for durable erasure evidence (format v2): the pinned database identity
 * ({@code postgresql:<system_identifier>:<datname>}) and the producer keys. It comes from the
 * consumer's own configuration, never from the evidence. A trust document holds HMAC secrets,
 * so it is secret material; parse errors name the field and key id, never a key value. The
 * Python model ({@code EvidenceTrust} in {@code scripts/lib/recovery_persist_protocol.py})
 * accepts and refuses the same documents with the same messages.
 */
public final class EvidenceTrust {

    public static final String FORMAT = "parkio-erasure-evidence-trust";
    public static final int VERSION = 1;
    public static final int MIN_KEY_BYTES = 32;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String databaseIdentity;
    private final Map<String, TrustedKey> keys;

    public EvidenceTrust(String databaseIdentity, Collection<TrustedKey> keys) {
        if (databaseIdentity == null || databaseIdentity.isBlank()) {
            throw new DurableEvidenceException("trust databaseIdentity must not be blank");
        }
        Map<String, TrustedKey> byId = new LinkedHashMap<>();
        for (TrustedKey key : keys) {
            if (byId.put(key.keyId(), key) != null) {
                throw new DurableEvidenceException("duplicate keyId " + key.keyId() + " in trust");
            }
        }
        this.databaseIdentity = databaseIdentity;
        this.keys = Map.copyOf(byId);
    }

    /** Parses a trust document (JSON). */
    public static EvidenceTrust parse(byte[] document) {
        JsonNode root;
        try {
            root = JSON.readTree(document);
        } catch (IOException ex) {
            throw new DurableEvidenceException("trust document is not JSON");
        }
        if (root == null || !root.isObject() || !FORMAT.equals(root.path("format").textValue())) {
            throw new DurableEvidenceException("trust document format must be " + FORMAT);
        }
        JsonNode version = root.path("version");
        if (!version.isIntegralNumber() || version.longValue() != VERSION) {
            throw new DurableEvidenceException("trust document version must be " + VERSION);
        }
        JsonNode entries = root.path("keys");
        if (!entries.isArray() || entries.isEmpty()) {
            throw new DurableEvidenceException("trust document needs at least one key");
        }
        List<TrustedKey> keys = new ArrayList<>();
        for (int index = 0; index < entries.size(); index++) {
            keys.add(key(entries.get(index), index));
        }
        JsonNode identity = root.path("databaseIdentity");
        return new EvidenceTrust(identity.isTextual() ? identity.textValue() : null, keys);
    }

    public String databaseIdentity() {
        return databaseIdentity;
    }

    public Optional<TrustedKey> key(String keyId) {
        return Optional.ofNullable(keys.get(keyId));
    }

    /**
     * The trusted key that may have signed {@code body} as seen at {@code at}: the object's
     * {@code keyId} must be known, belong to the object's {@code producerId}, not be retired and
     * not be used before its {@code notBefore}.
     */
    public TrustedKey verifyingKey(Map<String, Object> body, Instant at) {
        Object keyId = body.get("keyId");
        TrustedKey key = keyId instanceof String id ? keys.get(id) : null;
        if (key == null) {
            throw new DurableEvidenceException("unknown producer key");
        }
        if (!key.producerId().equals(body.get("producerId"))) {
            throw new DurableEvidenceException("producer key belongs to another producer");
        }
        if (key.retired()) {
            throw new DurableEvidenceException("retired producer key");
        }
        if (at.isBefore(key.notBefore())) {
            throw new DurableEvidenceException("producer key not yet valid");
        }
        return key;
    }

    private static TrustedKey key(JsonNode entry, int index) {
        if (!entry.isObject()) {
            throw new DurableEvidenceException("trust key #" + index + " must be an object");
        }
        String keyId = text(entry.path("keyId"));
        if (keyId == null || keyId.isBlank()) {
            throw new DurableEvidenceException("trust key #" + index + " needs a keyId");
        }
        String producerId = text(entry.path("producerId"));
        if (producerId == null || producerId.isBlank()) {
            throw new DurableEvidenceException("trust key " + keyId + " needs a producerId");
        }
        JsonNode hex = entry.path("keyHex");
        byte[] secret;
        try {
            if (!hex.isMissingNode() && !hex.isNull() && !hex.isTextual()) {
                throw new IllegalArgumentException("not text");
            }
            secret = HexFormat.of().parseHex(hex.isTextual() ? hex.textValue() : "");
        } catch (IllegalArgumentException ex) {
            throw new DurableEvidenceException("trust key " + keyId + " keyHex must be hex");
        }
        if (secret.length < MIN_KEY_BYTES) {
            throw new DurableEvidenceException("trust key " + keyId + " must be at least " + MIN_KEY_BYTES + " bytes");
        }
        JsonNode retired = entry.path("retired");
        if (!retired.isMissingNode() && !retired.isBoolean()) {
            throw new DurableEvidenceException("trust key " + keyId + " retired must be true or false");
        }
        Instant notBefore = instant(entry.path("notBefore"), "key " + keyId + " notBefore");
        JsonNode end = entry.path("notAfter");
        Instant notAfter = end.isMissingNode() || end.isNull() ? null : instant(end, "key " + keyId + " notAfter");
        return new TrustedKey(keyId, producerId, secret, notBefore, notAfter, retired.asBoolean(false));
    }

    /** An ISO-8601 UTC instant with a {@code Z} suffix, as the Python model requires. */
    private static Instant instant(JsonNode value, String label) {
        String text = text(value);
        if (text == null || !text.endsWith("Z")) {
            throw new DurableEvidenceException(label + " must be an ISO-8601 UTC instant");
        }
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException ex) {
            throw new DurableEvidenceException(label + " must be an ISO-8601 UTC instant");
        }
    }

    private static String text(JsonNode node) {
        return node.isTextual() ? node.textValue() : null;
    }
}
