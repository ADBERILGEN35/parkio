package com.parkio.auth.infrastructure.durable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.auth.application.durable.DurableErasureEvidence;
import com.parkio.auth.application.durable.DurableErasureEvidenceVerifier;
import com.parkio.auth.application.durable.DurableErasureEvidenceVerifier.VerifiedFrontier;
import com.parkio.auth.application.durable.DurableErasureEvidenceVerifier.VerifiedPending;
import com.parkio.auth.application.durable.DurableEvidenceException;
import com.parkio.auth.application.durable.ProducerKey;
import com.parkio.auth.application.port.DurableErasurePutResult;
import com.parkio.auth.application.port.DurableErasureRecord;
import com.parkio.auth.application.port.DurableErasureRecordStore;
import com.parkio.auth.domain.exception.AuthErrorCode;
import com.parkio.auth.domain.exception.AuthException;
import io.minio.messages.RetentionMode;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Off-host durable erasure record store on an S3-compatible bucket with object lock, writing
 * evidence format v1 (docs/operations/recovery-evidence-contract.md). The protocol mirrors the
 * Python model: reserve a sequence with a marker, raise the frontier's highest reservation,
 * publish the signed record, then raise the expected boundary to cover it.
 *
 * <ul>
 *   <li>Every object version is written under a retention lock; nothing is overwritten or
 *       deleted. The first version of a record or marker is canonical, so a later write or a
 *       delete marker cannot replace it.</li>
 *   <li>An identical retry returns the existing record; a retry with a different body is a
 *       conflict.</li>
 *   <li>A record counts as found only once the signed frontier covers it, so a crash between
 *       record and frontier is completed by the next put instead of being reported durable.</li>
 *   <li>Store I/O refuses to run inside a database transaction.</li>
 * </ul>
 *
 * <p>Writes are serialised in this JVM; the frontier is read-modify-written, so one auth
 * instance may write a bucket at a time. Any store or verification failure throws
 * {@link AuthErrorCode#DURABLE_RECORDING_UNAVAILABLE}, which leaves the request
 * {@code PENDING_DURABLE} for the retry worker.
 */
public final class ObjectLockDurableErasureRecordStore implements DurableErasureRecordStore {

    private static final Logger log = LoggerFactory.getLogger(ObjectLockDurableErasureRecordStore.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ObjectLockBucket bucket;
    private final ObjectLockEvidenceObjects objects;
    private final String databaseIdentity;
    private final ProducerKey producer;
    private final DurableErasureEvidenceVerifier verifier;
    private final RetentionMode retentionMode;
    private final Duration retention;
    private final Clock clock;

    ObjectLockDurableErasureRecordStore(ObjectLockBucket bucket, String databaseIdentity, ProducerKey producer,
                                        RetentionMode retentionMode, Duration retention, Clock clock) {
        this.bucket = Objects.requireNonNull(bucket, "bucket");
        this.objects = new ObjectLockEvidenceObjects(bucket);
        this.databaseIdentity = Objects.requireNonNull(databaseIdentity, "databaseIdentity");
        this.producer = Objects.requireNonNull(producer, "producer");
        this.verifier = new DurableErasureEvidenceVerifier(databaseIdentity, Map.of(producer.producerId(), producer.key()));
        this.retentionMode = Objects.requireNonNull(retentionMode, "retentionMode");
        this.retention = Objects.requireNonNull(retention, "retention");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public synchronized DurableErasurePutResult putIfAbsent(DurableErasureRecord record) {
        requireNoTransaction();
        String key = DurableErasureEvidence.recordKey(record.erasureRequestId());
        Optional<ObjectLockBucket.StoredVersion> existing = bucket.oldest(key);
        if (existing.isPresent()) {
            return settle(existing.get(), record, false);
        }
        long sequence = reserveSequence(record.erasureRequestId());
        advanceFrontier(null, sequence);
        String versionId = publish(key, DurableErasureEvidence.pendingRecord(record, sequence, databaseIdentity, producer));
        ObjectLockBucket.StoredVersion canonical = bucket.oldest(key)
                .orElseThrow(() -> unavailable("record " + key + " is not readable after publication"));
        return settle(canonical, record, canonical.versionId().equals(versionId));
    }

    @Override
    public Optional<DurableErasureRecord> findByRequestId(UUID erasureRequestId) {
        requireNoTransaction();
        Optional<ObjectLockBucket.StoredVersion> stored = bucket.oldest(DurableErasureEvidence.recordKey(erasureRequestId));
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        VerifiedPending pending = verify(stored.get());
        long expectedThrough = frontier().map(VerifiedFrontier::expectedThrough).orElse(0L);
        // Not durable until the signed boundary covers it; the next put completes it.
        return pending.sequence() <= expectedThrough ? Optional.of(toRecord(pending, stored.get())) : Optional.empty();
    }

    /** Read access to the bucket's evidence, for recovery checks. */
    public ObjectLockEvidenceObjects evidence() {
        return objects;
    }

    private DurableErasurePutResult settle(ObjectLockBucket.StoredVersion stored, DurableErasureRecord candidate,
                                           boolean writtenNow) {
        VerifiedPending pending = verify(stored);
        DurableErasureRecord existing = toRecord(pending, stored);
        if (!existing.bodyDigest().equals(candidate.bodyDigest())) {
            return DurableErasurePutResult.conflict(existing);
        }
        advanceFrontier(pending.sequence(), pending.sequence());
        return writtenNow ? DurableErasurePutResult.created(existing) : DurableErasurePutResult.existing(existing);
    }

    /**
     * Python's SequenceAllocator: reuse this request's reservation, else take the next free
     * sequence. Like the model it reads every marker, which is fine at erasure volumes.
     */
    private long reserveSequence(UUID erasureRequestId) {
        String requestId = erasureRequestId.toString();
        long highest = 0;
        for (String key : bucket.keys("sequences/")) {
            long sequence = sequenceOf(key);
            highest = Math.max(highest, sequence);
            if (requestId.equals(markerRequestId(bucket.oldest(key).orElseThrow()))) {
                return sequence;
            }
        }
        for (long next = highest + 1; ; next++) {
            String key = DurableErasureEvidence.sequenceKey(next);
            if (bucket.oldest(key).isPresent()) {
                continue;
            }
            publish(key, DurableErasureEvidence.sequenceMarker(next, erasureRequestId));
            // Another writer may have reserved it first: the first version wins.
            if (requestId.equals(markerRequestId(bucket.oldest(key).orElseThrow()))) {
                return next;
            }
        }
    }

    /** Raises the signed boundary; it never decreases and highestReserved never trails it. */
    private void advanceFrontier(Long expectedThrough, long highestReserved) {
        Optional<VerifiedFrontier> current = frontier();
        long oldExpected = current.map(VerifiedFrontier::expectedThrough).orElse(0L);
        long oldReserved = current.map(VerifiedFrontier::highestReserved).orElse(0L);
        long newExpected = expectedThrough == null ? oldExpected : Math.max(oldExpected, expectedThrough);
        long newReserved = Math.max(Math.max(oldReserved, highestReserved), newExpected);
        if (current.isPresent() && newExpected == oldExpected && newReserved == oldReserved) {
            return;
        }
        publish(DurableErasureEvidence.FRONTIER_KEY,
                DurableErasureEvidence.frontier(newExpected, newReserved, databaseIdentity, producer));
    }

    private Optional<VerifiedFrontier> frontier() {
        try {
            return verifier.verifyFrontier(objects.find(DurableErasureEvidence.FRONTIER_KEY));
        } catch (DurableEvidenceException ex) {
            log.error("durable store frontier failed verification: {}", ex.getMessage());
            throw unavailable("frontier failed verification");
        }
    }

    private VerifiedPending verify(ObjectLockBucket.StoredVersion stored) {
        try {
            return verifier.verifyPending(stored.bytes());
        } catch (DurableEvidenceException ex) {
            log.error("durable store record {} version {} failed verification: {}",
                    stored.key(), stored.versionId(), ex.getMessage());
            throw unavailable("record failed verification");
        }
    }

    private static DurableErasureRecord toRecord(VerifiedPending pending, ObjectLockBucket.StoredVersion stored) {
        try {
            JsonNode body = JSON.readTree(stored.bytes());
            return new DurableErasureRecord(UUID.fromString(pending.erasureRequestId()),
                    UUID.fromString(pending.authUserId()), Instant.parse(pending.erasedAt()),
                    body.path("bodyDigest").asText());
        } catch (IOException | RuntimeException ex) {
            throw unavailable("record " + stored.key() + " is not a format v1 record");
        }
    }

    private static String markerRequestId(ObjectLockBucket.StoredVersion marker) {
        try {
            return JSON.readTree(marker.bytes()).path("erasureRequestId").asText();
        } catch (IOException ex) {
            throw unavailable("marker " + marker.key() + " is unreadable");
        }
    }

    private static long sequenceOf(String key) {
        String name = key.substring(key.lastIndexOf('/') + 1).replace(".json", "");
        try {
            return Long.parseLong(name);
        } catch (NumberFormatException ex) {
            throw unavailable("unexpected sequence marker " + key);
        }
    }

    /** Every version is written under the configured retention lock. */
    private String publish(String key, byte[] bytes) {
        return bucket.put(key, bytes, retentionMode,
                ZonedDateTime.ofInstant(clock.instant().plus(retention), ZoneOffset.UTC));
    }

    private static void requireNoTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("durable store I/O must run outside a database transaction");
        }
    }

    private static AuthException unavailable(String reason) {
        return new AuthException(AuthErrorCode.DURABLE_RECORDING_UNAVAILABLE, "durable store: " + reason);
    }
}
