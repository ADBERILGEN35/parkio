package com.parkio.media.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
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
import com.parkio.media.infrastructure.persistence.MediaErasureJobStore.Job;
import com.parkio.media.infrastructure.persistence.MediaErasureJobStore.StoredMedia;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
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
    private static final String BUCKET = "parkio-media";

    private final MediaErasureJobStore jobs = mock(MediaErasureJobStore.class);
    private final MediaStoragePort storage = mock(MediaStoragePort.class);
    private final ErasureAckOutbox ackOutbox = mock(ErasureAckOutbox.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final UUID jobId = UUID.randomUUID();
    private final UUID requestId = UUID.randomUUID();
    private final UUID owner = UUID.randomUUID();
    private final String namespace = "media/" + owner + "/";
    private MediaObjectErasureWorker worker;

    @BeforeEach
    void setUp() {
        when(transactions.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        worker = worker(Clock.fixed(NOW, ZoneOffset.UTC));
        when(jobs.find(jobId)).thenReturn(Optional.of(new Job(jobId, requestId, owner, 0)));
    }

    @Test
    void successAckIsQueuedOnlyAfterEveryVersionIsConfirmedGoneAndTheMetadataDeleted() {
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
        when(jobs.lock(jobId)).thenReturn(true);
        when(jobs.countMedia(owner)).thenReturn(0L);

        assertThat(worker.process(jobId)).isEqualTo(MediaObjectErasureWorker.Outcome.ACK_QUEUED);

        InOrder order = inOrder(storage, jobs, ackOutbox);
        order.verify(storage).removeVersion(a1);
        order.verify(storage).removeVersion(a2);
        order.verify(jobs).deleteMedia(a.mediaId());
        order.verify(storage).removeVersion(marker);
        order.verify(jobs).deleteMedia(b.mediaId());
        order.verify(storage).removeVersion(orphan);
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
    void anObjectStillListedAfterADeleteThatReturnedNormallyIsNotErased() {
        StoredMedia a = media("a.png");
        StoredVersion a1 = version(a, "v1", false);
        when(jobs.remainingMedia(owner)).thenReturn(List.of(a));
        when(storage.versionsOf(BUCKET, a.objectKey())).thenReturn(List.of(a1), List.of(a1));

        assertThat(worker.process(jobId)).isEqualTo(MediaObjectErasureWorker.Outcome.RETRY_SCHEDULED);

        verify(storage).removeVersion(a1);
        verify(jobs, never()).deleteMedia(any());
        verify(storage, never()).versionsUnder(anyString());
        verify(jobs).scheduleRetry(eq(jobId), contains("still present after delete"),
                eq(NOW.plus(Duration.ofSeconds(5))), eq(NOW));
        verify(ackOutbox, never()).append(any());
        verify(jobs, never()).delete(any());
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
        verify(jobs).scheduleRetry(eq(jobId), contains("media " + broken.mediaId()),
                eq(NOW.plus(Duration.ofSeconds(20))), eq(NOW));
        verify(ackOutbox, never()).append(any());
    }

    @Test
    void mediaCommittedDuringTheAttemptKeepsTheJobPending() {
        when(jobs.remainingMedia(owner)).thenReturn(List.of());
        when(storage.versionsUnder(namespace)).thenReturn(List.of());
        when(jobs.lock(jobId)).thenReturn(true);
        when(jobs.countMedia(owner)).thenReturn(1L);

        assertThat(worker.process(jobId)).isEqualTo(MediaObjectErasureWorker.Outcome.RETRY_SCHEDULED);

        verify(jobs).makeDue(jobId, NOW);
        verify(ackOutbox, never()).append(any());
        verify(jobs, never()).delete(any());
    }

    @Test
    void unexpectedFailureIsRecordedOnTheJobWithBackoffAndQueuesNoAck() {
        when(jobs.remainingMedia(owner)).thenReturn(List.of());
        when(storage.versionsUnder(namespace)).thenReturn(List.of());
        when(jobs.lock(jobId)).thenReturn(true);
        when(jobs.countMedia(owner)).thenReturn(0L);
        doThrow(new IllegalStateException("outbox insert failed")).when(ackOutbox).append(any());

        assertThat(worker.process(jobId)).isEqualTo(MediaObjectErasureWorker.Outcome.RETRY_SCHEDULED);

        verify(transactions).rollback(any());
        verify(jobs, never()).delete(any());
        verify(jobs).scheduleRetry(eq(jobId), eq("attempt failed: IllegalStateException: outbox insert failed"),
                eq(NOW.plus(Duration.ofSeconds(5))), eq(NOW));
    }

    @Test
    void failureToRecordAFailedAttemptNeitherThrowsNorQueuesAnAck() {
        when(jobs.remainingMedia(owner)).thenThrow(new IllegalStateException("database down"));
        doThrow(new IllegalStateException("still down")).when(jobs)
                .scheduleRetry(any(), anyString(), any(), any());

        assertThat(worker.process(jobId)).isEqualTo(MediaObjectErasureWorker.Outcome.RETRY_SCHEDULED);

        verify(ackOutbox, never()).append(any());
        verify(jobs, never()).delete(any());
    }

    @Test
    void unknownJobIsLeftAlone() {
        when(jobs.find(jobId)).thenReturn(Optional.empty());

        assertThat(worker.process(jobId)).isEqualTo(MediaObjectErasureWorker.Outcome.NOT_PENDING);

        verify(storage, never()).versionsOf(anyString(), anyString());
        verify(storage, never()).removeVersion(any());
        verify(ackOutbox, never()).append(any());
    }

    @Test
    void anAttemptOverItsBudgetStopsTakingObjectsAndLeavesTheJobDue() {
        // Every clock read advances 61s, past the 60s budget before the first object.
        Clock ticking = new Clock() {
            private Instant next = NOW;

            @Override
            public Instant instant() {
                Instant current = next;
                next = next.plusSeconds(61);
                return current;
            }

            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }
        };
        MediaObjectErasureWorker slow = worker(ticking);
        when(jobs.remainingMedia(owner)).thenReturn(List.of(media("a.png")));

        assertThat(slow.process(jobId)).isEqualTo(MediaObjectErasureWorker.Outcome.RETRY_SCHEDULED);

        verify(jobs).makeDue(eq(jobId), any());
        verify(storage, never()).removeVersion(any());
        verify(storage, never()).versionsUnder(anyString());
        verify(jobs, never()).scheduleRetry(any(), anyString(), any(), any());
        verify(ackOutbox, never()).append(any());
    }

    @Test
    void scheduledPollProcessesEveryClaimedJob() {
        when(jobs.claimDue(20, NOW, NOW.plus(Duration.ofMinutes(2)))).thenReturn(List.of(jobId));
        when(jobs.remainingMedia(owner)).thenReturn(List.of());
        when(storage.versionsUnder(namespace)).thenReturn(List.of());
        when(jobs.lock(jobId)).thenReturn(true);
        when(jobs.countMedia(owner)).thenReturn(0L);

        worker.processDue();

        verify(ackOutbox).append(any());
        verify(jobs).delete(jobId);
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
