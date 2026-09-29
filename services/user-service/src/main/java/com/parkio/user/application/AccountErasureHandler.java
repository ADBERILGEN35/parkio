package com.parkio.user.application;

import com.parkio.user.application.event.UserErasureRequestedEvent;
import com.parkio.user.infrastructure.client.AuthErasureAckClient;
import com.parkio.user.infrastructure.persistence.entity.ErasedUserTombstoneEntity;
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
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountErasureHandler {

    private static final Logger log = LoggerFactory.getLogger(AccountErasureHandler.class);

    private final UserProfileJpaRepository profiles;
    private final SavedPlaceJpaRepository savedPlaces;
    private final FavouriteParkingJpaRepository favouriteParking;
    private final FavouriteDestinationJpaRepository favouriteDestinations;
    private final RecentDestinationJpaRepository recentDestinations;
    private final RecentParkingJpaRepository recentParking;
    private final UserPreferenceJpaRepository preferences;
    private final UserVehicleProfileJpaRepository vehicles;
    private final UserTrustProfileJpaRepository trustProfiles;
    private final UserTrustScoreHistoryJpaRepository trustHistory;
    private final PendingUserStatusEventJpaRepository pendingStatus;
    private final ErasedUserTombstoneJpaRepository tombstones;
    private final AuthErasureAckClient ackClient;
    private final Clock clock;

    public AccountErasureHandler(
            UserProfileJpaRepository profiles,
            SavedPlaceJpaRepository savedPlaces,
            FavouriteParkingJpaRepository favouriteParking,
            FavouriteDestinationJpaRepository favouriteDestinations,
            RecentDestinationJpaRepository recentDestinations,
            RecentParkingJpaRepository recentParking,
            UserPreferenceJpaRepository preferences,
            UserVehicleProfileJpaRepository vehicles,
            UserTrustProfileJpaRepository trustProfiles,
            UserTrustScoreHistoryJpaRepository trustHistory,
            PendingUserStatusEventJpaRepository pendingStatus,
            ErasedUserTombstoneJpaRepository tombstones,
            AuthErasureAckClient ackClient,
            Clock clock) {
        this.profiles = profiles;
        this.savedPlaces = savedPlaces;
        this.favouriteParking = favouriteParking;
        this.favouriteDestinations = favouriteDestinations;
        this.recentDestinations = recentDestinations;
        this.recentParking = recentParking;
        this.preferences = preferences;
        this.vehicles = vehicles;
        this.trustProfiles = trustProfiles;
        this.trustHistory = trustHistory;
        this.pendingStatus = pendingStatus;
        this.tombstones = tombstones;
        this.ackClient = ackClient;
        this.clock = clock;
    }

    @Transactional
    public void handle(UserErasureRequestedEvent event) {
        UUID authUserId = event.authUserId();
        eraseLocal(authUserId);
        UUID ackEventId = UUID.nameUUIDFromBytes(
                (event.erasureRequestId() + ":user").getBytes(StandardCharsets.UTF_8));
        ackClient.acknowledge(ackEventId, event.erasureRequestId(), authUserId, "SUCCESS");
        log.info("erasure completed requestId={} service=user status=SUCCESS", event.erasureRequestId());
    }

    private void eraseLocal(UUID authUserId) {
        tombstones.save(new ErasedUserTombstoneEntity(authUserId, clock.instant()));
        pendingStatus.deleteByAuthUserId(authUserId);
        Optional<UserProfileEntity> profile = profiles.findByAuthUserId(authUserId);
        if (profile.isEmpty()) {
            return;
        }
        UUID profileId = profile.get().getId();
        savedPlaces.deleteByUserProfileId(profileId);
        favouriteParking.deleteByUserProfileId(profileId);
        favouriteDestinations.deleteByUserProfileId(profileId);
        recentDestinations.deleteByUserProfileId(profileId);
        recentParking.deleteByUserProfileId(profileId);
        trustHistory.deleteByUserProfileId(profileId);
        trustProfiles.deleteByUserProfileId(profileId);
        vehicles.deleteByUserProfileId(profileId);
        preferences.deleteByUserProfileId(profileId);
        profiles.deleteById(profileId);
    }
}
