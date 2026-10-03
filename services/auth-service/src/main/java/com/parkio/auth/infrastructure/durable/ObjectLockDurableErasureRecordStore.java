package com.parkio.auth.infrastructure.durable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.auth.application.durable.DurableErasureEvidence;
import com.parkio.auth.application.durable.DurableErasureEvidenceVerifier;
import com.parkio.auth.application.durable.DurableErasureEvidenceVerifier.FrontierVersions;
import com.parkio.auth.application.durable.DurableErasureEvidenceVerifier.VerifiedCheckpoint;
import com.parkio.auth.application.durable.DurableErasureEvidenceVerifier.VerifiedFrontier;
import com.parkio.auth.application.durable.DurableErasureEvidenceVerifier.VerifiedPending;
import com.parkio.auth.application.durable.DurableEvidenceException;
import com.parkio.auth.application.durable.EvidenceTrust;
import com.parkio.auth.application.durable.TrustedKey;
import com.parkio.auth.application.port.CapturedErasureLedger;
import com.parkio.auth.application.port.DurableErasureCheckpoint;
import com.parkio.auth.application.port.DurableErasureCheckpointStore;
import com.parkio.auth.application.port.DurableErasurePutResult;
import com.parkio.auth.application.port.DurableErasureReceipt;
import com.parkio.auth.application.port.DurableErasureRecord;
import com.parkio.auth.application.port.DurableErasureRecordStore;
import com.parkio.auth.application.port.ErasureLedgerCapture;
import com.parkio.auth.domain.exception.AuthErrorCode;
import com.parkio.auth.domain.exception.AuthException;
import io.minio.messages.Retention;
import io.minio.messages.RetentionMode;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Off-host durable erasure record store on an S3-compatible bucket with object lock, writing
 * evidence format v2 (docs/operations/recovery-evidence-contract.md). The protocol mirrors the
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
 *   <li>Objects are signed with one key of the trust document, and only while that key is not
 *       retired and inside its signing window; reads verify with the whole trust.</li>
 * </ul>
 *
 * <p>Signed checkpoints of the tombstone ledger (contract stage 2) take sequences from the same
 * space as records; see {@link #publishCheckpoint(ErasureLedgerCapture)}.
 *
 * <p>Writes are serialised in this JVM; the frontier is read-modify-written, so one auth
 * instance may write a bucket at a time. Any store or verification failure throws
 * {@link AuthErrorCode#DURABLE_RECORDING_UNAVAILABLE}, which leaves the request
 * {@code PENDING_DURABLE} for the retry worker.
 */
public final class ObjectLockDurableErasureRecordStore
        implements DurableErasureRecordStore, DurableErasureCheckpointStore {

    private static final Logger log = LoggerFactory.getLogger(ObjectLockDurableErasureRecordStore.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ObjectLockBucket bucket;
    private final ObjectLockEvidenceObjects objects;
    private final EvidenceTrust trust;
    private final TrustedKey signingKey;
    private final String databaseIdentity;
    private final RetentionMode retentionMode;
    private final Duration retention;
    private final Clock clock;
    private final AtomicInteger reportedIgnoredFrontierVersions = new AtomicInteger();

    /**
     * Signs with {@code signingKeyId} from {@code trust} and verifies what it reads with the whole
     * trust, so objects signed with an earlier key still verify after a key rotation.
     */
    ObjectLockDurableErasureRecordStore(ObjectLockBucket bucket, EvidenceTrust trust, String signingKeyId,
                                        RetentionMode retentionMode, Duration retention, Clock clock) {
        this.bucket = Objects.requireNonNull(bucket, "bucket");
        this.objects = new ObjectLockEvidenceObjects(bucket);
        this.trust = Objects.requireNonNull(trust, "trust");
        this.signingKey = trust.key(signingKeyId)
                .orElseThrow(() -> new IllegalArgumentException("signing key " + signingKeyId + " is not in the trust"));
        this.databaseIdentity = trust.databaseIdentity();
        this.retentionMode = Objects.requireNonNull(retentionMode, "retentionMode");
        this.retention = Objects.requireNonNull(retention, "retention");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public synchronized DurableErasurePutResult putIfAbsent(DurableErasureRecord record) {
        requireNoTransaction();
        signingKey();
        String key = DurableErasureEvidence.recordKey(record.erasureRequestId());
        Optional<ObjectLockBucket.StoredVersion> existing = bucket.oldest(key);
        if (existing.isPresent()) {
            return settle(existing.get(), record, false);
        }
        long sequence = reserveSequence(record.erasureRequestId());
        advanceFrontier(null, sequence);
        String versionId = publish(key,
                DurableErasureEvidence.pendingRecord(record, sequence, databaseIdentity, signingKey()));
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

    /**
     * Captures the tombstone ledger and publishes it as the next signed checkpoint. The capture
     * runs inside this writer's lock, so no record can take a sequence between the snapshot and
     * the checkpoint's reservation: every record below the checkpoint's sequence was reserved
     * before the snapshot, after its tombstone committed, and its tombstone is in the snapshot.
     * The sequence is reserved only after the capture committed, so a failed capture publishes
     * nothing. A checkpoint reservation left without its checkpoint (a store failure or crash
     * after the marker) is filled by the next checkpoint, which keeps the rule above (that
     * sequence, too, was reserved before this snapshot) and closes the gap below the frontier.
     */
    @Override
    public synchronized DurableErasureCheckpoint publishCheckpoint(ErasureLedgerCapture capture) {
        requireNoTransaction();
        signingKey();
        CapturedErasureLedger ledger = Objects.requireNonNull(capture.capture(), "capture");
        requireNoTransaction();
        OptionalLong abandoned = abandonedCheckpointReservation();
        long sequence = abandoned.isPresent() ? abandoned.getAsLong() : reserveCheckpointSequence();
        advanceFrontier(null, sequence);
        String key = DurableErasureEvidence.checkpointKey(sequence);
        String versionId = publish(key,
                DurableErasureEvidence.checkpoint(sequence, ledger.entries(), databaseIdentity, signingKey()));
        ObjectLockBucket.StoredVersion canonical = bucket.oldest(key)
                .orElseThrow(() -> unavailable("checkpoint " + key + " is not readable after publication"));
        if (!canonical.versionId().equals(versionId)) {
            throw unavailable("checkpoint " + key + " was published by another writer");
        }
        VerifiedCheckpoint verified = verifyCheckpoint(canonical);
        advanceFrontier(sequence, sequence);
        return new DurableErasureCheckpoint(sequence, ledger.entries().size(), verified.ledgerDigest(),
                ledger.coveredThrough(), abandoned.isPresent());
    }

    /** Read access to the bucket's evidence, for recovery checks. */
    public ObjectLockEvidenceObjects evidence() {
        return objects;
    }

    private DurableErasurePutResult settle(ObjectLockBucket.StoredVersion stored, DurableErasureRecord candidate,
                                           boolean writtenNow) {
        VerifiedPending pending = verify(stored);
        DurableErasureRecord existing = toRecord(pending, stored);
        DurableErasureReceipt receipt = receipt(stored);
        if (!existing.bodyDigest().equals(candidate.bodyDigest())) {
            return DurableErasurePutResult.conflict(existing, receipt);
        }
        advanceFrontier(pending.sequence(), pending.sequence());
        return writtenNow
                ? DurableErasurePutResult.created(existing, receipt)
                : DurableErasurePutResult.existing(existing, receipt);
    }

    /** The canonical version's id, the SHA-256 of its bytes, and the lock the store reports on it. */
    private DurableErasureReceipt receipt(ObjectLockBucket.StoredVersion stored) {
        Retention retention = bucket.retention(stored.key(), stored.versionId());
        if (retention == null || retention.mode() == null || retention.retainUntilDate() == null) {
            throw unavailable("record " + stored.key() + " has no retention lock");
        }
        return new DurableErasureReceipt(stored.versionId(), sha256Hex(stored.bytes()), retention.mode().name(),
                retention.retainUntilDate().toInstant());
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
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

    /** The lowest sequence a checkpoint reserved without publishing its checkpoint, if any. */
    private OptionalLong abandonedCheckpointReservation() {
        for (String key : bucket.keys("sequences/")) {
            long sequence = sequenceOf(key);
            if (isCheckpointReservation(bucket.oldest(key).orElseThrow())
                    && bucket.oldest(DurableErasureEvidence.checkpointKey(sequence)).isEmpty()) {
                return OptionalLong.of(sequence);
            }
        }
        return OptionalLong.empty();
    }

    /** The next free sequence, reserved with a checkpoint marker; the first marker version wins. */
    private long reserveCheckpointSequence() {
        long highest = 0;
        for (String key : bucket.keys("sequences/")) {
            highest = Math.max(highest, sequenceOf(key));
        }
        for (long next = highest + 1; ; next++) {
            String key = DurableErasureEvidence.sequenceKey(next);
            if (bucket.oldest(key).isPresent()) {
                continue;
            }
            String versionId = publish(key,
                    DurableErasureEvidence.sequenceMarker(next, DurableErasureEvidence.CHECKPOINT_RESERVATION));
            if (bucket.oldest(key).orElseThrow().versionId().equals(versionId)) {
                return next;
            }
        }
    }

    private static boolean isCheckpointReservation(ObjectLockBucket.StoredVersion marker) {
        return DurableErasureEvidence.CHECKPOINT_RESERVATION.toString().equals(markerRequestId(marker));
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
                DurableErasureEvidence.frontier(newExpected, newReserved, databaseIdentity, signingKey()));
    }

    private Optional<VerifiedFrontier> frontier() {
        FrontierVersions versions;
        try {
            versions = verifier().verifyFrontierVersions(objects.findAll(DurableErasureEvidence.FRONTIER_KEY));
        } catch (DurableEvidenceException ex) {
            log.error("durable store frontier failed verification: {}", ex.getMessage());
            throw unavailable("frontier failed verification");
        }
        int ignored = versions.ignoredVersions();
        if (ignored > reportedIgnoredFrontierVersions.getAndAccumulate(ignored, Math::max)) {
            log.warn("durable store ignores {} frontier version(s) that failed verification", ignored);
        }
        return versions.highest();
    }

    private VerifiedCheckpoint verifyCheckpoint(ObjectLockBucket.StoredVersion stored) {
        try {
            return verifier().verifyCheckpoint(stored.bytes());
        } catch (DurableEvidenceException ex) {
            log.error("durable store checkpoint {} version {} failed verification: {}",
                    stored.key(), stored.versionId(), ex.getMessage());
            throw unavailable("checkpoint failed verification");
        }
    }

    private VerifiedPending verify(ObjectLockBucket.StoredVersion stored) {
        try {
            return verifier().verifyPending(stored.bytes());
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
            throw unavailable("record " + stored.key() + " is not a format v2 record");
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

    /**
     * The signing key, if it may sign now: not retired and inside its signing window. Checked
     * before an operation reserves anything and again for every signed object.
     */
    private TrustedKey signingKey() {
        if (!signingKey.signsAt(clock.instant())) {
            log.error("durable store signing key {} is retired or outside its signing window", signingKey.keyId());
            throw unavailable("signing key is retired or outside its signing window");
        }
        return signingKey;
    }

    /** Verification as of now: the whole trust, so earlier keys still verify. */
    private DurableErasureEvidenceVerifier verifier() {
        return new DurableErasureEvidenceVerifier(trust, clock.instant());
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
