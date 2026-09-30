package com.parkio.media.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.parkio.media.application.port.ErasureAckOutbox;
import com.parkio.media.application.port.MediaStoragePort;
import com.parkio.media.domain.event.UserErasureAcknowledgedEvent;
import com.parkio.media.infrastructure.persistence.MediaErasureJobStore;
import com.parkio.media.infrastructure.persistence.MediaErasureJobStore.Job;
import com.parkio.media.infrastructure.persistence.MediaErasureJobStore.PendingObject;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

class MediaObjectErasureWorkerTest {

    private static final Instant NOW = Instant.parse("2026-08-14T09:00:00Z");

    private final MediaErasureJobStore jobs = mock(MediaErasureJobStore.class);
    private final MediaStoragePort storage = mock(MediaStoragePort.class);
    private final ErasureAckOutbox ackOutbox = mock(ErasureAckOutbox.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final UUID jobId = UUID.randomUUID();
    private final UUID requestId = UUID.randomUUID();
    private final UUID owner = UUID.randomUUID();
    private MediaObjectErasureWorker worker;

    @BeforeEach
    void setUp() {
        when(transactions.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        worker = new MediaObjectErasureWorker(jobs, storage, ackOutbox, transactions,
                Clock.fixed(NOW, ZoneOffset.UTC), new SimpleMeterRegistry(), true, 20, 120_000, 5_000, 900_000);
        when(jobs.find(jobId)).thenReturn(Optional.of(new Job(jobId, requestId, owner, MediaErasureJobStore.PENDING, 0)));
    }

    @Test
    void successAckIsQueuedOnlyAfterEveryObjectIsConfirmed() {
        PendingObject a = new PendingObject(UUID.randomUUID(), "a.png");
        PendingObject b = new PendingObject(UUID.randomUUID(), "b.png");
        when(jobs.pendingObjects(owner)).thenReturn(List.of(a, b));
        when(jobs.lockPending(jobId)).thenReturn(true);
        when(jobs.countPendingObjects(owner)).thenReturn(0L);

        assertThat(worker.process(jobId)).isEqualTo(MediaObjectErasureWorker.Outcome.ACK_QUEUED);

        verify(storage).delete("a.png");
        verify(storage).delete("b.png");
        verify(jobs).markObjectDeleted(a.mediaId(), NOW);
        verify(jobs).markObjectDeleted(b.mediaId(), NOW);
        ArgumentCaptor<UserErasureAcknowledgedEvent> ack = ArgumentCaptor.forClass(UserErasureAcknowledgedEvent.class);
        verify(ackOutbox).append(ack.capture());
        assertThat(ack.getValue().eventId()).isEqualTo(jobId);
        assertThat(ack.getValue().erasureRequestId()).isEqualTo(requestId);
        assertThat(ack.getValue().authUserId()).isEqualTo(owner);
        assertThat(ack.getValue().serviceName()).isEqualTo("media");
        assertThat(ack.getValue().status()).isEqualTo("SUCCESS");
        verify(jobs).markAckQueued(jobId, NOW);
    }

    @Test
    void partialObjectFailureQueuesNoAckAndSchedulesARetry() {
        PendingObject ok = new PendingObject(UUID.randomUUID(), "ok.png");
        PendingObject broken = new PendingObject(UUID.randomUUID(), "broken.png");
        when(jobs.pendingObjects(owner)).thenReturn(List.of(ok, broken));
        doThrow(new IllegalStateException("storage down")).when(storage).delete("broken.png");

        assertThat(worker.process(jobId)).isEqualTo(MediaObjectErasureWorker.Outcome.RETRY_SCHEDULED);

        verify(jobs).markObjectDeleted(ok.mediaId(), NOW);
        verify(jobs, never()).markObjectDeleted(eq(broken.mediaId()), any());
        verify(jobs).scheduleRetry(eq(jobId), anyString(), eq(NOW.plus(Duration.ofSeconds(5))), eq(NOW));
        verify(ackOutbox, never()).append(any());
        verify(jobs, never()).markAckQueued(any(), any());
    }

    @Test
    void rowsSoftDeletedDuringTheAttemptKeepTheJobPending() {
        when(jobs.pendingObjects(owner)).thenReturn(List.of());
        when(jobs.lockPending(jobId)).thenReturn(true);
        when(jobs.countPendingObjects(owner)).thenReturn(1L);

        assertThat(worker.process(jobId)).isEqualTo(MediaObjectErasureWorker.Outcome.RETRY_SCHEDULED);

        verify(jobs).makeDue(jobId, NOW);
        verify(ackOutbox, never()).append(any());
    }

    @Test
    void jobThatIsNoLongerPendingIsLeftAlone() {
        when(jobs.find(jobId)).thenReturn(Optional.of(new Job(jobId, requestId, owner, MediaErasureJobStore.ACK_QUEUED, 0)));

        assertThat(worker.process(jobId)).isEqualTo(MediaObjectErasureWorker.Outcome.NOT_PENDING);

        verify(storage, never()).delete(anyString());
        verify(ackOutbox, never()).append(any());
    }

    @Test
    void backoffDoublesAndIsCapped() {
        assertThat(worker.backoff(1)).isEqualTo(Duration.ofSeconds(5));
        assertThat(worker.backoff(2)).isEqualTo(Duration.ofSeconds(10));
        assertThat(worker.backoff(30)).isEqualTo(Duration.ofMinutes(15));
    }
}
