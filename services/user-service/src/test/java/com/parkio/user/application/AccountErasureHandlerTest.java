package com.parkio.user.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.parkio.user.application.event.UserErasureRequestedEvent;
import com.parkio.user.application.event.UserErasureRestoreReplayRequestedEvent;
import com.parkio.user.application.port.ErasureAckOutbox;
import com.parkio.user.domain.event.UserErasureAcknowledgedEvent;
import com.parkio.user.domain.event.UserErasureRestoreAcknowledgedEvent;
import com.parkio.user.infrastructure.persistence.entity.UserProfileEntity;
import com.parkio.user.infrastructure.persistence.jpa.ErasedUserTombstoneJpaRepository;
import com.parkio.user.infrastructure.persistence.jpa.FavouriteDestinationJpaRepository;
import com.parkio.user.infrastructure.persistence.jpa.FavouriteParkingJpaRepository;
import com.parkio.user.infrastructure.persistence.jpa.PendingUserStatusEventJpaRepository;
import com.parkio.user.infrastructure.persistence.jpa.RecentDestinationJpaRepository;
import com.parkio.user.infrastructure.persistence.jpa.RecentParkingJpaRepository;
import com.parkio.user.infrastructure.persistence.jpa.SavedPlaceJpaRepository;
import com.parkio.user.infrastructure.persistence.jpa.UserPreferenceJpaRepository;
import com.parkio.user.infrastructure.persistence.jpa.UserProfileJpaRepository;
import com.parkio.user.infrastructure.persistence.jpa.UserTrustProfileJpaRepository;
import com.parkio.user.infrastructure.persistence.jpa.UserTrustScoreHistoryJpaRepository;
import com.parkio.user.infrastructure.persistence.jpa.UserVehicleProfileJpaRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AccountErasureHandlerTest {

    private static final Instant NOW = Instant.parse("2026-08-14T09:00:00Z");

    @Mock private UserProfileJpaRepository profiles;
    @Mock private SavedPlaceJpaRepository savedPlaces;
    @Mock private FavouriteParkingJpaRepository favouriteParking;
    @Mock private FavouriteDestinationJpaRepository favouriteDestinations;
    @Mock private RecentDestinationJpaRepository recentDestinations;
    @Mock private RecentParkingJpaRepository recentParking;
    @Mock private UserPreferenceJpaRepository preferences;
    @Mock private UserVehicleProfileJpaRepository vehicles;
    @Mock private UserTrustProfileJpaRepository trustProfiles;
    @Mock private UserTrustScoreHistoryJpaRepository trustHistory;
    @Mock private PendingUserStatusEventJpaRepository pendingStatus;
    @Mock private ErasedUserTombstoneJpaRepository tombstones;
    @Mock private ErasureAckOutbox ackOutbox;

    private AccountErasureHandler handler;

    @BeforeEach
    void setUp() {
        handler = new AccountErasureHandler(
                profiles, savedPlaces, favouriteParking, favouriteDestinations,
                recentDestinations, recentParking, preferences, vehicles, trustProfiles,
                trustHistory, pendingStatus, tombstones, ackOutbox,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void deletesUserOwnedRowsAndAcks() {
        UUID authUserId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();
        UserProfileEntity profile = new UserProfileEntity(
                profileId, authUserId, "a@b.c", "n", null, null,
                com.parkio.user.domain.UserStatus.ACTIVE, null, NOW, 0L);
        when(profiles.findByAuthUserId(authUserId)).thenReturn(Optional.of(profile));
        UUID requestId = UUID.randomUUID();

        handler.handle(new UserErasureRequestedEvent(UUID.randomUUID(), requestId, authUserId, NOW));

        verify(savedPlaces).deleteByUserProfileId(profileId);
        verify(favouriteParking).deleteByUserProfileId(profileId);
        verify(recentDestinations).deleteByUserProfileId(profileId);
        InOrder order = inOrder(profiles, ackOutbox);
        order.verify(profiles).deleteById(profileId);
        ArgumentCaptor<UserErasureAcknowledgedEvent> ack = ArgumentCaptor.forClass(UserErasureAcknowledgedEvent.class);
        order.verify(ackOutbox).append(ack.capture());
        assertThat(ack.getValue().erasureRequestId()).isEqualTo(requestId);
        assertThat(ack.getValue().authUserId()).isEqualTo(authUserId);
        assertThat(ack.getValue().serviceName()).isEqualTo("user");
        assertThat(ack.getValue().status()).isEqualTo("SUCCESS");
    }

    @Test
    void missingProfileStillTombsAndAcks() {
        UUID authUserId = UUID.randomUUID();
        when(profiles.findByAuthUserId(authUserId)).thenReturn(Optional.empty());
        UUID requestId = UUID.randomUUID();

        handler.handle(new UserErasureRequestedEvent(UUID.randomUUID(), requestId, authUserId, NOW));

        verify(savedPlaces, never()).deleteByUserProfileId(any());
        verify(ackOutbox).append(any());
        verify(tombstones).save(any());
    }

    @Test
    void redeliveredRequestDerivesSameAckEventIdAndReplayDerivesNewOne() {
        UUID authUserId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        when(profiles.findByAuthUserId(authUserId)).thenReturn(Optional.empty());
        UserErasureRequestedEvent event = new UserErasureRequestedEvent(UUID.randomUUID(), requestId, authUserId, NOW);
        UserErasureRequestedEvent replay = new UserErasureRequestedEvent(UUID.randomUUID(), requestId, authUserId, NOW);

        handler.handle(event);
        handler.handle(event);
        handler.handle(replay);

        ArgumentCaptor<UserErasureAcknowledgedEvent> acks = ArgumentCaptor.forClass(UserErasureAcknowledgedEvent.class);
        verify(ackOutbox, times(3)).append(acks.capture());
        List<UUID> ids = acks.getAllValues().stream().map(UserErasureAcknowledgedEvent::eventId).toList();
        assertThat(ids.get(0)).isEqualTo(ids.get(1));
        assertThat(ids.get(2)).isNotEqualTo(ids.get(0));
        verify(tombstones, times(3)).save(any());
    }

    @Test
    void failedEraseQueuesNoAck() {
        UUID authUserId = UUID.randomUUID();
        when(profiles.findByAuthUserId(authUserId)).thenThrow(new IllegalStateException("db down"));

        assertThatThrownBy(() -> handler.handle(
                new UserErasureRequestedEvent(UUID.randomUUID(), UUID.randomUUID(), authUserId, NOW)))
                .isInstanceOf(IllegalStateException.class);

        verify(ackOutbox, never()).append(any());
    }

    @Test
    void restoreReplayErasesThenQueuesTheAttemptBoundAck() {
        UserErasureRestoreReplayRequestedEvent replay = restoreReplay();

        handler.replayForRestore(replay);

        InOrder order = inOrder(tombstones, ackOutbox);
        order.verify(tombstones).save(any());
        ArgumentCaptor<UserErasureRestoreAcknowledgedEvent> ack =
                ArgumentCaptor.forClass(UserErasureRestoreAcknowledgedEvent.class);
        order.verify(ackOutbox).appendRestoreAck(ack.capture());
        assertThat(ack.getValue().eventId()).isEqualTo(AccountErasureHandler.restoreAckEventId(replay));
        assertThat(ack.getValue().recoveryAttemptId()).isEqualTo(replay.recoveryAttemptId());
        assertThat(ack.getValue().restoredDatasetId()).isEqualTo(replay.restoredDatasetId());
        assertThat(ack.getValue().erasureSetDigest()).isEqualTo(replay.erasureSetDigest());
        assertThat(ack.getValue().authUserId()).isEqualTo(replay.authUserId());
        assertThat(ack.getValue().serviceName()).isEqualTo("user");
        assertThat(ack.getValue().status()).isEqualTo("SUCCESS");
        assertThat(ack.getValue().occurredAt()).isEqualTo(NOW);
    }

    @Test
    void aRestoreRedeliveryReDerivesTheAckIdAndAnotherAttemptGetsANewOne() {
        UserErasureRestoreReplayRequestedEvent replay = restoreReplay();
        UserErasureRestoreReplayRequestedEvent redelivered = new UserErasureRestoreReplayRequestedEvent(
                replay.eventId(), replay.recoveryAttemptId(), replay.restoredDatasetId(), replay.erasureSetDigest(),
                replay.authUserId(), replay.erasedAt(), replay.occurredAt());
        UserErasureRestoreReplayRequestedEvent anotherAttempt = new UserErasureRestoreReplayRequestedEvent(
                UUID.randomUUID(), UUID.randomUUID(), replay.restoredDatasetId(), replay.erasureSetDigest(),
                replay.authUserId(), replay.erasedAt(), replay.occurredAt());

        assertThat(AccountErasureHandler.restoreAckEventId(redelivered))
                .isEqualTo(AccountErasureHandler.restoreAckEventId(replay));
        assertThat(AccountErasureHandler.restoreAckEventId(anotherAttempt))
                .isNotEqualTo(AccountErasureHandler.restoreAckEventId(replay));
    }

    private static UserErasureRestoreReplayRequestedEvent restoreReplay() {
        return new UserErasureRestoreReplayRequestedEvent(UUID.randomUUID(), UUID.randomUUID(),
                "backup-stamp-2026-10-03", "d".repeat(64), UUID.randomUUID(),
                Instant.parse("2026-09-29T08:16:00Z"), NOW);
    }
}
