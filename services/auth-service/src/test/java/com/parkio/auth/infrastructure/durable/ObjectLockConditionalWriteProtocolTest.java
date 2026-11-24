package com.parkio.auth.infrastructure.durable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.cloud.storage.StorageException;
import com.parkio.auth.application.durable.DurableErasureEvidence;
import com.parkio.auth.application.durable.DurableErasureEvidenceVerifier;
import com.parkio.auth.application.durable.DurableErasureEvidenceVerifier.RecoveryVerdict;
import com.parkio.auth.application.durable.DurableErasureEvidenceVerifier.Verdict;
import com.parkio.auth.application.durable.ErasureLedgerEntry;
import com.parkio.auth.application.durable.EvidenceTrust;
import com.parkio.auth.application.durable.TrustedKey;
import com.parkio.auth.application.port.CapturedErasureLedger;
import com.parkio.auth.application.port.DurableErasureCheckpoint;
import com.parkio.auth.application.port.DurableErasureRecord;
import com.parkio.auth.domain.exception.AuthErrorCode;
import com.parkio.auth.domain.exception.AuthException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The conditional-write protocol of {@link ObjectLockDurableErasureRecordStore} (#494) on the GCS
 * adapter over the in-memory transport: what a writer that stops between its steps leaves, how
 * the next writer and the retry complete it, and how the frontier's compare-and-swap behaves.
 * Recovery is read with the verifier, as an operator would read the bucket.
 */
class ObjectLockConditionalWriteProtocolTest {

    private static final String DATABASE = "postgresql:7000000000000000010:parkio_auth";
    private static final byte[] SECRET = "durable-protocol-test-hmac-key-not-a-secret!!".getBytes(StandardCharsets.UTF_8);
    private static final TrustedKey PRODUCER = TrustedKey.active("protocol-test-key-2026a", "auth-protocol-test", SECRET,
            Instant.parse("2026-01-01T00:00:00Z"));
    private static final EvidenceTrust TRUST = new EvidenceTrust(DATABASE, List.of(PRODUCER));
    private static final Duration RETENTION = Duration.ofHours(2);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-24T08:00:00Z"), ZoneOffset.UTC);
    private static final Instant ERASED_AT = Instant.parse("2026-11-24T07:00:00Z");

    private InMemoryGcsObjectRetentionTransport memory;
    private FaultyTransport faulty;
    private GcsObjectRetentionBucket bucket;
    private ObjectLockDurableErasureRecordStore store;

    @BeforeEach
    void setUp() {
        memory = new InMemoryGcsObjectRetentionTransport("parkio-erasure-protocol-test");
        faulty = new FaultyTransport(memory);
        bucket = new GcsObjectRetentionBucket(faulty);
        store = new ObjectLockDurableErasureRecordStore(bucket, TRUST, PRODUCER.keyId(), EvidenceRetentionMode.GOVERNANCE,
                RETENTION, CLOCK);
    }

    @Test
    void aReservationWhoseRecordWasLostIsIndexedByTheNextWriterAndFilledByItsRetry() {
        DurableErasureRecord first = record(1);
        DurableErasureRecord second = record(2);
        faulty.failNextCreateOf("records/");

        assertUnavailable(() -> store.putIfAbsent(first));
        // The bucket's first reservation wrote the boundary (0, 0) before its marker.
        RecoveryVerdict lost = recover();
        assertThat(lost.verdict()).isEqualTo(Verdict.BLOCKED);
        assertThat(lost.expectedThrough()).isZero();
        assertThat(lost.abandonedReservations()).containsExactly(1L);

        assertThat(store.putIfAbsent(second).created()).isTrue();
        assertThat(bucket.oldest(DurableErasureEvidence.reservationKey(first.erasureRequestId())).orElseThrow().bytes())
                .isEqualTo(DurableErasureEvidence.sequenceReservation(1, first.erasureRequestId()));
        RecoveryVerdict gap = recover();
        assertThat(gap.verdict()).isEqualTo(Verdict.BLOCKED);
        assertThat(gap.expectedThrough()).isEqualTo(2L);
        assertThat(gap.gaps()).containsExactly(1L);

        assertThat(store.putIfAbsent(first).created()).isTrue();
        assertThat(store.findByRequestId(first.erasureRequestId())).contains(first);
        RecoveryVerdict complete = recover();
        assertThat(complete.verdict()).isEqualTo(Verdict.ACCEPT_ISOLATED);
        assertThat(complete.expectedThrough()).isEqualTo(2L);
        assertThat(complete.abandonedReservations()).isEmpty();
        assertThat(bucket.keys("sequences/")).hasSize(2);
    }

    @Test
    void aRetryWhoseMarkerIsStillAboveTheFrontierTakesItAgainWithoutAnIndexEntry() {
        DurableErasureRecord first = record(1);
        faulty.failNextCreateOf("records/");
        assertUnavailable(() -> store.putIfAbsent(first));

        assertThat(store.putIfAbsent(first).created()).isTrue();

        assertThat(bucket.keys("sequences/")).containsExactly(DurableErasureEvidence.sequenceKey(1));
        assertThat(bucket.keys(DurableErasureEvidence.RESERVATIONS_PREFIX)).isEmpty();
        assertThat(recover().verdict()).isEqualTo(Verdict.ACCEPT_ISOLATED);
    }

    @Test
    void aCheckpointReservationPassedByARecordIsIndexedAndFilledByTheNextCheckpoint() {
        DurableErasureRecord first = record(1);
        assertThat(store.putIfAbsent(first).created()).isTrue();
        faulty.failNextCreateOf("checkpoints/");
        assertUnavailable(() -> store.publishCheckpoint(() -> ledger(first)));

        DurableErasureRecord second = record(2);
        assertThat(store.putIfAbsent(second).created()).isTrue();
        assertThat(store.findByRequestId(second.erasureRequestId())).contains(second);
        assertThat(bucket.oldest(DurableErasureEvidence.checkpointReservationKey(2))).isPresent();
        RecoveryVerdict gap = recover();
        assertThat(gap.verdict()).isEqualTo(Verdict.BLOCKED);
        assertThat(gap.gaps()).containsExactly(2L);

        DurableErasureCheckpoint filled = store.publishCheckpoint(() -> ledger(first));
        assertThat(filled.sequence()).isEqualTo(2);
        assertThat(filled.filledReservation()).isTrue();
        RecoveryVerdict complete = recover();
        assertThat(complete.verdict()).isEqualTo(Verdict.ACCEPT_ISOLATED);
        assertThat(complete.expectedThrough()).isEqualTo(3L);
    }

    @Test
    void aCheckpointReservationStillAboveTheFrontierIsFilledByTheNextCheckpoint() {
        DurableErasureRecord first = record(1);
        assertThat(store.putIfAbsent(first).created()).isTrue();
        faulty.failNextCreateOf("checkpoints/");
        assertUnavailable(() -> store.publishCheckpoint(() -> ledger(first)));

        DurableErasureCheckpoint filled = store.publishCheckpoint(() -> ledger(first));

        assertThat(filled.sequence()).isEqualTo(2);
        assertThat(filled.filledReservation()).isTrue();
        assertThat(bucket.keys(DurableErasureEvidence.RESERVATIONS_PREFIX)).isEmpty();
        assertThat(recover().expectedThrough()).isEqualTo(2L);
    }

    @Test
    void aLiveFrontierThatFailsVerificationIsReplacedOnTopOfTheHighestVerifiedVersion() {
        assertThat(store.putIfAbsent(record(1)).created()).isTrue();
        memory.createGeneration(DurableErasureEvidence.FRONTIER_KEY,
                "{\"kind\":\"not-a-frontier\"}".getBytes(StandardCharsets.UTF_8), EvidenceRetentionMode.GOVERNANCE,
                CLOCK.instant().plus(RETENTION), OptionalLong.empty());

        assertThat(store.putIfAbsent(record(2)).created()).isTrue();

        byte[] live = bucket.current(DurableErasureEvidence.FRONTIER_KEY).orElseThrow().version().bytes();
        assertThat(verifier().verifyFrontier(Optional.of(live)).orElseThrow().expectedThrough()).isEqualTo(2L);
        RecoveryVerdict recovered = recover();
        assertThat(recovered.verdict()).isEqualTo(Verdict.ACCEPT_ISOLATED);
        assertThat(recovered.expectedThrough()).isEqualTo(2L);
        assertThat(recovered.ignoredFrontierVersions()).isEqualTo(1);
    }

    @Test
    void aFrontierReplacedBetweenReadAndWriteIsReadAgain() {
        assertThat(store.putIfAbsent(record(1)).created()).isTrue();
        faulty.replaceFrontierBeforeNextConditionalWrite(false);

        assertThat(store.putIfAbsent(record(2)).created()).isTrue();

        assertThat(faulty.preconditionFailures).isEqualTo(1);
        assertThat(recover().expectedThrough()).isEqualTo(2L);
    }

    @Test
    void aFrontierReadNeverGoesBelowABoundaryThisProcessHasSeen() {
        DurableErasureRecord second = record(2);
        assertThat(store.putIfAbsent(record(1)).created()).isTrue();
        assertThat(store.putIfAbsent(second).created()).isTrue();
        // An older valid version becomes live again: a replayed frontier, or MinIO after a backward
        // clock step.
        memory.createGeneration(DurableErasureEvidence.FRONTIER_KEY,
                DurableErasureEvidence.frontier(1, 1, DATABASE, PRODUCER), EvidenceRetentionMode.GOVERNANCE,
                CLOCK.instant().plus(RETENTION), OptionalLong.empty());

        assertThat(store.findByRequestId(second.erasureRequestId())).contains(second);
        assertThat(store.putIfAbsent(record(3)).created()).isTrue();

        // The walk started above sequence 2: it passed no marker, so it indexed none.
        assertThat(bucket.keys(DurableErasureEvidence.RESERVATIONS_PREFIX)).isEmpty();
        byte[] live = bucket.current(DurableErasureEvidence.FRONTIER_KEY).orElseThrow().version().bytes();
        assertThat(verifier().verifyFrontier(Optional.of(live)).orElseThrow().expectedThrough()).isEqualTo(3L);
        assertThat(recover().expectedThrough()).isEqualTo(3L);
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void aFrontierThatKeepsChangingFailsTheCallClosedAndTheRetryCompletesIt() {
        DurableErasureRecord first = record(1);
        assertThat(store.putIfAbsent(first).created()).isTrue();
        DurableErasureRecord second = record(2);
        faulty.replaceFrontierBeforeNextConditionalWrite(true);

        assertThatThrownBy(() -> store.putIfAbsent(second))
                .isInstanceOf(AuthException.class)
                .hasMessage("durable store: frontier kept changing while it was written")
                .extracting(ex -> ((AuthException) ex).errorCode())
                .isEqualTo(AuthErrorCode.DURABLE_RECORDING_UNAVAILABLE);
        assertThat(faulty.preconditionFailures).isEqualTo(ObjectLockDurableErasureRecordStore.FRONTIER_WRITE_ATTEMPTS);
        assertThat(store.findByRequestId(second.erasureRequestId())).isEmpty();

        faulty.stopReplacingFrontier();
        assertThat(store.putIfAbsent(second).created()).isFalse();
        assertThat(store.findByRequestId(second.erasureRequestId())).contains(second);
    }

    @Test
    void aFrontierWriteResentAfterALostResponseFindsItselfLive() {
        assertThat(store.putIfAbsent(record(1)).created()).isTrue();
        faulty.resendNextConditionalFrontierWrite();

        assertThat(store.putIfAbsent(record(2)).created()).isTrue();

        assertThat(faulty.preconditionFailures).isEqualTo(1);
        assertThat(bucket.versionCount(DurableErasureEvidence.FRONTIER_KEY)).isEqualTo(3);
        assertThat(recover().expectedThrough()).isEqualTo(2L);
    }

    private RecoveryVerdict recover() {
        return verifier().recover(store.evidence(), null);
    }

    private static DurableErasureEvidenceVerifier verifier() {
        return new DurableErasureEvidenceVerifier(TRUST, CLOCK.instant());
    }

    private static DurableErasureRecord record(int n) {
        return DurableErasureRecord.of(
                UUID.nameUUIDFromBytes(("protocol-request-" + n).getBytes(StandardCharsets.UTF_8)),
                UUID.nameUUIDFromBytes(("protocol-user-" + n).getBytes(StandardCharsets.UTF_8)), ERASED_AT);
    }

    private static CapturedErasureLedger ledger(DurableErasureRecord... records) {
        return new CapturedErasureLedger(List.of(records).stream()
                .map(record -> new ErasureLedgerEntry(record.authUserId(), record.erasedAt())).toList(), CLOCK.instant());
    }

    private static void assertUnavailable(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(AuthException.class)
                .extracting(ex -> ((AuthException) ex).errorCode())
                .isEqualTo(AuthErrorCode.DURABLE_RECORDING_UNAVAILABLE);
    }

    /**
     * Fails the next create under a prefix, as a writer that stops there would; replaces the live
     * frontier just before a conditional frontier write, as a second writer would; or commits a
     * conditional frontier write and sends it again, as the client does after a lost response.
     */
    private final class FaultyTransport implements GcsObjectRetentionTransport {

        int preconditionFailures;
        private final GcsObjectRetentionTransport delegate;
        private String failNextCreateOf;
        private boolean replaceFrontier;
        private boolean keepReplacing;
        private boolean resendNextFrontierWrite;

        FaultyTransport(GcsObjectRetentionTransport delegate) {
            this.delegate = delegate;
        }

        void failNextCreateOf(String prefix) {
            failNextCreateOf = prefix;
        }

        void replaceFrontierBeforeNextConditionalWrite(boolean everyTime) {
            replaceFrontier = true;
            keepReplacing = everyTime;
        }

        void stopReplacingFrontier() {
            replaceFrontier = false;
            keepReplacing = false;
        }

        void resendNextConditionalFrontierWrite() {
            resendNextFrontierWrite = true;
        }

        @Override
        public String bucket() {
            return delegate.bucket();
        }

        @Override
        public void requireObjectRetentionEnabled() {
            delegate.requireObjectRetentionEnabled();
        }

        @Override
        public long createGeneration(String objectName, byte[] bytes, EvidenceRetentionMode mode, Instant retainUntil,
                                     OptionalLong ifGenerationMatch) {
            if (failNextCreateOf != null && objectName.startsWith(failNextCreateOf)) {
                failNextCreateOf = null;
                throw new StorageException(503, "the test writer stopped here");
            }
            boolean conditionalFrontierWrite = objectName.equals(DurableErasureEvidence.FRONTIER_KEY)
                    && ifGenerationMatch.isPresent() && ifGenerationMatch.getAsLong() != 0;
            if (conditionalFrontierWrite && replaceFrontier) {
                replaceFrontier = keepReplacing;
                byte[] live = delegate.liveGeneration(objectName).orElseThrow().bytes();
                delegate.createGeneration(objectName, live, mode, retainUntil, OptionalLong.empty());
            }
            if (conditionalFrontierWrite && resendNextFrontierWrite) {
                resendNextFrontierWrite = false;
                delegate.createGeneration(objectName, bytes, mode, retainUntil, ifGenerationMatch);
            }
            try {
                return delegate.createGeneration(objectName, bytes, mode, retainUntil, ifGenerationMatch);
            } catch (StorageException ex) {
                if (ex.getCode() == PRECONDITION_FAILED) {
                    preconditionFailures++;
                }
                throw ex;
            }
        }

        @Override
        public Optional<GenerationBytes> liveGeneration(String objectName) {
            return delegate.liveGeneration(objectName);
        }

        @Override
        public RetentionState retention(String objectName, long generation) {
            return delegate.retention(objectName, generation);
        }

        @Override
        public ListPage listGenerations(String prefix, Optional<String> pageToken, int pageSize) {
            return delegate.listGenerations(prefix, pageToken, pageSize);
        }

        @Override
        public byte[] readGeneration(String objectName, long generation) {
            return delegate.readGeneration(objectName, generation);
        }
    }
}
