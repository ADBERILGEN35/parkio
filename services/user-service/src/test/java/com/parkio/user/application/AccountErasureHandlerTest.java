package com.parkio.user.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.parkio.user.application.event.UserErasureRequestedEvent;
import com.parkio.user.infrastructure.client.AuthErasureAckClient;
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
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
    @Mock private AuthErasureAckClient ackClient;

    private AccountErasureHandler handler;

    @BeforeEach
    void setUp() {
        handler = new AccountErasureHandler(
                profiles, savedPlaces, favouriteParking, favouriteDestinations,
                recentDestinations, recentParking, preferences, vehicles, trustProfiles,
                trustHistory, pendingStatus, tombstones, ackClient,
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
        verify(profiles).deleteById(profileId);
        verify(ackClient).acknowledge(any(), eq(requestId), eq(authUserId), eq("SUCCESS"));
    }

    @Test
    void missingProfileStillTombsAndAcks() {
        UUID authUserId = UUID.randomUUID();
        when(profiles.findByAuthUserId(authUserId)).thenReturn(Optional.empty());
        UUID requestId = UUID.randomUUID();

        handler.handle(new UserErasureRequestedEvent(UUID.randomUUID(), requestId, authUserId, NOW));

        verify(savedPlaces, never()).deleteByUserProfileId(any());
        verify(ackClient).acknowledge(any(), eq(requestId), eq(authUserId), eq("SUCCESS"));
        verify(tombstones).save(any());
    }
}
