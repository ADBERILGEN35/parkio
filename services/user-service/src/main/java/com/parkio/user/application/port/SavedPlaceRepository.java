package com.parkio.user.application.port;

import com.parkio.user.domain.place.SavedPlace;
import com.parkio.user.domain.place.SavedPlaceKind;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SavedPlaceRepository {

    SavedPlace save(SavedPlace place);

    Optional<SavedPlace> findByIdAndUserProfileId(UUID id, UUID userProfileId);

    Optional<SavedPlace> findByUserProfileIdAndKind(UUID userProfileId, SavedPlaceKind kind);

    List<SavedPlace> findAllByUserProfileId(UUID userProfileId);

    long countByUserProfileIdAndKind(UUID userProfileId, SavedPlaceKind kind);

    void deleteByIdAndUserProfileId(UUID id, UUID userProfileId);

    List<LegacyHomeCandidate> findLegacyHomesMissingSavedPlace(int limit);

    record LegacyHomeCandidate(
            UUID userProfileId,
            double latitude,
            double longitude,
            String homeLabel) {
    }
}
