package com.parkio.user.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.parkio.user.application.port.SavedPlaceRepository;
import com.parkio.user.application.port.UserPreferenceRepository;
import com.parkio.user.application.port.UserProfileRepository;
import com.parkio.user.domain.UserPreference;
import com.parkio.user.domain.UserProfile;
import com.parkio.user.domain.UserStatus;
import com.parkio.user.domain.exception.UserErrorCode;
import com.parkio.user.domain.exception.UserException;
import com.parkio.user.domain.place.PlaceDestinationSource;
import com.parkio.user.domain.place.SavedPlace;
import com.parkio.user.domain.place.SavedPlaceKind;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class SavedPlaceApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-06T12:00:00Z");
    private static final UUID AUTH = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    private static final UUID PROFILE = UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc");
    private static final UUID OTHER_AUTH = UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd");
    private static final UUID OTHER_PROFILE = UUID.fromString("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee");

    private FakeSavedPlaceRepository places;
    private FakeProfiles profiles;
    private FakePreferences preferences;
    private SavedPlaceApplicationService service;

    @BeforeEach
    void setUp() {
        places = new FakeSavedPlaceRepository();
        profiles = new FakeProfiles();
        preferences = new FakePreferences();
        profiles.put(new UserProfile(
                PROFILE, AUTH, "a@example.com", "Alice", null, null, UserStatus.ACTIVE, null, NOW, 0L));
        profiles.put(new UserProfile(
                OTHER_PROFILE, OTHER_AUTH, "b@example.com", "Bob", null, null, UserStatus.ACTIVE, null, NOW, 0L));
        preferences.save(UserPreference.createDefault(PROFILE));
        preferences.save(UserPreference.createDefault(OTHER_PROFILE));
        service = new SavedPlaceApplicationService(
                places, profiles, preferences, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void upsertHomeIsIdempotentAndMirrorsLegacy() {
        SavedPlace first = service.upsertHome(AUTH, 41.01, 28.97, null, PlaceDestinationSource.MAP_PIN, null, null);
        SavedPlace second = service.upsertHome(AUTH, 41.02, 28.98, "Ev", PlaceDestinationSource.SYSTEM, null, null);

        assertThat(first.id()).isEqualTo(second.id());
        assertThat(places.findAllByUserProfileId(PROFILE)).hasSize(1);
        UserPreference pref = preferences.findByUserProfileId(PROFILE).orElseThrow();
        assertThat(pref.homeLatitude()).isEqualTo(41.02);
        assertThat(pref.homeLongitude()).isEqualTo(28.98);
    }

    @Test
    void upsertWorkDoesNotMirrorLegacyHome() {
        service.upsertWork(AUTH, 41.04, 29.0, null, PlaceDestinationSource.MAP_PIN, null, null);
        UserPreference pref = preferences.findByUserProfileId(PROFILE).orElseThrow();
        assertThat(pref.hasHomeLocation()).isFalse();
        assertThat(places.findByUserProfileIdAndKind(PROFILE, SavedPlaceKind.WORK)).isPresent();
    }

    @Test
    void customCrudAndOrdering() {
        service.upsertHome(AUTH, 41.0, 29.0, null, PlaceDestinationSource.SYSTEM, null, null);
        service.upsertWork(AUTH, 41.1, 29.1, null, PlaceDestinationSource.MAP_PIN, null, null);
        SavedPlace custom = service.createCustom(
                AUTH, "Market", 41.2, 29.2, PlaceDestinationSource.MAP_PIN, null, null);

        List<SavedPlace> listed = service.list(AUTH);
        assertThat(listed).extracting(SavedPlace::kind)
                .containsExactly(SavedPlaceKind.HOME, SavedPlaceKind.WORK, SavedPlaceKind.CUSTOM);

        service.updateCustom(AUTH, custom.id(), "Bakkal", 41.21, 29.21, null, null, null);
        service.delete(AUTH, custom.id());
        assertThat(service.list(AUTH)).hasSize(2);
    }

    @Test
    void crossUserCannotAccess() {
        SavedPlace custom = service.createCustom(
                AUTH, "Mine", 41.0, 29.0, PlaceDestinationSource.MAP_PIN, null, null);
        assertThatThrownBy(() -> service.updateCustom(
                OTHER_AUTH, custom.id(), "Hack", 41.0, 29.0, null, null, null))
                .isInstanceOf(UserException.class)
                .extracting(ex -> ((UserException) ex).errorCode())
                .isEqualTo(UserErrorCode.SAVED_PLACE_NOT_FOUND);
    }

    @Test
    void clearHomeClearsSavedAndLegacy() {
        service.upsertHome(AUTH, 41.01, 28.97, null, PlaceDestinationSource.SYSTEM, null, null);
        UserPreference enabled = preferences.findByUserProfileId(PROFILE).orElseThrow();
        enabled.updateSmartReturnSettings(true, 41.01, 28.97, "Home", null, 30);
        preferences.save(enabled);

        service.clearHome(AUTH);

        assertThat(places.findByUserProfileIdAndKind(PROFILE, SavedPlaceKind.HOME)).isEmpty();
        UserPreference pref = preferences.findByUserProfileId(PROFILE).orElseThrow();
        assertThat(pref.hasHomeLocation()).isFalse();
        assertThat(pref.smartReturnEnabled()).isFalse();
    }

    @Test
    void dualReadPrefersSavedPlaceHome() {
        UserPreference pref = preferences.findByUserProfileId(PROFILE).orElseThrow();
        pref.mirrorHomeLocation(40.0, 29.0, "Legacy");
        preferences.save(pref);
        service.upsertHome(AUTH, 41.5, 28.5, "Saved", PlaceDestinationSource.SYSTEM, null, null);

        var resolved = service.resolveHome(PROFILE, preferences.findByUserProfileId(PROFILE).orElseThrow());
        assertThat(resolved).isPresent();
        assertThat(resolved.orElseThrow().latitude()).isEqualTo(41.5);
    }

    @Test
    void backfillIsIdempotentAndSkipsExistingHome() {
        UserPreference pref = preferences.findByUserProfileId(PROFILE).orElseThrow();
        pref.mirrorHomeLocation(41.0082, 28.9784, "Legacy Home");
        preferences.save(pref);
        places.legacyCandidates.add(new SavedPlaceRepository.LegacyHomeCandidate(
                PROFILE, 41.0082, 28.9784, "Legacy Home"));

        assertThat(service.backfillLegacyHomes(10)).isEqualTo(1);
        assertThat(service.backfillLegacyHomes(10)).isEqualTo(0);
        assertThat(places.findAllByUserProfileId(PROFILE)).hasSize(1);
    }

    @Test
    void concurrentHomeInsertRetriesUpdate() {
        places.failFirstInsert = true;
        SavedPlace home = service.upsertHome(AUTH, 41.0, 29.0, null, PlaceDestinationSource.MAP_PIN, null, null);
        assertThat(home.latitude()).isEqualTo(41.0);
        assertThat(places.findAllByUserProfileId(PROFILE)).hasSize(1);
    }

    @Test
    void mirrorLegacyDoesNotWritePreferences() {
        service.mirrorLegacyHomeToSavedPlace(PROFILE, 41.1, 28.9, "From SR");
        UserPreference pref = preferences.findByUserProfileId(PROFILE).orElseThrow();
        assertThat(pref.hasHomeLocation()).isFalse();
        assertThat(places.findByUserProfileIdAndKind(PROFILE, SavedPlaceKind.HOME)).isPresent();
    }

    private static final class FakeProfiles implements UserProfileRepository {
        private final Map<UUID, UserProfile> byAuth = new HashMap<>();
        private final Map<UUID, UserProfile> byId = new HashMap<>();

        void put(UserProfile profile) {
            byAuth.put(profile.authUserId(), profile);
            byId.put(profile.id(), profile);
        }

        @Override
        public UserProfile save(UserProfile profile) {
            put(profile);
            return profile;
        }

        @Override
        public Optional<UserProfile> findById(UUID id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public Optional<UserProfile> findByAuthUserId(UUID authUserId) {
            return Optional.ofNullable(byAuth.get(authUserId));
        }

        @Override
        public boolean existsByAuthUserId(UUID authUserId) {
            return byAuth.containsKey(authUserId);
        }
    }

    private static final class FakePreferences implements UserPreferenceRepository {
        private final Map<UUID, UserPreference> byProfile = new HashMap<>();

        @Override
        public UserPreference save(UserPreference preference) {
            byProfile.put(preference.userProfileId(), preference);
            return preference;
        }

        @Override
        public Optional<UserPreference> findByUserProfileId(UUID userProfileId) {
            return Optional.ofNullable(byProfile.get(userProfileId));
        }

        @Override
        public List<UserPreference> claimDueSmartReturnPrompts(LocalDate promptDate, int limit) {
            return List.of();
        }

        @Override
        public List<UserPreference> claimDueSmartReturnChecks(Instant now, int limit) {
            return List.of();
        }
    }

    private static final class FakeSavedPlaceRepository implements SavedPlaceRepository {
        private final Map<UUID, SavedPlace> byId = new HashMap<>();
        private final List<LegacyHomeCandidate> legacyCandidates = new ArrayList<>();
        private boolean failFirstInsert;
        private final AtomicInteger insertAttempts = new AtomicInteger();

        @Override
        public SavedPlace save(SavedPlace place) {
            boolean isNew = !byId.containsKey(place.id());
            if (isNew && place.kind() == SavedPlaceKind.HOME) {
                Optional<SavedPlace> existingHome =
                        findByUserProfileIdAndKind(place.userProfileId(), SavedPlaceKind.HOME);
                if (existingHome.isPresent() && !existingHome.get().id().equals(place.id())) {
                    throw new DataIntegrityViolationException("duplicate home");
                }
                if (failFirstInsert && insertAttempts.getAndIncrement() == 0) {
                    SavedPlace raced = SavedPlace.create(
                            place.userProfileId(),
                            SavedPlaceKind.HOME,
                            place.label(),
                            place.latitude(),
                            place.longitude(),
                            place.source(),
                            place.placeIdentity(),
                            place.subtitle(),
                            place.createdAt());
                    byId.put(raced.id(), raced);
                    throw new DataIntegrityViolationException("race");
                }
            }
            byId.put(place.id(), place);
            return place;
        }

        @Override
        public Optional<SavedPlace> findByIdAndUserProfileId(UUID id, UUID userProfileId) {
            return Optional.ofNullable(byId.get(id))
                    .filter(p -> p.userProfileId().equals(userProfileId));
        }

        @Override
        public Optional<SavedPlace> findByUserProfileIdAndKind(UUID userProfileId, SavedPlaceKind kind) {
            return byId.values().stream()
                    .filter(p -> p.userProfileId().equals(userProfileId) && p.kind() == kind)
                    .findFirst();
        }

        @Override
        public List<SavedPlace> findAllByUserProfileId(UUID userProfileId) {
            return byId.values().stream().filter(p -> p.userProfileId().equals(userProfileId)).toList();
        }

        @Override
        public long countByUserProfileIdAndKind(UUID userProfileId, SavedPlaceKind kind) {
            return findAllByUserProfileId(userProfileId).stream().filter(p -> p.kind() == kind).count();
        }

        @Override
        public void deleteByIdAndUserProfileId(UUID id, UUID userProfileId) {
            findByIdAndUserProfileId(id, userProfileId).ifPresent(p -> byId.remove(p.id()));
        }

        @Override
        public List<LegacyHomeCandidate> findLegacyHomesMissingSavedPlace(int limit) {
            return legacyCandidates.stream()
                    .filter(c -> findByUserProfileIdAndKind(c.userProfileId(), SavedPlaceKind.HOME).isEmpty())
                    .limit(limit)
                    .toList();
        }
    }
}
