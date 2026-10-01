package com.parkio.auth.application.durable;

import com.parkio.auth.application.port.DurableErasureRecord;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Durable erasure evidence format v1, shared with the Python persist protocol
 * ({@code scripts/lib/recovery_persist_protocol.py}). This class writes the objects a store
 * adapter publishes: signed pending records, sequence-allocation markers and the signed
 * expected-boundary frontier. Bytes are canonical JSON ({@link CanonicalJson}); signatures
 * are hex HMAC-SHA256 over the canonical signed subset. The cross-language fixtures under
 * {@code src/test/resources/durable-erasure-evidence/v1} pin byte equality with Python.
 *
 * <p>Store I/O, sequence allocation, key distribution and flags are not part of this class.
 */
public final class DurableErasureEvidence {

    public static final int SCHEMA_VERSION = 1;
    public static final String KIND_PENDING = "erasure-pending-record";
    public static final String KIND_CHECKPOINT = "erasure-checkpoint";
    public static final String KIND_FRONTIER = "erasure-expected-frontier";
    public static final String KIND_SEQUENCE_ALLOCATION = "sequence-allocation";
    public static final String KIND_LEDGER = "erasure-ledger";
    public static final String FRONTIER_KEY = "frontier/expected-through.json";

    public static final List<String> SIGNED_PENDING = List.of(
            "schemaVersion", "kind", "erasureRecordId", "erasureRequestId",
            "authUserId", "sequence", "databaseIdentity", "producerId", "bodyDigest");
    public static final List<String> SIGNED_CHECKPOINT = List.of(
            "schemaVersion", "kind", "sequence", "databaseIdentity", "producerId",
            "ledgerDigest", "captureProtocol");
    public static final List<String> SIGNED_FRONTIER = List.of(
            "schemaVersion", "kind", "expectedThrough", "highestReserved",
            "databaseIdentity", "producerId", "frontierDigest");

    private DurableErasureEvidence() {
    }

    /** One request, one record key; retries reuse it. */
    public static String recordKey(UUID erasureRequestId) {
        return "records/" + erasureRequestId + ".json";
    }

    public static String sequenceKey(long sequence) {
        return String.format(Locale.ROOT, "sequences/%016d.json", requirePositive(sequence));
    }

    public static String checkpointKey(long sequence) {
        return String.format(Locale.ROOT, "checkpoints/%016d.json", requirePositive(sequence));
    }

    /**
     * {@code erasedAt} as written into records: {@link Instant#toString()} after truncation to
     * microseconds, PostgreSQL's precision, so a value read back from the database formats the
     * same as the one that was written.
     */
    public static String erasedAt(Instant instant) {
        return DateTimeFormatter.ISO_INSTANT.format(instant.truncatedTo(ChronoUnit.MICROS));
    }

    /** SHA-256 of the canonical {@code {authUserId, erasureRequestId, erasedAt}} body. */
    public static String bodyDigest(UUID authUserId, UUID erasureRequestId, String erasedAt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("authUserId", authUserId.toString());
        body.put("erasureRequestId", erasureRequestId.toString());
        body.put("erasedAt", Objects.requireNonNull(erasedAt, "erasedAt"));
        return sha256Hex(CanonicalJson.bytes(body));
    }

    public static String frontierDigest(long expectedThrough, long highestReserved) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("kind", KIND_FRONTIER);
        body.put("expectedThrough", expectedThrough);
        body.put("highestReserved", highestReserved);
        return sha256Hex(CanonicalJson.bytes(body));
    }

    public static String ledgerDigest(Object entries) {
        Map<String, Object> ledger = new LinkedHashMap<>();
        ledger.put("kind", KIND_LEDGER);
        ledger.put("entries", entries);
        return sha256Hex(CanonicalJson.bytes(ledger));
    }

    /** The unsigned if-not-exists marker that reserves {@code sequence} for one request. */
    public static byte[] sequenceMarker(long sequence, UUID erasureRequestId) {
        Map<String, Object> marker = new LinkedHashMap<>();
        marker.put("kind", KIND_SEQUENCE_ALLOCATION);
        marker.put("sequence", requirePositive(sequence));
        marker.put("erasureRequestId", erasureRequestId.toString());
        return CanonicalJson.bytes(marker);
    }

    public static byte[] pendingRecord(DurableErasureRecord record, long sequence,
                                       String databaseIdentity, ProducerKey producer) {
        Objects.requireNonNull(record, "record");
        String erasedAt = erasedAt(record.erasedAt());
        if (!bodyDigest(record.authUserId(), record.erasureRequestId(), erasedAt).equals(record.bodyDigest())) {
            // A verifier would reject the object; refuse to publish it.
            throw new IllegalArgumentException("record bodyDigest does not match its fields");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schemaVersion", SCHEMA_VERSION);
        body.put("kind", KIND_PENDING);
        body.put("erasureRecordId", recordKey(record.erasureRequestId()));
        body.put("erasureRequestId", record.erasureRequestId().toString());
        body.put("authUserId", record.authUserId().toString());
        body.put("erasedAt", erasedAt);
        body.put("sequence", requirePositive(sequence));
        body.put("databaseIdentity", requireText(databaseIdentity, "databaseIdentity"));
        body.put("producerId", producer.producerId());
        body.put("bodyDigest", record.bodyDigest());
        body.put("signature", sign(body, SIGNED_PENDING, producer.key()));
        return CanonicalJson.bytes(body);
    }

    /** The signed expected boundary; {@code highestReserved} is never below {@code expectedThrough}. */
    public static byte[] frontier(long expectedThrough, long highestReserved,
                                  String databaseIdentity, ProducerKey producer) {
        if (expectedThrough < 0 || highestReserved < expectedThrough) {
            throw new IllegalArgumentException(
                    "frontier needs 0 <= expectedThrough <= highestReserved: "
                            + expectedThrough + ", " + highestReserved);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schemaVersion", SCHEMA_VERSION);
        body.put("kind", KIND_FRONTIER);
        body.put("expectedThrough", expectedThrough);
        body.put("highestReserved", highestReserved);
        body.put("databaseIdentity", requireText(databaseIdentity, "databaseIdentity"));
        body.put("producerId", producer.producerId());
        body.put("frontierDigest", frontierDigest(expectedThrough, highestReserved));
        body.put("signature", sign(body, SIGNED_FRONTIER, producer.key()));
        return CanonicalJson.bytes(body);
    }

    /** Hex HMAC-SHA256 over the canonical subset of {@code body} named by {@code fields}. */
    static String sign(Map<String, ?> body, List<String> fields, byte[] key) {
        Map<String, Object> subset = new LinkedHashMap<>();
        for (String field : fields) {
            if (!body.containsKey(field)) {
                throw new DurableEvidenceException("missing signed field " + field);
            }
            subset.put(field, body.get(field));
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(CanonicalJson.bytes(subset)));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HmacSHA256 is required", ex);
        }
    }

    static boolean signatureMatches(Map<String, ?> body, List<String> fields, byte[] key) {
        Object provided = body.get("signature");
        String expected = sign(body, fields, key);
        byte[] given = (provided instanceof String text ? text : "").getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), given);
    }

    static String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("SHA-256 is required", ex);
        }
    }

    private static long requirePositive(long sequence) {
        if (sequence < 1) {
            throw new IllegalArgumentException("sequence must be >= 1: " + sequence);
        }
        return sequence;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
