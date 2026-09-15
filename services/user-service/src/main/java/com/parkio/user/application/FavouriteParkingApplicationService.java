package com.parkio.user.application;

import com.parkio.user.application.port.FavouriteParkingRepository;
import com.parkio.user.application.port.UserProfileRepository;
import com.parkio.user.domain.UserProfile;
import com.parkio.user.domain.exception.UserErrorCode;
import com.parkio.user.domain.exception.UserException;
import com.parkio.user.domain.place.FavouriteParking;
import com.parkio.user.domain.place.FavouriteParkingTargetKind;
import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Municipal parking favourites (WP-SPA-04). Reference-only persistence —
 * facility truth remains in parking-service. Target UUID is validated for
 * format only (Strategy C); no synchronous parking-service call.
 */
@Service
@Transactional
public class FavouriteParkingApplicationService {

    private final FavouriteParkingRepository favourites;
    private final UserProfileRepository profiles;
    private final Clock clock;

    public FavouriteParkingApplicationService(
            FavouriteParkingRepository favourites,
            UserProfileRepository profiles,
            Clock clock) {
        this.favourites = favourites;
        this.profiles = profiles;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<FavouriteParking> list(UUID authUserId) {
        return favourites.findAllByUserProfileId(requireProfile(authUserId).id());
    }

    public FavouriteParking addMunicipalFacility(UUID authUserId, UUID facilityId) {
        if (facilityId == null) {
            throw new UserException(UserErrorCode.UNSUPPORTED_FAVOURITE_TARGET);
        }
        UUID profileId = requireProfile(authUserId).id();
        var existing = favourites.findByUserProfileIdAndTarget(
                profileId, FavouriteParkingTargetKind.MUNICIPAL_FACILITY, facilityId);
        if (existing.isPresent()) {
            return existing.get();
        }
        if (favourites.countByUserProfileId(profileId) >= FavouriteParking.MAX_PER_USER) {
            throw new UserException(UserErrorCode.FAVOURITE_LIMIT_EXCEEDED);
        }
        try {
            return favourites.save(FavouriteParking.create(
                    profileId,
                    FavouriteParkingTargetKind.MUNICIPAL_FACILITY,
                    facilityId,
                    clock.instant()));
        } catch (DataIntegrityViolationException ex) {
            return favourites.findByUserProfileIdAndTarget(
                            profileId, FavouriteParkingTargetKind.MUNICIPAL_FACILITY, facilityId)
                    .orElseThrow(() -> new UserException(UserErrorCode.FAVOURITE_CONFLICT));
        }
    }

    public void removeMunicipalFacility(UUID authUserId, UUID facilityId) {
        UUID profileId = requireProfile(authUserId).id();
        var existing = favourites.findByUserProfileIdAndTarget(
                profileId, FavouriteParkingTargetKind.MUNICIPAL_FACILITY, facilityId);
        if (existing.isEmpty()) {
            throw new UserException(UserErrorCode.FAVOURITE_PARKING_NOT_FOUND);
        }
        favourites.deleteByUserProfileIdAndTarget(
                profileId, FavouriteParkingTargetKind.MUNICIPAL_FACILITY, facilityId);
    }

    @Transactional(readOnly = true)
    public Set<UUID> statusFor(UUID authUserId, Collection<UUID> facilityIds) {
        UUID profileId = requireProfile(authUserId).id();
        return favourites.findByUserProfileIdAndTargets(
                        profileId, FavouriteParkingTargetKind.MUNICIPAL_FACILITY, facilityIds)
                .stream()
                .map(FavouriteParking::targetId)
                .collect(Collectors.toSet());
    }

    private UserProfile requireProfile(UUID authUserId) {
        return profiles.findByAuthUserId(authUserId)
                .orElseThrow(() -> new UserException(UserErrorCode.PROFILE_NOT_FOUND));
    }
}
