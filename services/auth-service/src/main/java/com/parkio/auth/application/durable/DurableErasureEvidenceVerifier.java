package com.parkio.auth.application.durable;

import static com.parkio.auth.application.durable.DurableErasureEvidence.FRONTIER_KEY;
import static com.parkio.auth.application.durable.DurableErasureEvidence.KIND_CHECKPOINT;
import static com.parkio.auth.application.durable.DurableErasureEvidence.KIND_FRONTIER;
import static com.parkio.auth.application.durable.DurableErasureEvidence.KIND_PENDING;
import static com.parkio.auth.application.durable.DurableErasureEvidence.SIGNED_CHECKPOINT;
import static com.parkio.auth.application.durable.DurableErasureEvidence.SIGNED_FRONTIER;
import static com.parkio.auth.application.durable.DurableErasureEvidence.SIGNED_PENDING;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.LongStream;

/**
 * Verifies durable erasure evidence the way the Python model does
 * ({@code verify_pending}, {@code verify_frontier}, {@code verify_checkpoint} and
 * {@code recover_latest_trusted} in {@code scripts/lib/recovery_persist_protocol.py}): same
 * checks, same order, same verdicts and the same error messages. The pinned database identity
 * and the producer keys ({@link EvidenceTrust}) come from the consumer's own configuration,
 * never from the objects. Every signed object must be format v2, name a trusted key of its own
 * producer that is not retired and was valid ({@code notBefore}) at the verification instant.
 *
 * <p>Like the model, {@link Verdict#ACCEPT_ISOLATED} proves producer authenticity, byte
 * integrity and contiguity up to the signed frontier. It does not prove off-host WORM
 * durability or freshness, and it does not set verifiedCoverage.
 */
public final class DurableErasureEvidenceVerifier {

    public enum Verdict { ACCEPT_ISOLATED, UNKNOWN, BLOCKED }

    public record VerifiedPending(String erasureRequestId, String authUserId, String erasedAt,
                                  long sequence, String producerId) {
    }

    public record VerifiedFrontier(long expectedThrough, long highestReserved, String producerId) {
    }

    public record VerifiedCheckpoint(long sequence, String producerId, String ledgerDigest) {
    }

    /**
     * Completeness uses the signed frontier, never the highest listed sequence: no frontier is
     * {@link Verdict#UNKNOWN}; a missing record in {@code 1..expectedThrough} is
     * {@link Verdict#BLOCKED}.
     */
    public record RecoveryVerdict(Verdict verdict,
                                  Long expectedThrough,
                                  Long latestTrustedSequence,
                                  Long latestTrustedCheckpoint,
                                  Long listedMaximumSequence,
                                  List<Long> gaps,
                                  List<Long> abandonedReservations,
                                  List<VerifiedPending> pending,
                                  String reason) {

        public boolean completenessEstablished() {
            return verdict == Verdict.ACCEPT_ISOLATED;
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final EvidenceTrust trust;
    private final Instant at;

    /** Verifies against {@code trust} as of {@code at} (the consumer's clock). */
    public DurableErasureEvidenceVerifier(EvidenceTrust trust, Instant at) {
        this.trust = Objects.requireNonNull(trust, "trust");
        this.at = Objects.requireNonNull(at, "at");
    }

    public VerifiedPending verifyPending(byte[] raw) {
        Map<String, Object> body = parse(raw);
        verifySigned(body, KIND_PENDING, "not a pending record", SIGNED_PENDING, "producer signature mismatch");
        Map<String, Object> digestBody = new LinkedHashMap<>();
        for (String field : List.of("authUserId", "erasureRequestId", "erasedAt")) {
            if (!body.containsKey(field)) {
                throw new DurableEvidenceException("pending body digest mismatch");
            }
            digestBody.put(field, body.get(field));
        }
        if (!DurableErasureEvidence.sha256Hex(CanonicalJson.bytes(digestBody)).equals(body.get("bodyDigest"))) {
            throw new DurableEvidenceException("pending body digest mismatch");
        }
        return new VerifiedPending(String.valueOf(body.get("erasureRequestId")),
                String.valueOf(body.get("authUserId")), String.valueOf(body.get("erasedAt")),
                integral(body, "sequence"), String.valueOf(body.get("producerId")));
    }

    public Optional<VerifiedFrontier> verifyFrontier(Optional<byte[]> raw) {
        if (raw.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> body = parse(raw.get());
        verifySigned(body, KIND_FRONTIER, "not an expected-boundary frontier", SIGNED_FRONTIER,
                "frontier signature mismatch");
        long expectedThrough = integral(body, "expectedThrough");
        long highestReserved = integral(body, "highestReserved");
        if (!DurableErasureEvidence.frontierDigest(expectedThrough, highestReserved).equals(body.get("frontierDigest"))) {
            throw new DurableEvidenceException("frontier digest mismatch");
        }
        return Optional.of(new VerifiedFrontier(expectedThrough, highestReserved, String.valueOf(body.get("producerId"))));
    }

    public VerifiedCheckpoint verifyCheckpoint(byte[] raw) {
        Map<String, Object> body = parse(raw);
        verifySigned(body, KIND_CHECKPOINT, "not a checkpoint", SIGNED_CHECKPOINT, "producer signature mismatch");
        if (!body.containsKey("entries")
                || !DurableErasureEvidence.ledgerDigest(body.get("entries")).equals(body.get("ledgerDigest"))) {
            throw new DurableEvidenceException("checkpoint ledger digest mismatch");
        }
        return new VerifiedCheckpoint(integral(body, "sequence"), String.valueOf(body.get("producerId")),
                String.valueOf(body.get("ledgerDigest")));
    }

    /**
     * Recovers the latest trusted state from the store. With {@code requiredThroughSequence},
     * anything short of an accepted, gap-free boundary at or beyond it is refused.
     */
    public RecoveryVerdict recover(EvidenceObjects store, Long requiredThroughSequence) {
        List<VerifiedPending> pending = new ArrayList<>();
        for (String key : store.list("records/")) {
            pending.add(verifyPending(store.get(key)));
        }
        List<VerifiedCheckpoint> checkpoints = new ArrayList<>();
        for (String key : store.list("checkpoints/")) {
            checkpoints.add(verifyCheckpoint(store.get(key)));
        }
        Set<Long> published = new TreeSet<>();
        pending.forEach(item -> published.add(item.sequence()));
        checkpoints.forEach(item -> published.add(item.sequence()));
        Optional<VerifiedFrontier> frontier = verifyFrontier(store.find(FRONTIER_KEY));
        List<Long> abandoned = abandonedReservations(store, published);
        Long listedMaximum = published.isEmpty() ? null : Collections.max(published);

        if (frontier.isEmpty()) {
            return result(Verdict.UNKNOWN, null, null, List.of(), checkpoints, listedMaximum, abandoned,
                    List.of(), "independently durable expected boundary is missing", requiredThroughSequence);
        }
        long expectedThrough = frontier.get().expectedThrough();
        List<Long> gaps = LongStream.rangeClosed(1, expectedThrough)
                .filter(sequence -> !published.contains(sequence))
                .boxed()
                .toList();
        if (!gaps.isEmpty() || (expectedThrough == 0 && !abandoned.isEmpty())) {
            return result(Verdict.BLOCKED, expectedThrough, null, gaps, checkpoints, listedMaximum, abandoned,
                    List.of(), "expected boundary is present but published records are incomplete",
                    requiredThroughSequence);
        }
        if (expectedThrough == 0) {
            return result(Verdict.ACCEPT_ISOLATED, 0L, null, gaps, checkpoints, listedMaximum, abandoned,
                    List.of(), "expected boundary is zero and no abandoned reservation remains",
                    requiredThroughSequence);
        }
        List<VerifiedPending> trusted = pending.stream()
                .filter(item -> item.sequence() <= expectedThrough)
                .toList();
        return result(Verdict.ACCEPT_ISOLATED, expectedThrough, expectedThrough, gaps, checkpoints, listedMaximum,
                abandoned, trusted, null, requiredThroughSequence);
    }

    private static RecoveryVerdict result(Verdict verdict, Long expectedThrough, Long latest, List<Long> gaps,
                                          List<VerifiedCheckpoint> checkpoints, Long listedMaximum,
                                          List<Long> abandoned, List<VerifiedPending> trusted, String reason,
                                          Long requiredThroughSequence) {
        Long latestCheckpoint = latest == null ? null : checkpoints.stream()
                .map(VerifiedCheckpoint::sequence)
                .filter(sequence -> sequence <= latest)
                .max(Long::compare)
                .orElse(null);
        if (requiredThroughSequence != null && verdict != Verdict.ACCEPT_ISOLATED) {
            throw new DurableEvidenceException(verdict + ": missing records or unknown tail; recovery BLOCKED");
        }
        if (requiredThroughSequence != null) {
            long expected = expectedThrough == null ? 0 : expectedThrough;
            if (requiredThroughSequence > expected || gaps.contains(requiredThroughSequence)) {
                throw new DurableEvidenceException("missing records or unknown tail; recovery BLOCKED");
            }
        }
        return new RecoveryVerdict(verdict, expectedThrough, latest, latestCheckpoint, listedMaximum,
                List.copyOf(gaps), List.copyOf(abandoned), List.copyOf(trusted), reason);
    }

    /** Reserved sequences without a published record or checkpoint (markers are unsigned). */
    private static List<Long> abandonedReservations(EvidenceObjects store, Set<Long> published) {
        List<Long> abandoned = new ArrayList<>();
        for (String key : store.list("sequences/")) {
            long sequence = integral(parse(store.get(key)), "sequence");
            if (!published.contains(sequence)) {
                abandoned.add(sequence);
            }
        }
        Collections.sort(abandoned);
        return abandoned;
    }

    /** The checks every signed kind shares, in the order the Python model applies them. */
    private void verifySigned(Map<String, Object> body, String kind, String kindError, List<String> fields,
                              String signatureError) {
        if (!kind.equals(body.get("kind"))) {
            throw new DurableEvidenceException(kindError);
        }
        if (!Long.valueOf(DurableErasureEvidence.SCHEMA_VERSION).equals(body.get("schemaVersion"))) {
            throw new DurableEvidenceException("unsupported schema version");
        }
        if (!trust.databaseIdentity().equals(body.get("databaseIdentity"))) {
            throw new DurableEvidenceException("database identity mismatch");
        }
        TrustedKey key = trust.verifyingKey(body, at);
        if (!DurableErasureEvidence.signatureMatches(body, fields, key.key())) {
            throw new DurableEvidenceException(signatureError);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(byte[] raw) {
        JsonNode node;
        try {
            node = JSON.readTree(raw);
        } catch (IOException ex) {
            throw new DurableEvidenceException("unreadable evidence object");
        }
        if (node == null || !node.isObject()) {
            throw new DurableEvidenceException("evidence object is not a JSON object");
        }
        try {
            return (Map<String, Object>) CanonicalJson.plain(node);
        } catch (IllegalArgumentException ex) {
            throw new DurableEvidenceException("evidence object is not canonical JSON");
        }
    }

    private static long integral(Map<String, Object> body, String field) {
        Object value = body.get(field);
        if (value instanceof Long number) {
            return number;
        }
        if (value instanceof BigInteger big && big.bitLength() < Long.SIZE) {
            return big.longValue();
        }
        throw new DurableEvidenceException(field + " must be an integer");
    }
}
