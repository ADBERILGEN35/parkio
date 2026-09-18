package com.parkio.user.application;

import com.parkio.user.application.port.FavouriteDestinationRepository;
import com.parkio.user.application.port.UserProfileRepository;
import com.parkio.user.domain.UserProfile;
import com.parkio.user.domain.exception.UserErrorCode;
import com.parkio.user.domain.exception.UserException;
import com.parkio.user.domain.place.FavouriteDestination;
import com.parkio.user.domain.place.PlaceDestinationSource;
import com.parkio.user.domain.place.PlaceIdentity;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Destination favourites (WP-SPA-04). Destination snapshots validated locally
 * with WP-SPA-02 semantics; duplicate key prefers PlaceIdentity then 5-dp coords.
 */
@Service
@Transactional
public class FavouriteDestinationApplicationService {

    private final FavouriteDestinationRepository favourites;
    private final UserProfileRepository profiles;
    private final Clock clock;

    public FavouriteDestinationApplicationService(
            FavouriteDestinationRepository favourites,
            UserProfileRepository profiles,
            Clock clock) {
        this.favourites = favourites;
        this.profiles = profiles;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<FavouriteDestination> list(UUID authUserId) {
        return favourites.findAllByUserProfileId(requireProfile(authUserId).id());
    }

    public FavouriteDestination add(
            UUID authUserId,
            String label,
            double latitude,
            double longitude,
            PlaceDestinationSource source,
            PlaceIdentity placeIdentity,
            String subtitle) {
        UUID profileId = requireProfile(authUserId).id();
        FavouriteDestination candidate = FavouriteDestination.create(
                profileId, label, latitude, longitude, source, placeIdentity, subtitle, clock.instant());
        var existing = favourites.findByUserProfileIdAndDuplicateKey(profileId, candidate.duplicateKey());
        if (existing.isPresent()) {
            return existing.get();
        }
        if (favourites.countByUserProfileId(profileId) >= FavouriteDestination.MAX_PER_USER) {
            throw new UserException(UserErrorCode.FAVOURITE_LIMIT_EXCEEDED);
        }
        try {
            return favourites.save(candidate);
        } catch (DataIntegrityViolationException ex) {
            return favourites.findByUserProfileIdAndDuplicateKey(profileId, candidate.duplicateKey())
                    .orElseThrow(() -> new UserException(UserErrorCode.FAVOURITE_CONFLICT));
        }
    }

    public FavouriteDestination updateDisplay(UUID authUserId, UUID favouriteId, String label, String subtitle) {
        UUID profileId = requireProfile(authUserId).id();
        FavouriteDestination existing = favourites.findByIdAndUserProfileId(favouriteId, profileId)
                .orElseThrow(() -> new UserException(UserErrorCode.FAVOURITE_DESTINATION_NOT_FOUND));
        existing.updateDisplay(label, subtitle, clock.instant());
        return favourites.save(existing);
    }

    public void delete(UUID authUserId, UUID favouriteId) {
        UUID profileId = requireProfile(authUserId).id();
        FavouriteDestination existing = favourites.findByIdAndUserProfileId(favouriteId, profileId)
                .orElseThrow(() -> new UserException(UserErrorCode.FAVOURITE_DESTINATION_NOT_FOUND));
        favourites.deleteByIdAndUserProfileId(existing.id(), profileId);
    }

    private UserProfile requireProfile(UUID authUserId) {
        return profiles.findByAuthUserId(authUserId)
                .orElseThrow(() -> new UserException(UserErrorCode.PROFILE_NOT_FOUND));
    }
}
