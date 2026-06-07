package com.parkio.user.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.parkio.user.application.command.CreateProfileCommand;
import com.parkio.user.application.command.UpdatePreferencesCommand;
import com.parkio.user.application.command.UpdateProfileCommand;
import com.parkio.user.application.command.UpsertVehicleCommand;
import com.parkio.user.application.event.UserRegisteredEvent;
import com.parkio.user.application.port.OutboxEventAppender;
import com.parkio.user.application.port.UserPreferenceRepository;
import com.parkio.user.application.port.UserProfileRepository;
import com.parkio.user.application.port.UserTrustProfileRepository;
import com.parkio.user.application.port.UserTrustScoreHistoryRepository;
import com.parkio.user.application.port.UserVehicleProfileRepository;
import com.parkio.user.application.result.PublicProfileView;
import com.parkio.user.domain.TrustBand;
import com.parkio.user.domain.UserPreference;
import com.parkio.user.domain.UserProfile;
import com.parkio.user.domain.UserStatus;
import com.parkio.user.domain.UserTrustProfile;
import com.parkio.user.domain.UserTrustScoreHistory;
import com.parkio.user.domain.UserVehicleProfile;
import com.parkio.user.domain.VehicleType;
import com.parkio.user.domain.event.UserProfileCreatedEvent;
import com.parkio.user.domain.exception.UserErrorCode;
import com.parkio.user.domain.exception.UserException;
import java.lang.reflect.RecordComponent;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Behavioural unit tests for {@link UserApplicationService} using in-memory fake
 * ports — no Spring context, no database.
 */
class UserApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-06T12:00:00Z");

    private FakeUserProfileRepository profiles;
    private FakeUserPreferenceRepository preferences;
    private FakeUserVehicleProfileRepository vehicles;
    private FakeUserTrustProfileRepository trustProfiles;
    private FakeUserTrustScoreHistoryRepository trustHistory;
    private FakeOutboxEventAppender outbox;
    private UserApplicationService service;

    @BeforeEach
    void setUp() {
        profiles = new FakeUserProfileRepository();
        preferences = new FakeUserPreferenceRepository();
        vehicles = new FakeUserVehicleProfileRepository();
        trustProfiles = new FakeUserTrustProfileRepository();
        trustHistory = new FakeUserTrustScoreHistoryRepository();
        outbox = new FakeOutboxEventAppender();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new UserApplicationService(profiles, preferences, vehicles, trustProfiles,
                trustHistory, outbox, clock);
    }

    private CreateProfileCommand command(UUID authUserId) {
        return new CreateProfileCommand(authUserId, "owner@example.com", "Jane Driver", "+905551112233", "Istanbul");
    }

    @Test
    void createProfileInitialisesDefaultsAndOutboxEvent() {
        UUID authUserId = UUID.randomUUID();

        UserProfile profile = service.createProfile(command(authUserId));

        assertThat(profile.authUserId()).isEqualTo(authUserId);
        assertThat(profile.displayName()).isEqualTo("Jane Driver");
        assertThat(profile.status()).isEqualTo(UserStatus.ACTIVE);

        UserPreference pref = preferences.findByUserProfileId(profile.id()).orElseThrow();
        assertThat(pref.preferredRadiusMeters()).isEqualTo(UserPreference.DEFAULT_RADIUS_METERS);
        assertThat(pref.notificationsEnabled()).isTrue();

        UserTrustProfile trust = trustProfiles.findByUserProfileId(profile.id()).orElseThrow();
        assertThat(trust.trustScore()).isEqualTo(100);
        assertThat(trust.trustBand()).isEqualTo(TrustBand.HIGH_TRUST);
        assertThat(trust.totalPoints()).isZero();
        assertThat(trust.currentLevel()).isEqualTo(1);

        assertThat(trustHistory.entries).singleElement().satisfies(h -> {
            assertThat(h.previousScore()).isNull();
            assertThat(h.newScore()).isEqualTo(100);
            assertThat(h.reason()).isEqualTo(UserTrustScoreHistory.REASON_INITIAL);
        });

        assertThat(outbox.events).singleElement()
                .extracting(UserProfileCreatedEvent::authUserId).isEqualTo(authUserId);
    }

    @Test
    void createProfileRejectsDuplicate() {
        UUID authUserId = UUID.randomUUID();
        service.createProfile(command(authUserId));

        assertThatThrownBy(() -> service.createProfile(command(authUserId)))
                .isInstanceOf(UserException.class)
                .extracting(e -> ((UserException) e).errorCode())
                .isEqualTo(UserErrorCode.PROFILE_ALREADY_EXISTS);
    }

    @Test
    void handleUserRegisteredCreatesDefaultProfileIdempotently() {
        UUID authUserId = UUID.randomUUID();
        UserRegisteredEvent event = new UserRegisteredEvent(UUID.randomUUID(), authUserId, "john.doe@example.com", NOW);

        service.handleUserRegistered(event);
        service.handleUserRegistered(event); // redelivery must be a no-op

        assertThat(profiles.byId).hasSize(1);
        UserProfile profile = profiles.findByAuthUserId(authUserId).orElseThrow();
        assertThat(profile.displayName()).isEqualTo("john.doe");
        assertThat(profile.status()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    void getMyProfileThrowsWhenMissing() {
        assertThatThrownBy(() -> service.getMyProfile(UUID.randomUUID()))
                .isInstanceOf(UserException.class)
                .extracting(e -> ((UserException) e).errorCode())
                .isEqualTo(UserErrorCode.PROFILE_NOT_FOUND);
    }

    @Test
    void updateMyProfileChangesProvidedFieldsOnly() {
        UUID authUserId = UUID.randomUUID();
        service.createProfile(command(authUserId));

        UserProfile updated = service.updateMyProfile(authUserId,
                new UpdateProfileCommand("New Name", null, "Ankara"));

        assertThat(updated.displayName()).isEqualTo("New Name");
        assertThat(updated.city()).isEqualTo("Ankara");
        assertThat(updated.phoneNumber()).isEqualTo("+905551112233"); // unchanged
    }

    @Test
    void updateMyPreferencesAppliesChanges() {
        UUID authUserId = UUID.randomUUID();
        service.createProfile(command(authUserId));

        UserPreference updated = service.updateMyPreferences(authUserId,
                new UpdatePreferencesCommand(2500, false));

        assertThat(updated.preferredRadiusMeters()).isEqualTo(2500);
        assertThat(updated.notificationsEnabled()).isFalse();
    }

    @Test
    void updateMyPreferencesRejectsRadiusOutOfRange() {
        UUID authUserId = UUID.randomUUID();
        service.createProfile(command(authUserId));

        assertThatThrownBy(() -> service.updateMyPreferences(authUserId,
                new UpdatePreferencesCommand(UserPreference.MAX_RADIUS_METERS + 1, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void vehicleProfileIsEmptyUntilSetThenUpserted() {
        UUID authUserId = UUID.randomUUID();
        service.createProfile(command(authUserId));

        assertThat(service.getMyVehicle(authUserId)).isEmpty();

        service.upsertMyVehicle(authUserId, new UpsertVehicleCommand(VehicleType.SEDAN, "34 abc 34"));
        UserVehicleProfile stored = service.getMyVehicle(authUserId).orElseThrow();
        assertThat(stored.vehicleType()).isEqualTo(VehicleType.SEDAN);
        assertThat(stored.plate()).isEqualTo("34 ABC 34"); // normalised upper-case

        service.upsertMyVehicle(authUserId, new UpsertVehicleCommand(VehicleType.SUV, null));
        UserVehicleProfile replaced = service.getMyVehicle(authUserId).orElseThrow();
        assertThat(replaced.vehicleType()).isEqualTo(VehicleType.SUV);
        assertThat(replaced.plate()).isNull();
    }

    @Test
    void getMyStatsReturnsProjection() {
        UUID authUserId = UUID.randomUUID();
        service.createProfile(command(authUserId));

        UserTrustProfile stats = service.getMyStats(authUserId);

        assertThat(stats.trustScore()).isEqualTo(100);
        assertThat(stats.trustBand()).isEqualTo(TrustBand.HIGH_TRUST);
        assertThat(stats.currentLevel()).isEqualTo(1);
    }

    @Test
    void publicProfileIsLookedUpByAuthUserIdAndExposesItAsUserId() {
        UUID authUserId = UUID.randomUUID();
        UserProfile profile = service.createProfile(command(authUserId));
        service.upsertMyVehicle(authUserId, new UpsertVehicleCommand(VehicleType.SEDAN, "34 ABC 34"));

        PublicProfileView view = service.getPublicProfile(authUserId);

        // userId in the public contract is the platform-wide authUserId, not the
        // internal user_profiles.id.
        assertThat(view.userId()).isEqualTo(authUserId);
        assertThat(view.userId()).isNotEqualTo(profile.id());
        assertThat(view.displayName()).isEqualTo("Jane Driver");
        assertThat(view.city()).isEqualTo("Istanbul");
        assertThat(view.trustBand()).isEqualTo(TrustBand.HIGH_TRUST);
        assertThat(view.currentLevel()).isEqualTo(1);

        // Privacy guard: the public view must not carry email / phone / plate.
        List<String> componentNames = new ArrayList<>();
        for (RecordComponent rc : PublicProfileView.class.getRecordComponents()) {
            componentNames.add(rc.getName().toLowerCase());
        }
        assertThat(componentNames)
                .noneMatch(n -> n.contains("email") || n.contains("phone") || n.contains("plate"));
    }

    @Test
    void getPublicProfileByInternalProfileIdIsTreatedAsNotFound() {
        UUID authUserId = UUID.randomUUID();
        UserProfile profile = service.createProfile(command(authUserId));

        // The internal profile id is not a valid public identifier.
        assertThatThrownBy(() -> service.getPublicProfile(profile.id()))
                .isInstanceOf(UserException.class)
                .extracting(e -> ((UserException) e).errorCode())
                .isEqualTo(UserErrorCode.PROFILE_NOT_FOUND);
    }

    @Test
    void getPublicProfileThrowsWhenMissing() {
        assertThatThrownBy(() -> service.getPublicProfile(UUID.randomUUID()))
                .isInstanceOf(UserException.class)
                .extracting(e -> ((UserException) e).errorCode())
                .isEqualTo(UserErrorCode.PROFILE_NOT_FOUND);
    }

    // --- Fakes -----------------------------------------------------------

    private static final class FakeUserProfileRepository implements UserProfileRepository {
        private final Map<UUID, UserProfile> byId = new HashMap<>();

        @Override
        public UserProfile save(UserProfile profile) {
            byId.put(profile.id(), profile);
            return profile;
        }

        @Override
        public Optional<UserProfile> findByAuthUserId(UUID authUserId) {
            return byId.values().stream().filter(p -> p.authUserId().equals(authUserId)).findFirst();
        }

        @Override
        public boolean existsByAuthUserId(UUID authUserId) {
            return byId.values().stream().anyMatch(p -> p.authUserId().equals(authUserId));
        }
    }

    private static final class FakeUserPreferenceRepository implements UserPreferenceRepository {
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
    }

    private static final class FakeUserVehicleProfileRepository implements UserVehicleProfileRepository {
        private final Map<UUID, UserVehicleProfile> byProfile = new HashMap<>();

        @Override
        public UserVehicleProfile save(UserVehicleProfile vehicle) {
            byProfile.put(vehicle.userProfileId(), vehicle);
            return vehicle;
        }

        @Override
        public Optional<UserVehicleProfile> findByUserProfileId(UUID userProfileId) {
            return Optional.ofNullable(byProfile.get(userProfileId));
        }
    }

    private static final class FakeUserTrustProfileRepository implements UserTrustProfileRepository {
        private final Map<UUID, UserTrustProfile> byProfile = new HashMap<>();

        @Override
        public UserTrustProfile save(UserTrustProfile trustProfile) {
            byProfile.put(trustProfile.userProfileId(), trustProfile);
            return trustProfile;
        }

        @Override
        public Optional<UserTrustProfile> findByUserProfileId(UUID userProfileId) {
            return Optional.ofNullable(byProfile.get(userProfileId));
        }
    }

    private static final class FakeUserTrustScoreHistoryRepository implements UserTrustScoreHistoryRepository {
        private final List<UserTrustScoreHistory> entries = new ArrayList<>();

        @Override
        public UserTrustScoreHistory save(UserTrustScoreHistory history) {
            entries.add(history);
            return history;
        }
    }

    private static final class FakeOutboxEventAppender implements OutboxEventAppender {
        private final List<UserProfileCreatedEvent> events = new ArrayList<>();

        @Override
        public void append(UserProfileCreatedEvent event) {
            events.add(event);
        }
    }
}
