package com.parkio.media.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.parkio.media.application.port.ErasureAckOutbox;
import com.parkio.media.application.port.MediaStoragePort;
import com.parkio.media.application.port.MediaStoragePort.StoredVersion;
import com.parkio.media.domain.event.UserErasureAcknowledgedEvent;
import com.parkio.media.infrastructure.persistence.MediaErasureJobStore;
import com.parkio.media.infrastructure.persistence.MediaErasureJobStore.Claim;
import com.parkio.media.infrastructure.persistence.MediaErasureJobStore.Job;
import com.parkio.media.infrastructure.persistence.MediaErasureJobStore.ObjectWrite;
import com.parkio.media.infrastructure.persistence.MediaErasureJobStore.StoredMedia;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

class MediaObjectErasureWorkerTest {

    private static final Instant NOW = Instant.parse("2026-08-14T09:00:00Z");
    private static final Instant LEASE_END = NOW.plus(Duration.ofMinutes(2));
    private static final String BUCKET = "parkio-media";

    private final MediaErasureJobStore jobs = mock(MediaErasureJobStore.class);
    private final MediaStoragePort storage = mock(MediaStoragePort.class);
    private final ErasureAckOutbox ackOutbox = mock(ErasureAckOutbox.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final UUID jobId = UUID.randomUUID();
    private final UUID token = UUID.randomUUID();
    private final UUID requestId = UUID.randomUUID();
    private final UUID owner = UUID.randomUUID();
    private final String namespace = "media/" + owner + "/";
    private MediaObjectErasureWorker worker;

    @BeforeEach
    void setUp() {
        when(transactions.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        worker = worker(Clock.fixed(NOW, ZoneOffset.UTC));
        when(jobs.claim(eq(jobId), any(), any())).thenReturn(Optional.of(token));
        when(jobs.find(jobId)).thenReturn(Optional.of(new Job(jobId, requestId, owner, 0)));
        when(jobs.lockClaim(eq(jobId), eq(token), any())).thenReturn(true);
    }

    @Test
    void successAckIsQueuedOnlyAfterEveryVersionIsConfirmedGoneUnderTheOwnerFenceAndALiveClaim() {
        StoredMedia a = media("a.png");
        StoredMedia b = media("b.png");
        StoredVersion a1 = version(a, "v1", false);
        StoredVersion a2 = version(a, "v2", false);
        StoredVersion marker = version(b, "v3", true);
        StoredVersion orphan = new StoredVersion(BUCKET, namespace + "orphan.png", "null", false);
        when(jobs.remainingMedia(owner)).thenReturn(List.of(a, b));
        when(storage.versionsOf(BUCKET, a.objectKey())).thenReturn(List.of(a1, a2), List.of());
        when(storage.versionsOf(BUCKET, b.objectKey())).thenReturn(List.of(marker), List.of());
        when(storage.versionsUnder(namespace)).thenReturn(List.of(orphan), List.of());
        when(jobs.countMedia(owner)).thenReturn(0L);

        assertThat(worker.process(jobId)).isEqualTo(MediaObjectErasureWorker.Outcome.ACK_QUEUED);

        verify(jobs).claim(jobId, NOW, LEASE_END);
        InOrder order = inOrder(storage, jobs, ackOutbox);
        order.verify(storage).removeVersion(a1);
        order.verify(storage).removeVersion(a2);
        order.verify(jobs).deleteMedia(a.mediaId());
        order.verify(storage).removeVersion(marker);
        order.verify(jobs).deleteMedia(b.mediaId());
        order.verify(storage).removeVersion(orphan);
        order.verify(jobs).holdOwner(owner);
        order.verify(jobs).lockClaim(jobId, token, NOW);
        order.verify(jobs).countMedia(owner);
        order.verify(jobs).deleteIdempotencyRecords(owner);
        order.verify(ackOutbox).append(any());
        order.verify(jobs).delete(jobId);
        ArgumentCaptor<UserErasureAcknowledgedEvent> ack = ArgumentCaptor.forClass(UserErasureAcknowledgedEvent.class);
        verify(ackOutbox).append(ack.capture());
        assertThat(ack.getValue().eventId()).isEqualTo(jobId);
        assertThat(ack.getValue().erasureRequestId()).isEqualTo(requestId);
        assertThat(ack.getValue().authUserId()).isEqualTo(owner);
        assertThat(ack.getValue().serviceName()).isEqualTo("media");
        assertThat(ack.getValue().status()).isEqualTo("SUCCESS");
    }

    @Test
    void anExpiredOrReclaimedClaimNeverQueuesTheAck() {
        when(jobs.remainingMedia(owner)).thenReturn(List.of());
        when(storage.versionsUnder(namespace)).thenReturn(List.of());
        when(jobs.lockClaim(eq(jobId), eq(token), any())).thenReturn(false);

        assertThat(worker.process(jobId)).isEqualTo(MediaObjectErasureWorker.Outcome.RETRY_SCHEDULED);

        verify(jobs).holdOwner(owner);
        verify(jobs, never()).countMedia(any());
        verify(ackOutbox, never()).append(any());
        verify(jobs, never()).delete(any());
    }

    @Test
    void aJobClaimedByAnotherAttemptIsLeftToIt() {
        when(jobs.claim(eq(jobId), any(), any())).thenReturn(Optional.empty());

        assertThat(worker.process(jobId)).isEqualTo(MediaObjectErasureWorker.Outcome.RETRY_SCHEDULED);

        verify(jobs, never()).remainingMedia(any());
        verify(storage, never()).versionsUnder(anyString());
        verify(ackOutbox, never()).append(any());
    }

    @Test
    void unknownJobIsLeftAlone() {
        when(jobs.claim(eq(jobId), any(), any())).thenReturn(Optional.empty());
        when(jobs.find(jobId)).thenReturn(Optional.empty());

        assertThat(worker.process(jobId)).isEqualTo(MediaObjectErasureWorker.Outcome.NOT_PENDING);

        verify(storage, never()).versionsOf(anyString(), anyString());
        verify(storage, never()).removeVersion(any());
        verify(ackOutbox, never()).append(any());
    }

    @Test
    void anObjectStillListedAfterADeleteThatReturnedNormallyIsNotErased() {
        StoredMedia a = media("a.png");
        StoredVersion a1 = version(a, "v1", false);
        when(jobs.remainingMedia(owner)).thenReturn(List.of(a));
        when(storage.versionsOf(BUCKET, a.objectKey())).thenReturn(List.of(a1), List.of(a1));

        assertThat(worker.process(jobId)).isEqualTo(MediaObjectErasureWorker.Outcome.RETRY_SCHEDULED);

        verify(storage).removeVersion(a1);
        verify(jobs, never()).deleteMedia(any());
        verify(storage, never()).versionsUnder(anyString());
        verify(jobs).scheduleRetry(eq(jobId), eq(token), contains("still present after delete"),
                eq(NOW.plus(Duration.ofSeconds(5))), eq(NOW));
        verify(ackOutbox, never()).append(any());
        verify(jobs, never()).delete(any());
    }

    @Test
    void versionsAreListedPageByPageUntilAFreshListingIsEmpty() {
        StoredMedia a = media("a.png");
        StoredVersion a1 = version(a, "v1", false);
        StoredVersion a2 = version(a, "v2", false);
        StoredVersion a3 = version(a, "v3", false);
        when(jobs.remainingMedia(owner)).thenReturn(List.of(a));
        when(storage.versionsOf(BUCKET, a.objectKey())).thenReturn(List.of(a1, a2), List.of(a3), List.of());
        when(storage.versionsUnder(namespace)).thenReturn(List.of());
        when(jobs.countMedia(owner)).thenReturn(0L);

        assertThat(worker.process(jobId)).isEqualTo(MediaObjectErasureWorker.Outcome.ACK_QUEUED);

        verify(storage).removeVersion(a1);
        verify(storage).removeVersion(a2);
        verify(storage).removeVersion(a3);
        verify(jobs).deleteMedia(a.mediaId());
    }

    @Test
    void anAttemptStopsAtItsDeadlineBeforeTheNextStorageCallAndKeepsTheJobDue() {
        MutableClock clock = new MutableClock(NOW);
        MediaObjectErasureWorker slow = worker(clock);
        StoredMedia a = media("a.png");
        List<StoredVersion> versions = List.of(version(a, "v1", false), version(a, "v2", false),
                version(a, "v3", false), version(a, "v4", false), version(a, "v5", false));
        when(jobs.remainingMedia(owner)).thenReturn(List.of(a));
        when(storage.versionsOf(BUCKET, a.objectKey())).thenReturn(versions);
        // Every delete takes 25 s: the 60 s budget allows three deletes, then the attempt stops.
        doAnswer(invocation -> {
            clock.advance(Duration.ofSeconds(25));
            return null;
        }).when(storage).removeVersion(any());

        assertThat(slow.process(jobId)).isEqualTo(MediaObjectErasureWorker.Outcome.RETRY_SCHEDULED);

        verify(storage, org.mockito.Mockito.times(3)).removeVersion(any());
        verify(jobs, never()).deleteMedia(any());
        verify(storage, never()).versionsUnder(anyString());
        verify(jobs).release(jobId, token, NOW.plusSeconds(75), NOW.plusSeconds(75));
        verify(jobs, never()).scheduleRetry(any(), any(), anyString(), any(), any());
        verify(ackOutbox, never()).append(any());
    }

    @Test
    void theOrphanNamespaceSweepAlsoStopsAtTheDeadline() {
        MutableClock clock = new MutableClock(NOW);
        MediaObjectErasureWorker slow = worker(clock);
        when(jobs.remainingMedia(owner)).thenReturn(List.of());
        List<StoredVersion> orphans = List.of(
                new StoredVersion(BUCKET, namespace + "1.png", "null", false),
                new StoredVersion(BUCKET, namespace + "2.png", "null", false),
                new StoredVersion(BUCKET, namespace + "3.png", "null", false),
                new StoredVersion(BUCKET, namespace + "4.png", "null", false));
        when(storage.versionsUnder(namespace)).thenReturn(orphans);
        doAnswer(invocation -> {
            clock.advance(Duration.ofSeconds(25));
            return null;
        }).when(storage).removeVersion(any());

        assertThat(slow.process(jobId)).isEqualTo(MediaObjectErasureWorker.Outcome.RETRY_SCHEDULED);

        verify(storage, org.mockito.Mockito.times(3)).removeVersion(any());
        verify(jobs).release(eq(jobId), eq(token), any(), any());
        verify(jobs, never()).holdOwner(any());
        verify(ackOutbox, never()).append(any());
    }

    @Test
    void partialObjectFailureQueuesNoAckAndKeepsOnlyTheUnconfirmedRow() {
        StoredMedia ok = media("ok.png");
        StoredMedia broken = media("broken.png");
        StoredVersion okVersion = version(ok, "null", false);
        StoredVersion brokenVersion = version(broken, "null", false);
        when(jobs.find(jobId)).thenReturn(Optional.of(new Job(jobId, requestId, owner, 2)));
        when(jobs.remainingMedia(owner)).thenReturn(List.of(ok, broken));
        when(storage.versionsOf(BUCKET, ok.objectKey())).thenReturn(List.of(okVersion), List.of());
        when(storage.versionsOf(BUCKET, broken.objectKey())).thenReturn(List.of(brokenVersion));
        doThrow(new IllegalStateException("object locked")).when(storage).removeVersion(brokenVersion);

        assertThat(worker.process(jobId)).isEqualTo(MediaObjectErasureWorker.Outcome.RETRY_SCHEDULED);

        verify(jobs).deleteMedia(ok.mediaId());
        verify(jobs, never()).deleteMedia(broken.mediaId());
        verify(jobs).scheduleRetry(eq(jobId), eq(token), contains("media " + broken.mediaId()),
                eq(NOW.plus(Duration.ofSeconds(20))), eq(NOW));
        verify(ackOutbox, never()).append(any());
    }

    @Test
    void mediaCommittedDuringTheAttemptKeepsTheJobPending() {
        when(jobs.remainingMedia(owner)).thenReturn(List.of());
        when(storage.versionsUnder(namespace)).thenReturn(List.of());
        when(jobs.countMedia(owner)).thenReturn(1L);

        assertThat(worker.process(jobId)).isEqualTo(MediaObjectErasureWorker.Outcome.RETRY_SCHEDULED);

        verify(jobs).release(jobId, token, NOW, NOW);
        verify(ackOutbox, never()).append(any());
        verify(jobs, never()).delete(any());
    }

    @Test
    void unexpectedFailureIsRecordedOnTheJobWithBackoffAndQueuesNoAck() {
        when(jobs.remainingMedia(owner)).thenReturn(List.of());
        when(storage.versionsUnder(namespace)).thenReturn(List.of());
        when(jobs.countMedia(owner)).thenReturn(0L);
        doThrow(new IllegalStateException("outbox insert failed")).when(ackOutbox).append(any());

        assertThat(worker.process(jobId)).isEqualTo(MediaObjectErasureWorker.Outcome.RETRY_SCHEDULED);

        verify(transactions).rollback(any());
        verify(jobs, never()).delete(any());
        verify(jobs).scheduleRetry(eq(jobId), eq(token), eq("attempt failed: IllegalStateException: outbox insert failed"),
                eq(NOW.plus(Duration.ofSeconds(5))), eq(NOW));
    }

    @Test
    void failureToRecordAFailedAttemptNeitherThrowsNorQueuesAnAck() {
        when(jobs.remainingMedia(owner)).thenThrow(new IllegalStateException("database down"));
        doThrow(new IllegalStateException("still down")).when(jobs)
                .scheduleRetry(any(), any(), anyString(), any(), any());

        assertThat(worker.process(jobId)).isEqualTo(MediaObjectErasureWorker.Outcome.RETRY_SCHEDULED);

        verify(ackOutbox, never()).append(any());
        verify(jobs, never()).delete(any());
    }

    @Test
    void scheduledPollProcessesEveryClaimedJobWithItsToken() {
        when(jobs.claimDue(20, NOW, LEASE_END)).thenReturn(List.of(new Claim(jobId, token)));
        when(jobs.remainingMedia(owner)).thenReturn(List.of());
        when(storage.versionsUnder(namespace)).thenReturn(List.of());
        when(jobs.countMedia(owner)).thenReturn(0L);

        worker.processDue();

        verify(jobs, never()).claim(any(), any(), any());
        verify(jobs).lockClaim(jobId, token, NOW);
        verify(ackOutbox).append(any());
        verify(jobs).delete(jobId);
    }

    @Test
    void aLeaseThatCannotOutlastTheBudgetAndTwoStorageCallsIsRejectedAtStartup() {
        assertThatThrownBy(() -> new MediaObjectErasureWorker(jobs, storage, ackOutbox, transactions,
                Clock.fixed(NOW, ZoneOffset.UTC), new SimpleMeterRegistry(), true, 20, 60_000, 5_000, 900_000, 60_000))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("lease-ms");
        // budget 60 s + 2 x 15 s storage calls + 10 s margin = 100 s
        new MediaObjectErasureWorker(jobs, storage, ackOutbox, transactions, Clock.fixed(NOW, ZoneOffset.UTC),
                new SimpleMeterRegistry(), true, 20, 100_000, 5_000, 900_000, 60_000);
    }

    @Test
    void aWriteOfUnknownOutcomeWhoseObjectWasNeverSeenKeepsTheJobPending() {
        ObjectWrite write = new ObjectWrite(UUID.randomUUID(), BUCKET, namespace + "late.jpg", false);
        when(jobs.remainingMedia(owner)).thenReturn(List.of());
        when(storage.versionsUnder(namespace)).thenReturn(List.of());
        when(jobs.unsettledWrites(eq(owner), anyInt())).thenReturn(List.of(write));
        when(storage.versionsOf(BUCKET, write.objectKey())).thenReturn(List.of());

        assertThat(worker.process(jobId)).isEqualTo(MediaObjectErasureWorker.Outcome.RETRY_SCHEDULED);

        verify(jobs).scheduleRetry(eq(jobId), eq(token), contains("1 object write(s) of unknown outcome"), any(), any());
        verify(jobs, never()).markWriteApplied(any(), any());
        verify(jobs, never()).forgetWrite(any());
        verify(jobs, never()).holdOwner(any());
        verify(ackOutbox, never()).append(any());
    }

    @Test
    void aWriteOfUnknownOutcomeIsSettledOnceItsObjectHasBeenObservedAndRemoved() {
        ObjectWrite write = new ObjectWrite(UUID.randomUUID(), BUCKET, namespace + "late.jpg", false);
        StoredVersion late = new StoredVersion(BUCKET, write.objectKey(), "v9", false);
        when(jobs.remainingMedia(owner)).thenReturn(List.of());
        when(storage.versionsUnder(namespace)).thenReturn(List.of());
        when(jobs.unsettledWrites(eq(owner), anyInt())).thenReturn(List.of(write));
        when(storage.versionsOf(BUCKET, write.objectKey())).thenReturn(List.of(late), List.of(late), List.of());
        when(jobs.countMedia(owner)).thenReturn(0L);

        assertThat(worker.process(jobId)).isEqualTo(MediaObjectErasureWorker.Outcome.ACK_QUEUED);

        InOrder order = inOrder(jobs, storage, ackOutbox);
        order.verify(jobs).markWriteApplied(write.writeId(), NOW);
        order.verify(storage).removeVersion(late);
        order.verify(jobs).forgetWrite(write.writeId());
        order.verify(jobs).countUnsettledWrites(owner);
        order.verify(ackOutbox).append(any());
    }

    @Test
    void anAppliedWriteIsSettledOnceItsObjectIsConfirmedGone() {
        ObjectWrite write = new ObjectWrite(UUID.randomUUID(), BUCKET, namespace + "rolled-back.jpg", true);
        when(jobs.remainingMedia(owner)).thenReturn(List.of());
        when(storage.versionsUnder(namespace)).thenReturn(List.of());
        when(jobs.unsettledWrites(eq(owner), anyInt())).thenReturn(List.of(write));
        when(storage.versionsOf(BUCKET, write.objectKey())).thenReturn(List.of());
        when(jobs.countMedia(owner)).thenReturn(0L);

        assertThat(worker.process(jobId)).isEqualTo(MediaObjectErasureWorker.Outcome.ACK_QUEUED);

        verify(jobs).forgetWrite(write.writeId());
        verify(jobs, never()).markWriteApplied(any(), any());
    }

    @Test
    void theNamespaceSweepRecordsAWriteOfUnknownOutcomeAsAppliedBeforeRemovingItsObject() {
        ObjectWrite unknown = new ObjectWrite(UUID.randomUUID(), BUCKET, namespace + "late.jpg", false);
        ObjectWrite observed = new ObjectWrite(unknown.writeId(), BUCKET, unknown.objectKey(), true);
        StoredVersion late = new StoredVersion(BUCKET, unknown.objectKey(), "null", false);
        when(jobs.remainingMedia(owner)).thenReturn(List.of());
        when(jobs.unsettledWrites(eq(owner), anyInt())).thenReturn(List.of(unknown), List.of(observed));
        when(storage.versionsUnder(namespace)).thenReturn(List.of(late), List.of());
        when(storage.versionsOf(BUCKET, unknown.objectKey())).thenReturn(List.of());
        when(jobs.countMedia(owner)).thenReturn(0L);

        assertThat(worker.process(jobId)).isEqualTo(MediaObjectErasureWorker.Outcome.ACK_QUEUED);

        InOrder order = inOrder(jobs, storage);
        order.verify(jobs).markWriteApplied(unknown.writeId(), NOW);
        order.verify(storage).removeVersion(late);
        order.verify(jobs).forgetWrite(unknown.writeId());
    }

    @Test
    void aWriteRecordedDuringTheAttemptKeepsTheCompletionFromQueuingSuccess() {
        when(jobs.remainingMedia(owner)).thenReturn(List.of());
        when(storage.versionsUnder(namespace)).thenReturn(List.of());
        when(jobs.countMedia(owner)).thenReturn(0L);
        when(jobs.countUnsettledWrites(owner)).thenReturn(1L);

        assertThat(worker.process(jobId)).isEqualTo(MediaObjectErasureWorker.Outcome.RETRY_SCHEDULED);

        verify(jobs).release(jobId, token, NOW, NOW);
        verify(ackOutbox, never()).append(any());
    }

    @Test
    void nonPositiveBudgetLeaseOrStorageTimeoutIsRejectedAtStartup() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        assertThatThrownBy(() -> new MediaObjectErasureWorker(jobs, storage, ackOutbox, transactions, clock,
                new SimpleMeterRegistry(), true, 20, 120_000, 5_000, 900_000, 0, Duration.ofSeconds(15)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("attempt-budget-ms must be positive");
        assertThatThrownBy(() -> new MediaObjectErasureWorker(jobs, storage, ackOutbox, transactions, clock,
                new SimpleMeterRegistry(), true, 20, 120_000, 5_000, 900_000, -60_000, Duration.ofSeconds(15)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("attempt-budget-ms must be positive");
        assertThatThrownBy(() -> new MediaObjectErasureWorker(jobs, storage, ackOutbox, transactions, clock,
                new SimpleMeterRegistry(), true, 20, 0, 5_000, 900_000, 60_000, Duration.ofSeconds(15)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("lease-ms must be positive");
        assertThatThrownBy(() -> new MediaObjectErasureWorker(jobs, storage, ackOutbox, transactions, clock,
                new SimpleMeterRegistry(), true, 20, 120_000, 5_000, 900_000, 60_000, Duration.ZERO))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("call-timeout must be positive");
        assertThatThrownBy(() -> new MediaObjectErasureWorker(jobs, storage, ackOutbox, transactions, clock,
                new SimpleMeterRegistry(), true, 20, 120_000, 5_000, 900_000, 60_000, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("call-timeout must be positive");
    }

    @Test
    void backoffDoublesAndIsCapped() {
        assertThat(worker.backoff(1)).isEqualTo(Duration.ofSeconds(5));
        assertThat(worker.backoff(2)).isEqualTo(Duration.ofSeconds(10));
        assertThat(worker.backoff(30)).isEqualTo(Duration.ofMinutes(15));
    }

    private MediaObjectErasureWorker worker(Clock clock) {
        return new MediaObjectErasureWorker(jobs, storage, ackOutbox, transactions, clock, new SimpleMeterRegistry(),
                true, 20, 120_000, 5_000, 900_000, 60_000);
    }

    private StoredMedia media(String name) {
        return new StoredMedia(UUID.randomUUID(), BUCKET, namespace + name);
    }

    private static StoredVersion version(StoredMedia media, String versionId, boolean deleteMarker) {
        return new StoredVersion(BUCKET, media.objectKey(), versionId, deleteMarker);
    }
}
