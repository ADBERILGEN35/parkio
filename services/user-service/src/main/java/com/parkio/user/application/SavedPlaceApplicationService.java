package com.parkio.user.application;

import com.parkio.user.application.port.SavedPlaceRepository;
import com.parkio.user.application.port.UserPreferenceRepository;
import com.parkio.user.application.port.UserProfileRepository;
import com.parkio.user.domain.UserPreference;
import com.parkio.user.domain.UserProfile;
import com.parkio.user.domain.exception.UserErrorCode;
import com.parkio.user.domain.exception.UserException;
import com.parkio.user.domain.place.PlaceDestinationSource;
import com.parkio.user.domain.place.PlaceIdentity;
import com.parkio.user.domain.place.SavedPlace;
import com.parkio.user.domain.place.SavedPlaceKind;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Saved Places CRUD plus Smart Return HOME dual-write orchestration.
 *
 * <p>Legacy Smart Return home columns remain the fallback for dual-read.
 * Dual-write is intentional and one-directional per call site to avoid loops:
 * SavedPlace HOME writes mirror legacy; Smart Return settings writes upsert HOME.
 */
@Service
@Transactional
public class SavedPlaceApplicationService {

    private static final Logger log = LoggerFactory.getLogger(SavedPlaceApplicationService.class);

    private final SavedPlaceRepository savedPlaces;
    private final UserProfileRepository profiles;
    private final UserPreferenceRepository preferences;
    private final Clock clock;

    public SavedPlaceApplicationService(
            SavedPlaceRepository savedPlaces,
            UserProfileRepository profiles,
            UserPreferenceRepository preferences,
            Clock clock) {
        this.savedPlaces = savedPlaces;
        this.profiles = profiles;
        this.preferences = preferences;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<SavedPlace> list(UUID authUserId) {
        UUID profileId = requireProfile(authUserId).id();
        return savedPlaces.findAllByUserProfileId(profileId).stream()
                .sorted(savedPlaceOrder())
                .toList();
    }

    @Transactional(readOnly = true)
    public Optional<SavedPlace> findHome(UUID userProfileId) {
        return savedPlaces.findByUserProfileIdAndKind(userProfileId, SavedPlaceKind.HOME);
    }

    public SavedPlace upsertHome(
            UUID authUserId,
            double latitude,
            double longitude,
            String label,
            PlaceDestinationSource source,
            PlaceIdentity placeIdentity,
            String subtitle) {
        return upsertSemantic(authUserId, SavedPlaceKind.HOME, latitude, longitude, label, source,
                placeIdentity, subtitle, true);
    }

    public SavedPlace upsertWork(
            UUID authUserId,
            double latitude,
            double longitude,
            String label,
            PlaceDestinationSource source,
            PlaceIdentity placeIdentity,
            String subtitle) {
        return upsertSemantic(authUserId, SavedPlaceKind.WORK, latitude, longitude, label, source,
                placeIdentity, subtitle, false);
    }

    public SavedPlace createCustom(
            UUID authUserId,
            String label,
            double latitude,
            double longitude,
            PlaceDestinationSource source,
            PlaceIdentity placeIdentity,
            String subtitle) {
        UUID profileId = requireProfile(authUserId).id();
        if (savedPlaces.countByUserProfileIdAndKind(profileId, SavedPlaceKind.CUSTOM)
                >= SavedPlace.MAX_CUSTOM_PLACES_PER_USER) {
            throw new UserException(UserErrorCode.SAVED_PLACE_LIMIT_EXCEEDED);
        }
        Instant now = clock.instant();
        SavedPlace created = SavedPlace.create(
                profileId,
                SavedPlaceKind.CUSTOM,
                label,
                latitude,
                longitude,
                source == null ? PlaceDestinationSource.MAP_PIN : source,
                placeIdentity,
                subtitle,
                now);
        try {
            return savedPlaces.save(created);
        } catch (DataIntegrityViolationException ex) {
            throw new UserException(UserErrorCode.SAVED_PLACE_CONFLICT);
        }
    }

    public SavedPlace updateCustom(
            UUID authUserId,
            UUID placeId,
            String label,
            double latitude,
            double longitude,
            PlaceDestinationSource source,
            PlaceIdentity placeIdentity,
            String subtitle) {
        UUID profileId = requireProfile(authUserId).id();
        SavedPlace existing = savedPlaces.findByIdAndUserProfileId(placeId, profileId)
                .orElseThrow(() -> new UserException(UserErrorCode.SAVED_PLACE_NOT_FOUND));
        if (existing.kind() != SavedPlaceKind.CUSTOM) {
            throw new UserException(UserErrorCode.SAVED_PLACE_CONFLICT);
        }
        existing.replaceLocation(
                label,
                latitude,
                longitude,
                source == null ? existing.source() : source,
                placeIdentity,
                subtitle,
                clock.instant());
        return savedPlaces.save(existing);
    }

    public void delete(UUID authUserId, UUID placeId) {
        UUID profileId = requireProfile(authUserId).id();
        SavedPlace existing = savedPlaces.findByIdAndUserProfileId(placeId, profileId)
                .orElseThrow(() -> new UserException(UserErrorCode.SAVED_PLACE_NOT_FOUND));
        savedPlaces.deleteByIdAndUserProfileId(placeId, profileId);
        if (existing.kind() == SavedPlaceKind.HOME) {
            clearLegacyHome(profileId);
        }
    }

    public void clearHome(UUID authUserId) {
        UUID profileId = requireProfile(authUserId).id();
        savedPlaces.findByUserProfileIdAndKind(profileId, SavedPlaceKind.HOME)
                .ifPresent(home -> savedPlaces.deleteByIdAndUserProfileId(home.id(), profileId));
        clearLegacyHome(profileId);
    }

    /**
     * Called from Smart Return settings update when home coordinates are written.
     * Upserts SavedPlace(HOME) without re-entering Smart Return write paths.
     */
    public void mirrorLegacyHomeToSavedPlace(
            UUID userProfileId,
            double latitude,
            double longitude,
            String homeLabel) {
        Instant now = clock.instant();
        Optional<SavedPlace> existing =
                savedPlaces.findByUserProfileIdAndKind(userProfileId, SavedPlaceKind.HOME);
        if (existing.isPresent()) {
            SavedPlace home = existing.get();
            home.replaceLocation(
                    homeLabel,
                    latitude,
                    longitude,
                    PlaceDestinationSource.SYSTEM,
                    home.placeIdentity(),
                    home.subtitle(),
                    now);
            savedPlaces.save(home);
            return;
        }
        try {
            savedPlaces.save(SavedPlace.create(
                    userProfileId,
                    SavedPlaceKind.HOME,
                    homeLabel,
                    latitude,
                    longitude,
                    PlaceDestinationSource.SYSTEM,
                    null,
                    null,
                    now));
        } catch (DataIntegrityViolationException ex) {
            // Concurrent insert — reload and update.
            SavedPlace home = savedPlaces.findByUserProfileIdAndKind(userProfileId, SavedPlaceKind.HOME)
                    .orElseThrow(() -> ex);
            home.replaceLocation(
                    homeLabel, latitude, longitude, PlaceDestinationSource.SYSTEM, null, null, now);
            savedPlaces.save(home);
        }
    }

    /**
     * Dual-read home coordinates for Smart Return consumers.
     * Priority: SavedPlace(HOME) → legacy user_preferences home columns.
     */
    @Transactional(readOnly = true)
    public Optional<ResolvedHome> resolveHome(UUID userProfileId, UserPreference preference) {
        Optional<SavedPlace> savedHome =
                savedPlaces.findByUserProfileIdAndKind(userProfileId, SavedPlaceKind.HOME);
        if (savedHome.isPresent()) {
            SavedPlace home = savedHome.get();
            return Optional.of(new ResolvedHome(
                    home.latitude(),
                    home.longitude(),
                    home.labelOptional().orElse(preference.homeLabel())));
        }
        if (preference.hasHomeLocation()) {
            return Optional.of(new ResolvedHome(
                    preference.homeLatitude(),
                    preference.homeLongitude(),
                    preference.homeLabel()));
        }
        return Optional.empty();
    }

    public int backfillLegacyHomes(int batchSize) {
        int migrated = 0;
        int skipped = 0;
        List<SavedPlaceRepository.LegacyHomeCandidate> batch =
                savedPlaces.findLegacyHomesMissingSavedPlace(batchSize);
        Instant now = clock.instant();
        for (SavedPlaceRepository.LegacyHomeCandidate candidate : batch) {
            try {
                savedPlaces.save(SavedPlace.create(
                        candidate.userProfileId(),
                        SavedPlaceKind.HOME,
                        candidate.homeLabel(),
                        candidate.latitude(),
                        candidate.longitude(),
                        PlaceDestinationSource.SYSTEM,
                        null,
                        null,
                        now));
                migrated++;
            } catch (IllegalArgumentException ex) {
                skipped++;
            } catch (DataIntegrityViolationException ex) {
                skipped++;
            }
        }
        log.info("saved-places home backfill batch complete migrated={} skipped={}", migrated, skipped);
        return migrated;
    }

    private SavedPlace upsertSemantic(
            UUID authUserId,
            SavedPlaceKind kind,
            double latitude,
            double longitude,
            String label,
            PlaceDestinationSource source,
            PlaceIdentity placeIdentity,
            String subtitle,
            boolean mirrorLegacy) {
        UUID profileId = requireProfile(authUserId).id();
        Instant now = clock.instant();
        PlaceDestinationSource resolvedSource =
                source == null ? PlaceDestinationSource.MAP_PIN : source;
        Optional<SavedPlace> existing = savedPlaces.findByUserProfileIdAndKind(profileId, kind);
        SavedPlace saved;
        if (existing.isPresent()) {
            SavedPlace place = existing.get();
            place.replaceLocation(label, latitude, longitude, resolvedSource, placeIdentity, subtitle, now);
            saved = savedPlaces.save(place);
        } else {
            try {
                saved = savedPlaces.save(SavedPlace.create(
                        profileId, kind, label, latitude, longitude, resolvedSource, placeIdentity, subtitle, now));
            } catch (DataIntegrityViolationException ex) {
                SavedPlace raced = savedPlaces.findByUserProfileIdAndKind(profileId, kind)
                        .orElseThrow(() -> new UserException(UserErrorCode.SAVED_PLACE_CONFLICT));
                raced.replaceLocation(label, latitude, longitude, resolvedSource, placeIdentity, subtitle, now);
                saved = savedPlaces.save(raced);
            }
        }
        if (mirrorLegacy) {
            mirrorSavedHomeToLegacy(profileId, saved);
        }
        return saved;
    }

    private void mirrorSavedHomeToLegacy(UUID profileId, SavedPlace home) {
        UserPreference preference = preferences.findByUserProfileId(profileId)
                .orElseThrow(() -> new UserException(UserErrorCode.PROFILE_NOT_FOUND));
        preference.mirrorHomeLocation(home.latitude(), home.longitude(), home.displayLabel());
        preferences.save(preference);
    }

    private void clearLegacyHome(UUID profileId) {
        preferences.findByUserProfileId(profileId).ifPresent(preference -> {
            preference.clearHomeLocation();
            preferences.save(preference);
        });
    }

    private UserProfile requireProfile(UUID authUserId) {
        return profiles.findByAuthUserId(authUserId)
                .orElseThrow(() -> new UserException(UserErrorCode.PROFILE_NOT_FOUND));
    }

    private static Comparator<SavedPlace> savedPlaceOrder() {
        return Comparator
                .comparing((SavedPlace p) -> switch (p.kind()) {
                    case HOME -> 0;
                    case WORK -> 1;
                    case CUSTOM -> 2;
                })
                .thenComparing(SavedPlace::updatedAt, Comparator.reverseOrder());
    }

    public record ResolvedHome(double latitude, double longitude, String label) {
    }
}
