package com.parkio.media.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.parkio.media.application.event.UserErasureRequestedEvent;
import com.parkio.media.domain.MediaFile;
import com.parkio.media.infrastructure.persistence.MediaErasureJobStore;
import com.parkio.media.infrastructure.persistence.entity.MediaFileEntity;
import com.parkio.media.infrastructure.persistence.jpa.ErasedUserTombstoneJpaRepository;
import com.parkio.media.infrastructure.persistence.jpa.MediaFileJpaRepository;
import com.parkio.media.infrastructure.persistence.mapper.MediaPersistenceMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

class AccountErasureHandlerTest {

    private static final Instant NOW = Instant.parse("2026-08-14T09:00:00Z");
    private static final Instant LEASE_END = NOW.plus(Duration.ofMinutes(2));

    private final ErasedUserTombstoneJpaRepository tombstones = mock(ErasedUserTombstoneJpaRepository.class);
    private final MediaFileJpaRepository mediaFiles = mock(MediaFileJpaRepository.class);
    private final MediaErasureJobStore jobs = mock(MediaErasureJobStore.class);
    private final MediaObjectErasureWorker objectEraser = mock(MediaObjectErasureWorker.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private AccountErasureHandler handler;

    @BeforeEach
    void setUp() {
        when(transactions.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        handler = new AccountErasureHandler(tombstones, mediaFiles, jobs, objectEraser, transactions,
                Clock.fixed(NOW, ZoneOffset.UTC), 120_000);
    }

    @Test
    void metadataEraseAndJobCommitBeforeTheObjectPhase() {
        UUID owner = UUID.randomUUID();
        MediaFile active = MediaFile.create(owner, "bucket", "k/" + UUID.randomUUID(), "image/png", 1,
                UUID.randomUUID().toString(), null, null, NOW);
        when(mediaFiles.findByOwnerUserId(owner)).thenReturn(List.of(MediaPersistenceMapper.toEntity(active)));
        UserErasureRequestedEvent event = new UserErasureRequestedEvent(UUID.randomUUID(), UUID.randomUUID(), owner, NOW);

        handler.handle(event);

        InOrder order = inOrder(tombstones, mediaFiles, jobs, transactions, objectEraser);
        // The owner fence comes first: in-flight uploads finish before the tombstone exists.
        order.verify(jobs).holdOwner(owner);
        order.verify(tombstones).save(any());
        ArgumentCaptor<MediaFileEntity> saved = ArgumentCaptor.forClass(MediaFileEntity.class);
        order.verify(mediaFiles).save(saved.capture());
        order.verify(jobs).deleteIdempotencyRecords(owner);
        // The immediate attempt holds the job's lease, so the poll does not run it concurrently.
        order.verify(jobs).open(AccountErasureHandler.ackEventId(event), event.erasureRequestId(), owner, NOW,
                LEASE_END);
        order.verify(transactions).commit(any());
        order.verify(objectEraser).process(AccountErasureHandler.ackEventId(event));
        assertThat(MediaPersistenceMapper.toDomain(saved.getValue()).isDeleted()).isTrue();
    }

    @Test
    void objectPhaseFailureDoesNotFailTheCommittedErase() {
        UUID owner = UUID.randomUUID();
        when(mediaFiles.findByOwnerUserId(owner)).thenReturn(List.of());
        doThrow(new IllegalStateException("db blip")).when(objectEraser).process(any());

        handler.handle(new UserErasureRequestedEvent(UUID.randomUUID(), UUID.randomUUID(), owner, NOW));

        verify(transactions).commit(any());
    }

    @Test
    void metadataFailureRollsBackAndSkipsTheObjectPhase() {
        UUID owner = UUID.randomUUID();
        when(tombstones.save(any())).thenThrow(new IllegalStateException("db down"));

        assertThatThrownBy(() -> handler.handle(
                new UserErasureRequestedEvent(UUID.randomUUID(), UUID.randomUUID(), owner, NOW)))
                .isInstanceOf(IllegalStateException.class);

        verify(transactions).rollback(any());
        verify(jobs).holdOwner(owner);
        verify(jobs, never()).open(any(), any(), any(), any(), any());
        verify(objectEraser, never()).process(any());
    }

    @Test
    void redeliveryReopensTheSameJobAndReplayOpensANewOne() {
        UUID owner = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        when(mediaFiles.findByOwnerUserId(owner)).thenReturn(List.of());
        UserErasureRequestedEvent event = new UserErasureRequestedEvent(UUID.randomUUID(), requestId, owner, NOW);
        UserErasureRequestedEvent replay = new UserErasureRequestedEvent(UUID.randomUUID(), requestId, owner, NOW);

        handler.handle(event);
        handler.handle(event);
        handler.handle(replay);

        UUID first = AccountErasureHandler.ackEventId(event);
        UUID second = AccountErasureHandler.ackEventId(replay);
        assertThat(first).isNotEqualTo(second);
        verify(jobs, times(2)).open(eq(first), eq(requestId), eq(owner), any(), any());
        verify(jobs).open(eq(second), eq(requestId), eq(owner), any(), any());
    }
}
