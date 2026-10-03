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
import java.util.TreeMap;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Durable erasure evidence format v2, shared with the Python persist protocol
 * ({@code scripts/lib/recovery_persist_protocol.py}). This class writes the objects a store
 * adapter publishes: signed pending records, signed checkpoints, sequence-allocation markers
 * and the signed expected-boundary frontier. Bytes are canonical JSON ({@link CanonicalJson});
 * signatures are hex HMAC-SHA256 over the canonical signed subset, which includes the signing
 * key's {@code keyId} (v2; v1 objects had none). The cross-language fixtures under
 * {@code src/test/resources/durable-erasure-evidence/v2} pin byte equality with Python.
 *
 * <p>Store I/O, sequence allocation, key windows, trust and flags are not part of this class.
 */
public final class DurableErasureEvidence {

    public static final int SCHEMA_VERSION = 2;
    public static final String KIND_PENDING = "erasure-pending-record";
    public static final String KIND_CHECKPOINT = "erasure-checkpoint";
    public static final String KIND_FRONTIER = "erasure-expected-frontier";
    public static final String KIND_SEQUENCE_ALLOCATION = "sequence-allocation";
    public static final String KIND_LEDGER = "erasure-ledger";
    public static final String FRONTIER_KEY = "frontier/expected-through.json";
    /** The capture protocol of a checkpoint: READ COMMITTED read under a SHARE table lock. */
    public static final String CAPTURE_PROTOCOL_TABLE_SHARE_LOCK = "table-share-lock";
    /**
     * The {@code erasureRequestId} of a sequence marker that reserves a checkpoint's sequence.
     * Format v1 has one marker shape; the nil UUID is never a request id.
     */
    public static final UUID CHECKPOINT_RESERVATION = new UUID(0L, 0L);

    public static final List<String> SIGNED_PENDING = List.of(
            "schemaVersion", "kind", "erasureRecordId", "erasureRequestId",
            "authUserId", "sequence", "databaseIdentity", "producerId", "keyId", "bodyDigest");
    public static final List<String> SIGNED_CHECKPOINT = List.of(
            "schemaVersion", "kind", "sequence", "databaseIdentity", "producerId", "keyId",
            "ledgerDigest", "captureProtocol");
    public static final List<String> SIGNED_FRONTIER = List.of(
            "schemaVersion", "kind", "expectedThrough", "highestReserved",
            "databaseIdentity", "producerId", "keyId", "frontierDigest");

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
     * {@code erasedAt} as written into records: the ISO-8601 instant truncated to
     * microseconds, PostgreSQL's precision. Truncation alone does not make an instant and its
     * database copy format the same: the JDBC driver rounds sub-microsecond digits when it writes,
     * so a nanosecond instant can come back one microsecond later. Producers therefore store the
     * time already at microsecond precision (AccountErasureApplicationService), and a record
     * rebuilt from the database is then byte-identical to the first one.
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
                                       String databaseIdentity, TrustedKey signingKey) {
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
        body.put("producerId", signingKey.producerId());
        body.put("keyId", signingKey.keyId());
        body.put("bodyDigest", record.bodyDigest());
        body.put("signature", sign(body, SIGNED_PENDING, signingKey.key()));
        return CanonicalJson.bytes(body);
    }

    /**
     * A signed checkpoint: the whole tombstone ledger captured under the lock protocol, one
     * {@code {authUserId, erasedAt}} entry per tombstone ordered by {@code authUserId} (the order
     * of PostgreSQL's {@code ORDER BY auth_user_id}), bound by {@code ledgerDigest}.
     */
    public static byte[] checkpoint(long sequence, List<ErasureLedgerEntry> entries,
                                    String databaseIdentity, TrustedKey signingKey) {
        List<Map<String, Object>> ledger = ledgerEntries(entries);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schemaVersion", SCHEMA_VERSION);
        body.put("kind", KIND_CHECKPOINT);
        body.put("sequence", requirePositive(sequence));
        body.put("databaseIdentity", requireText(databaseIdentity, "databaseIdentity"));
        body.put("producerId", signingKey.producerId());
        body.put("keyId", signingKey.keyId());
        body.put("ledgerDigest", ledgerDigest(ledger));
        body.put("captureProtocol", CAPTURE_PROTOCOL_TABLE_SHARE_LOCK);
        body.put("entries", ledger);
        body.put("signature", sign(body, SIGNED_CHECKPOINT, signingKey.key()));
        return CanonicalJson.bytes(body);
    }

    /**
     * Ledger entries in canonical order. Lowercase UUID text sorts like PostgreSQL's uuid order
     * (unsigned bytes); {@link UUID#compareTo} compares signed longs and would not.
     */
    static List<Map<String, Object>> ledgerEntries(List<ErasureLedgerEntry> entries) {
        Objects.requireNonNull(entries, "entries");
        Map<String, Map<String, Object>> byUser = new TreeMap<>();
        for (ErasureLedgerEntry entry : entries) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("authUserId", entry.authUserId().toString());
            item.put("erasedAt", erasedAt(entry.erasedAt()));
            if (byUser.put(entry.authUserId().toString(), item) != null) {
                throw new IllegalArgumentException("duplicate ledger entry for one authUserId");
            }
        }
        return List.copyOf(byUser.values());
    }

    /** The signed expected boundary; {@code highestReserved} is never below {@code expectedThrough}. */
    public static byte[] frontier(long expectedThrough, long highestReserved,
                                  String databaseIdentity, TrustedKey signingKey) {
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
        body.put("producerId", signingKey.producerId());
        body.put("keyId", signingKey.keyId());
        body.put("frontierDigest", frontierDigest(expectedThrough, highestReserved));
        body.put("signature", sign(body, SIGNED_FRONTIER, signingKey.key()));
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
