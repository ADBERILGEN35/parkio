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
import com.parkio.auth.infrastructure.durable.DurableEvidenceBucket.Current;
import com.parkio.auth.infrastructure.durable.DurableEvidenceBucket.RetentionLock;
import com.parkio.auth.infrastructure.durable.DurableEvidenceBucket.StoredVersion;
import com.parkio.auth.infrastructure.durable.DurableEvidenceBucket.Written;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Off-host durable erasure record store on a retention-locked evidence bucket
 * ({@link DurableEvidenceBucket}: MinIO/S3 Object Lock or GCS Object Retention Lock), writing
 * evidence format v2 (docs/operations/recovery-evidence-contract.md). The protocol mirrors the
 * Python model: reserve a sequence with a marker, publish the signed record, then raise both
 * boundaries of the frontier to cover it in one conditional write.
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
 * <p><strong>Conditional writes (#494).</strong> No operation lists the recorded history, so
 * the cost of an operation does not grow with the number of erasures
 * (docs/architecture/erasure-evidence-conditional-writes.md):
 * <ul>
 *   <li>A new sequence starts just above the signed frontier's {@code highestReserved}. Markers,
 *       records and checkpoints are created only if absent. The frontier is raised only after
 *       publication, so a marker found above it belongs to a writer that stopped before
 *       publishing; it is skipped.</li>
 *   <li>A skipped marker gets an index entry for its owner, because the frontier is about to pass
 *       it: {@code reservations/<erasureRequestId>.json}, or
 *       {@code reservations/checkpoints/<sequence>.json} for a checkpoint reservation. Its request
 *       finds its reservation there, the next checkpoint an abandoned checkpoint reservation; both
 *       fill the gap. Index entries are unsigned writer state, not evidence, and the normal path
 *       writes none.</li>
 *   <li>The frontier is read as its live version and replaced only while that version is still
 *       live (compare-and-swap); a live version that fails verification falls back to the highest
 *       verified of all versions, as recovery reads it. Reads never go below a boundary this
 *       process has read or written.</li>
 * </ul>
 *
 * <p>Signed checkpoints of the tombstone ledger (contract stage 2) take sequences from the same
 * space as records; see {@link #publishCheckpoint(ErasureLedgerCapture)}.
 *
 * <p>Writes are serialised in this JVM, and one auth instance may write a bucket at a time: the
 * conditional writes keep a second writer from replacing evidence or the frontier on a stale
 * read, but the reservation index assumes one writer. Any store or verification failure throws
 * {@link AuthErrorCode#DURABLE_RECORDING_UNAVAILABLE}, which leaves the request
 * {@code PENDING_DURABLE} for the retry worker.
 */
public final class ObjectLockDurableErasureRecordStore
        implements DurableErasureRecordStore, DurableErasureCheckpointStore {

    /** Conditional frontier writes before the store gives up on a frontier that keeps changing. */
    static final int FRONTIER_WRITE_ATTEMPTS = 3;

    private static final Logger log = LoggerFactory.getLogger(ObjectLockDurableErasureRecordStore.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final DurableEvidenceBucket bucket;
    private final ObjectLockEvidenceObjects objects;
    private final EvidenceTrust trust;
    private final TrustedKey signingKey;
    private final String databaseIdentity;
    private final EvidenceRetentionMode retentionMode;
    private final Duration retention;
    private final Clock clock;
    private final AtomicInteger reportedIgnoredFrontierVersions = new AtomicInteger();
    /** The highest boundary this process has read or written; frontier reads never go below it. */
    private final AtomicReference<Boundary> highestSeen = new AtomicReference<>(Boundary.NONE);

    /** A frontier's two boundaries, ordered as recovery orders versions: expectedThrough, then highestReserved. */
    private record Boundary(long expectedThrough, long highestReserved) {

        static final Boundary NONE = new Boundary(0, 0);

        static Boundary max(Boundary a, Boundary b) {
            boolean aBelow = a.expectedThrough < b.expectedThrough
                    || (a.expectedThrough == b.expectedThrough && a.highestReserved < b.highestReserved);
            return aBelow ? b : a;
        }
    }

    /**
     * A frontier read: the boundary to build on, the verified live version's boundary (null when no
     * live version verifies) and the token a conditional replace of the live version must name
     * (null when no version is live).
     */
    private record FrontierState(Boundary boundary, Boundary live, String matchToken) {
    }

    /** A reserved checkpoint sequence, and whether it was an abandoned reservation being filled. */
    private record CheckpointReservation(long sequence, boolean abandoned) {
    }

    /**
     * Signs with {@code signingKeyId} from {@code trust} and verifies what it reads with the whole
     * trust, so objects signed with an earlier key still verify after a key rotation.
     */
    ObjectLockDurableErasureRecordStore(DurableEvidenceBucket bucket, EvidenceTrust trust, String signingKeyId,
                                        EvidenceRetentionMode retentionMode, Duration retention, Clock clock) {
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
        Optional<StoredVersion> existing = bucket.oldest(key);
        if (existing.isPresent()) {
            return settle(existing.get(), record, false, readFrontier());
        }
        FrontierState frontier = startingFrontier();
        long sequence = reserveSequence(record.erasureRequestId(), frontier);
        Optional<Written> written = publishIfAbsent(key,
                DurableErasureEvidence.pendingRecord(record, sequence, databaseIdentity, signingKey()));
        StoredVersion canonical = bucket.oldest(key)
                .orElseThrow(() -> unavailable("record " + key + " is not readable after publication"));
        boolean writtenNow = written.isPresent() && canonical.versionId().equals(written.get().versionId());
        return settle(canonical, record, writtenNow, frontier);
    }

    @Override
    public Optional<DurableErasureRecord> findByRequestId(UUID erasureRequestId) {
        requireNoTransaction();
        Optional<StoredVersion> stored = bucket.oldest(DurableErasureEvidence.recordKey(erasureRequestId));
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        VerifiedPending pending = verify(stored.get());
        long expectedThrough = readFrontier().boundary().expectedThrough();
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
        FrontierState frontier = startingFrontier();
        OptionalLong indexed = abandonedCheckpointReservation();
        CheckpointReservation reservation = indexed.isPresent()
                ? new CheckpointReservation(indexed.getAsLong(), true)
                : reserveCheckpointSequence(frontier);
        long sequence = reservation.sequence();
        String key = DurableErasureEvidence.checkpointKey(sequence);
        byte[] bytes = DurableErasureEvidence.checkpoint(sequence, ledger.entries(), databaseIdentity, signingKey());
        Optional<Written> written = publishIfAbsent(key, bytes);
        StoredVersion canonical = bucket.oldest(key)
                .orElseThrow(() -> unavailable("checkpoint " + key + " is not readable after publication"));
        // Bytes equal to these mean this checkpoint is already in place (a write sent twice).
        boolean ours = written.map(w -> canonical.versionId().equals(w.versionId()))
                .orElseGet(() -> Arrays.equals(canonical.bytes(), bytes));
        if (!ours) {
            throw unavailable("checkpoint " + key + " was published by another writer");
        }
        VerifiedCheckpoint verified = verifyCheckpoint(canonical);
        advanceFrontier(frontier, sequence, sequence);
        return new DurableErasureCheckpoint(sequence, ledger.entries().size(), verified.ledgerDigest(),
                ledger.coveredThrough(), reservation.abandoned());
    }

    /** Read access to the bucket's evidence, for recovery checks. */
    public ObjectLockEvidenceObjects evidence() {
        return objects;
    }

    private DurableErasurePutResult settle(StoredVersion stored, DurableErasureRecord candidate,
                                           boolean writtenNow, FrontierState frontier) {
        VerifiedPending pending = verify(stored);
        DurableErasureRecord existing = toRecord(pending, stored);
        DurableErasureReceipt receipt = receipt(stored);
        if (!existing.bodyDigest().equals(candidate.bodyDigest())) {
            return DurableErasurePutResult.conflict(existing, receipt);
        }
        advanceFrontier(frontier, pending.sequence(), pending.sequence());
        return writtenNow
                ? DurableErasurePutResult.created(existing, receipt)
                : DurableErasurePutResult.existing(existing, receipt);
    }

    /** The canonical version's id, the SHA-256 of its bytes, and the lock the store reports on it. */
    private DurableErasureReceipt receipt(StoredVersion stored) {
        RetentionLock retention = bucket.retention(stored.key(), stored.versionId());
        return new DurableErasureReceipt(stored.versionId(), sha256Hex(stored.bytes()), retention.mode().name(),
                retention.retainUntil());
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    /**
     * Python's SequenceAllocator: this request's reservation, else the next free sequence above
     * the frontier's {@code highestReserved}. The frontier is raised only after the record is
     * published, so an unpublished marker of this request is either still above the frontier,
     * where this walk meets it again, or was passed by a later writer, which indexed it.
     */
    private long reserveSequence(UUID erasureRequestId, FrontierState frontier) {
        OptionalLong reserved = indexedReservation(erasureRequestId);
        if (reserved.isPresent()) {
            return reserved.getAsLong();
        }
        for (long next = frontier.boundary().highestReserved() + 1; ; next++) {
            UUID owner = claimMarker(next, erasureRequestId).owner();
            if (owner.equals(erasureRequestId)) {
                return next;
            }
            indexForItsOwner(next, owner);
        }
    }

    /**
     * The next free sequence above the frontier's {@code highestReserved}, reserved with a
     * checkpoint marker. A checkpoint marker found on the way without its checkpoint is an
     * abandoned checkpoint reservation that no writer has passed yet; it is filled.
     */
    private CheckpointReservation reserveCheckpointSequence(FrontierState frontier) {
        UUID checkpoint = DurableErasureEvidence.CHECKPOINT_RESERVATION;
        for (long next = frontier.boundary().highestReserved() + 1; ; next++) {
            Claim claim = claimMarker(next, checkpoint);
            if (claim.owner().equals(checkpoint)
                    && (claim.created() || bucket.oldest(DurableErasureEvidence.checkpointKey(next)).isEmpty())) {
                return new CheckpointReservation(next, !claim.created());
            }
            indexForItsOwner(next, claim.owner());
        }
    }

    /** A marker's canonical owner, and whether this call created that canonical version. */
    private record Claim(UUID owner, boolean created) {
    }

    /**
     * Creates the marker of {@code sequence} for {@code owner} unless one exists. The first
     * version stays canonical, so the owner is read back.
     */
    private Claim claimMarker(long sequence, UUID owner) {
        String key = DurableErasureEvidence.sequenceKey(sequence);
        Optional<Written> written = publishIfAbsent(key, DurableErasureEvidence.sequenceMarker(sequence, owner));
        StoredVersion canonical = bucket.oldest(key)
                .orElseThrow(() -> unavailable("marker " + key + " is not readable after its reservation"));
        return new Claim(markerOwner(canonical),
                written.isPresent() && canonical.versionId().equals(written.get().versionId()));
    }

    /**
     * A marker above the frontier that is not ours belongs to a reservation whose writer stopped
     * before publishing. This walk is about to raise the frontier past it, after which no walk
     * meets it again, so it is indexed for its owner: its request (or the next checkpoint) finds
     * it there and fills the gap.
     */
    private void indexForItsOwner(long sequence, UUID owner) {
        if (owner.equals(DurableErasureEvidence.CHECKPOINT_RESERVATION)) {
            publishIfAbsent(DurableErasureEvidence.checkpointReservationKey(sequence),
                    DurableErasureEvidence.checkpointReservation(sequence));
        } else {
            publishIfAbsent(DurableErasureEvidence.reservationKey(owner),
                    DurableErasureEvidence.sequenceReservation(sequence, owner));
        }
    }

    /** The sequence this request's index entry names, after checking that its marker names the request. */
    private OptionalLong indexedReservation(UUID erasureRequestId) {
        String key = DurableErasureEvidence.reservationKey(erasureRequestId);
        Optional<StoredVersion> entry = bucket.oldest(key);
        if (entry.isEmpty()) {
            return OptionalLong.empty();
        }
        long sequence = indexedSequence(entry.get());
        StoredVersion marker = bucket.oldest(DurableErasureEvidence.sequenceKey(sequence))
                .orElseThrow(() -> unavailable("index entry " + key + " names a sequence without a marker"));
        if (!erasureRequestId.equals(markerOwner(marker))) {
            throw unavailable("index entry " + key + " names another request's marker");
        }
        return OptionalLong.of(sequence);
    }

    /**
     * The lowest sequence reserved for a checkpoint that has no checkpoint, if any. It lists the
     * checkpoint index and the checkpoints, which grow with checkpoints, not with records.
     */
    private OptionalLong abandonedCheckpointReservation() {
        TreeSet<Long> reserved = new TreeSet<>();
        for (String key : bucket.keys(DurableErasureEvidence.CHECKPOINT_RESERVATIONS_PREFIX)) {
            reserved.add(sequenceOf(key));
        }
        if (reserved.isEmpty()) {
            return OptionalLong.empty();
        }
        for (String key : bucket.keys("checkpoints/")) {
            reserved.remove(sequenceOf(key));
        }
        if (reserved.isEmpty()) {
            return OptionalLong.empty();
        }
        long sequence = reserved.first();
        StoredVersion marker = bucket.oldest(DurableErasureEvidence.sequenceKey(sequence))
                .orElseThrow(() -> unavailable("checkpoint reservation " + sequence + " has no marker"));
        if (!DurableErasureEvidence.CHECKPOINT_RESERVATION.equals(markerOwner(marker))) {
            throw unavailable("checkpoint reservation " + sequence + " names a request's marker");
        }
        return OptionalLong.of(sequence);
    }

    /**
     * The frontier to build on. Normally one read of the live version. If that version does not
     * verify, or no version is live although versions exist, it is the highest verified of all
     * versions, as recovery reads it. Never below a boundary this process has read or written.
     */
    private FrontierState readFrontier() {
        Optional<Current> live = bucket.current(DurableErasureEvidence.FRONTIER_KEY);
        Optional<VerifiedFrontier> liveVerified = live.flatMap(this::verifiedLiveFrontier);
        Optional<VerifiedFrontier> verified = liveVerified;
        if (verified.isEmpty() && (live.isPresent() || bucket.versionCount(DurableErasureEvidence.FRONTIER_KEY) > 0)) {
            verified = highestVerifiedFrontier();
        }
        Boundary read = verified.map(ObjectLockDurableErasureRecordStore::boundary).orElse(Boundary.NONE);
        return new FrontierState(highestSeen.accumulateAndGet(read, Boundary::max),
                liveVerified.map(ObjectLockDurableErasureRecordStore::boundary).orElse(null),
                live.map(Current::matchToken).orElse(null));
    }

    /**
     * The frontier a reservation starts from. Without a live version it writes one first: the
     * boundary {@code (0, 0)} in a new bucket, so that a reservation lost before its object
     * leaves a present but incomplete boundary (BLOCKED) instead of none (UNKNOWN), as the Python
     * model does; else the highest verified version again, if the live one was deleted.
     */
    private FrontierState startingFrontier() {
        FrontierState frontier = readFrontier();
        return frontier.matchToken() == null ? advanceFrontier(frontier, null, 0) : frontier;
    }

    private Optional<VerifiedFrontier> verifiedLiveFrontier(Current live) {
        try {
            return verifier().verifyFrontier(Optional.of(live.version().bytes()));
        } catch (DurableEvidenceException ex) {
            log.warn("durable store live frontier version {} failed verification ({}); reading every version",
                    live.version().versionId(), ex.getMessage());
            return Optional.empty();
        }
    }

    /** The highest verified frontier version, as recovery reads it; ignored versions are logged. */
    private Optional<VerifiedFrontier> highestVerifiedFrontier() {
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

    private static Boundary boundary(VerifiedFrontier frontier) {
        return new Boundary(frontier.expectedThrough(), frontier.highestReserved());
    }

    /**
     * Raises the signed boundary; it never decreases and highestReserved never trails it. The
     * write names the live version it builds on; if that version is no longer live, the frontier
     * is read again and the write repeated, at most {@link #FRONTIER_WRITE_ATTEMPTS} times. A
     * frontier read that already shows the boundary to write ends the loop without a write.
     */
    private FrontierState advanceFrontier(FrontierState frontier, Long expectedThrough, long highestReserved) {
        FrontierState current = frontier;
        for (int attempt = 1; ; attempt++) {
            Boundary base = current.boundary();
            long newExpected = expectedThrough == null
                    ? base.expectedThrough()
                    : Math.max(base.expectedThrough(), expectedThrough);
            long newReserved = Math.max(Math.max(base.highestReserved(), highestReserved), newExpected);
            Boundary next = new Boundary(newExpected, newReserved);
            if (current.matchToken() != null && next.equals(current.live())) {
                return current;
            }
            byte[] bytes = DurableErasureEvidence.frontier(newExpected, newReserved, databaseIdentity, signingKey());
            Optional<Written> written = current.matchToken() == null
                    ? publishIfAbsent(DurableErasureEvidence.FRONTIER_KEY, bytes)
                    : bucket.putIfCurrent(DurableErasureEvidence.FRONTIER_KEY, bytes, current.matchToken(),
                            retentionMode, retainUntil());
            if (written.isPresent()) {
                return new FrontierState(highestSeen.accumulateAndGet(next, Boundary::max), next,
                        written.get().matchToken());
            }
            if (attempt == FRONTIER_WRITE_ATTEMPTS) {
                throw unavailable("frontier kept changing while it was written");
            }
            current = readFrontier();
        }
    }

    private VerifiedCheckpoint verifyCheckpoint(StoredVersion stored) {
        try {
            return verifier().verifyCheckpoint(stored.bytes());
        } catch (DurableEvidenceException ex) {
            log.error("durable store checkpoint {} version {} failed verification: {}",
                    stored.key(), stored.versionId(), ex.getMessage());
            throw unavailable("checkpoint failed verification");
        }
    }

    private VerifiedPending verify(StoredVersion stored) {
        try {
            return verifier().verifyPending(stored.bytes());
        } catch (DurableEvidenceException ex) {
            log.error("durable store record {} version {} failed verification: {}",
                    stored.key(), stored.versionId(), ex.getMessage());
            throw unavailable("record failed verification");
        }
    }

    private static DurableErasureRecord toRecord(VerifiedPending pending, StoredVersion stored) {
        try {
            JsonNode body = JSON.readTree(stored.bytes());
            return new DurableErasureRecord(UUID.fromString(pending.erasureRequestId()),
                    UUID.fromString(pending.authUserId()), Instant.parse(pending.erasedAt()),
                    body.path("bodyDigest").asText());
        } catch (IOException | RuntimeException ex) {
            throw unavailable("record " + stored.key() + " is not a format v2 record");
        }
    }

    private static UUID markerOwner(StoredVersion marker) {
        try {
            JsonNode body = JSON.readTree(marker.bytes());
            if (!DurableErasureEvidence.KIND_SEQUENCE_ALLOCATION.equals(body.path("kind").asText())) {
                throw unavailable("marker " + marker.key() + " is not a sequence marker");
            }
            return UUID.fromString(body.path("erasureRequestId").asText());
        } catch (IOException | IllegalArgumentException ex) {
            throw unavailable("marker " + marker.key() + " is unreadable");
        }
    }

    private static long indexedSequence(StoredVersion entry) {
        try {
            JsonNode sequence = JSON.readTree(entry.bytes()).path("sequence");
            if (!sequence.canConvertToLong() || sequence.asLong() < 1) {
                throw unavailable("index entry " + entry.key() + " has no sequence");
            }
            return sequence.asLong();
        } catch (IOException ex) {
            throw unavailable("index entry " + entry.key() + " is unreadable");
        }
    }

    private static long sequenceOf(String key) {
        String name = key.substring(key.lastIndexOf('/') + 1).replace(".json", "");
        try {
            return Long.parseLong(name);
        } catch (NumberFormatException ex) {
            throw unavailable("unexpected sequence key " + key);
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

    /** Every version is written under the configured retention lock, and only if the key has none. */
    private Optional<Written> publishIfAbsent(String key, byte[] bytes) {
        return bucket.putIfAbsent(key, bytes, retentionMode, retainUntil());
    }

    private ZonedDateTime retainUntil() {
        return ZonedDateTime.ofInstant(clock.instant().plus(retention), ZoneOffset.UTC);
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
