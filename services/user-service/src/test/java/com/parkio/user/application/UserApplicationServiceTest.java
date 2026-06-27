package com.parkio.user.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.parkio.user.application.command.CreateProfileCommand;
import com.parkio.user.application.command.SmartReturnReturnTimeCommand;
import com.parkio.user.application.command.UpdatePreferencesCommand;
import com.parkio.user.application.command.UpdateProfileCommand;
import com.parkio.user.application.command.UpdateSmartReturnSettingsCommand;
import com.parkio.user.application.command.UpsertVehicleCommand;
import com.parkio.user.application.event.UserRegisteredEvent;
import com.parkio.user.application.event.UserRestoredEvent;
import com.parkio.user.application.event.UserSuspendedEvent;
import com.parkio.user.application.port.InboxEventRepository;
import com.parkio.user.application.port.OutboxEventAppender;
import com.parkio.user.application.port.PendingUserStatusEventRepository;
import com.parkio.user.application.port.UserPreferenceRepository;
import com.parkio.user.application.port.UserProfileRepository;
import com.parkio.user.application.port.UserTrustProfileRepository;
import com.parkio.user.application.port.UserTrustScoreHistoryRepository;
import com.parkio.user.application.port.UserVehicleProfileRepository;
import com.parkio.user.application.result.AccountStatusView;
import com.parkio.user.application.result.PublicProfileView;
import com.parkio.user.domain.PendingUserStatusEvent;
import com.parkio.user.domain.SmartReturnTodayStatus;
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
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.RecordComponent;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
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
    private FakeInboxEventRepository inbox;
    private FakePendingUserStatusEventRepository pendingStatusEvents;
    private SimpleMeterRegistry meterRegistry;
    private UserApplicationService service;

    @BeforeEach
    void setUp() {
        profiles = new FakeUserProfileRepository();
        preferences = new FakeUserPreferenceRepository();
        vehicles = new FakeUserVehicleProfileRepository();
        trustProfiles = new FakeUserTrustProfileRepository();
        trustHistory = new FakeUserTrustScoreHistoryRepository();
        outbox = new FakeOutboxEventAppender();
        inbox = new FakeInboxEventRepository();
        pendingStatusEvents = new FakePendingUserStatusEventRepository();
        meterRegistry = new SimpleMeterRegistry();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new UserApplicationService(profiles, preferences, vehicles, trustProfiles,
                trustHistory, outbox, inbox, pendingStatusEvents, clock, new SmartReturnMetrics(meterRegistry));
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
        assertThat(inbox.processed).containsKey(event.eventId());
    }

    @Test
    void handleUserRegisteredSkipsWhenEventAlreadyInInbox() {
        UUID authUserId = UUID.randomUUID();
        UserRegisteredEvent event = new UserRegisteredEvent(UUID.randomUUID(), authUserId, "jane@example.com", NOW);
        inbox.markProcessed(event.eventId(), UserRegisteredEvent.TYPE, NOW); // already processed

        service.handleUserRegistered(event);

        assertThat(profiles.byId).isEmpty(); // dedup via inbox: no profile created
    }

    @Test
    void handleUserSuspendedThenRestoredFlipsStatusIdempotently() {
        UUID authUserId = UUID.randomUUID();
        UserProfile profile = service.createProfile(command(authUserId));

        UserSuspendedEvent suspend = new UserSuspendedEvent(
                UUID.randomUUID(), UUID.randomUUID(), authUserId, UUID.randomUUID(), NOW);
        service.handleUserSuspended(suspend);
        service.handleUserSuspended(suspend); // redelivery — inbox no-op

        assertThat(profiles.byId.get(profile.id()).status()).isEqualTo(UserStatus.SUSPENDED);

        UserRestoredEvent restore = new UserRestoredEvent(
                UUID.randomUUID(), UUID.randomUUID(), authUserId, UUID.randomUUID(), NOW);
        service.handleUserRestored(restore);

        assertThat(profiles.byId.get(profile.id()).status()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    void suspendBeforeRegistrationIsParkedAndAppliedDuringProvisioning() {
        UUID authUserId = UUID.randomUUID();
        UserSuspendedEvent suspend = new UserSuspendedEvent(
                UUID.randomUUID(), UUID.randomUUID(), authUserId, UUID.randomUUID(), NOW.minusSeconds(60));

        service.handleUserSuspended(suspend); // profile does not exist yet

        assertThat(profiles.byId).isEmpty();
        assertThat(pendingStatusEvents.byId).hasSize(1); // parked, not lost
        assertThat(inbox.processed).containsKey(suspend.eventId());

        service.handleUserRegistered(new UserRegisteredEvent(
                UUID.randomUUID(), authUserId, "late@example.com", NOW));

        UserProfile profile = profiles.findByAuthUserId(authUserId).orElseThrow();
        assertThat(profile.status()).isEqualTo(UserStatus.SUSPENDED);
        assertThat(profile.lastStatusEventAt()).isEqualTo(NOW.minusSeconds(60));
        assertThat(pendingStatusEvents.byId).isEmpty(); // consumed
        assertThat(service.getAccountStatus(authUserId).status()).isEqualTo(UserStatus.SUSPENDED);
    }

    @Test
    void restoreBeforeRegistrationIsParkedAndAppliedDuringProvisioning() {
        UUID authUserId = UUID.randomUUID();
        service.handleUserSuspended(new UserSuspendedEvent(
                UUID.randomUUID(), UUID.randomUUID(), authUserId, UUID.randomUUID(), NOW.minusSeconds(120)));
        service.handleUserRestored(new UserRestoredEvent(
                UUID.randomUUID(), UUID.randomUUID(), authUserId, UUID.randomUUID(), NOW.minusSeconds(60)));

        service.handleUserRegistered(new UserRegisteredEvent(
                UUID.randomUUID(), authUserId, "late@example.com", NOW));

        // The latest pending event by occurredAt (the restore) wins.
        UserProfile profile = profiles.findByAuthUserId(authUserId).orElseThrow();
        assertThat(profile.status()).isEqualTo(UserStatus.ACTIVE);
        assertThat(profile.lastStatusEventAt()).isEqualTo(NOW.minusSeconds(60));
        assertThat(pendingStatusEvents.byId).isEmpty();
    }

    @Test
    void latestPendingSuspensionWinsOverOlderRestoreDuringProvisioning() {
        UUID authUserId = UUID.randomUUID();
        // Out-of-order arrival: the newer suspension is consumed first.
        service.handleUserSuspended(new UserSuspendedEvent(
                UUID.randomUUID(), UUID.randomUUID(), authUserId, UUID.randomUUID(), NOW.minusSeconds(60)));
        service.handleUserRestored(new UserRestoredEvent(
                UUID.randomUUID(), UUID.randomUUID(), authUserId, UUID.randomUUID(), NOW.minusSeconds(120)));

        service.handleUserRegistered(new UserRegisteredEvent(
                UUID.randomUUID(), authUserId, "late@example.com", NOW));

        assertThat(profiles.findByAuthUserId(authUserId).orElseThrow().status())
                .isEqualTo(UserStatus.SUSPENDED);
    }

    @Test
    void staleRestoreDoesNotOverrideNewerSuspension() {
        UUID authUserId = UUID.randomUUID();
        UserProfile profile = service.createProfile(command(authUserId));

        service.handleUserSuspended(new UserSuspendedEvent(
                UUID.randomUUID(), UUID.randomUUID(), authUserId, UUID.randomUUID(), NOW));
        // An older restore delivered late (out of order) must not lift the suspension.
        service.handleUserRestored(new UserRestoredEvent(
                UUID.randomUUID(), UUID.randomUUID(), authUserId, UUID.randomUUID(), NOW.minusSeconds(300)));

        assertThat(profiles.byId.get(profile.id()).status()).isEqualTo(UserStatus.SUSPENDED);
        assertThat(profiles.byId.get(profile.id()).lastStatusEventAt()).isEqualTo(NOW);
    }

    @Test
    void staleSuspendDoesNotOverrideNewerRestore() {
        UUID authUserId = UUID.randomUUID();
        UserProfile profile = service.createProfile(command(authUserId));

        service.handleUserRestored(new UserRestoredEvent(
                UUID.randomUUID(), UUID.randomUUID(), authUserId, UUID.randomUUID(), NOW));
        service.handleUserSuspended(new UserSuspendedEvent(
                UUID.randomUUID(), UUID.randomUUID(), authUserId, UUID.randomUUID(), NOW.minusSeconds(300)));

        assertThat(profiles.byId.get(profile.id()).status()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    void duplicateStatusEventIsIdempotent() {
        UUID authUserId = UUID.randomUUID();
        UserProfile profile = service.createProfile(command(authUserId));
        UserSuspendedEvent suspend = new UserSuspendedEvent(
                UUID.randomUUID(), UUID.randomUUID(), authUserId, UUID.randomUUID(), NOW.minusSeconds(120));

        service.handleUserSuspended(suspend);
        service.handleUserRestored(new UserRestoredEvent(
                UUID.randomUUID(), UUID.randomUUID(), authUserId, UUID.randomUUID(), NOW));
        service.handleUserSuspended(suspend); // redelivery of the old suspend: inbox no-op

        assertThat(profiles.byId.get(profile.id()).status()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    void getAccountStatusReturnsIdAndStatusForActiveUser() {
        UUID authUserId = UUID.randomUUID();
        service.createProfile(command(authUserId));

        AccountStatusView view = service.getAccountStatus(authUserId);

        assertThat(view.userId()).isEqualTo(authUserId);
        assertThat(view.status()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    void getAccountStatusReflectsSuspension() {
        UUID authUserId = UUID.randomUUID();
        service.createProfile(command(authUserId));
        service.handleUserSuspended(new UserSuspendedEvent(
                UUID.randomUUID(), UUID.randomUUID(), authUserId, UUID.randomUUID(), NOW));

        assertThat(service.getAccountStatus(authUserId).status()).isEqualTo(UserStatus.SUSPENDED);
    }

    @Test
    void getAccountStatusThrowsNotFoundWhenProfileMissing() {
        assertThatThrownBy(() -> service.getAccountStatus(UUID.randomUUID()))
                .isInstanceOf(UserException.class)
                .extracting(e -> ((UserException) e).errorCode())
                .isEqualTo(UserErrorCode.PROFILE_NOT_FOUND);
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
    void smartReturnSettingsRequireHomeBeforeEnable() {
        UUID authUserId = UUID.randomUUID();
        service.createProfile(command(authUserId));

        assertThatThrownBy(() -> service.updateMySmartReturnSettings(authUserId,
                new UpdateSmartReturnSettingsCommand(true, null, null, null, LocalTime.of(18, 30), 15)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Home location");
    }

    @Test
    void smartReturnTodayFlowStoresOnlyCurrentPlanState() {
        UUID authUserId = UUID.randomUUID();
        service.createProfile(command(authUserId));
        service.updateMySmartReturnSettings(authUserId,
                new UpdateSmartReturnSettingsCommand(true, 38.4237, 27.1428,
                        "Konak", LocalTime.of(18, 30), 15));

        UserPreference leftByCar = service.markSmartReturnLeftByCar(authUserId,
                new SmartReturnReturnTimeCommand(NOW.plusSeconds(3600)));

        assertThat(leftByCar.smartReturnTodayStatus()).isEqualTo(com.parkio.user.domain.SmartReturnTodayStatus.LEFT_BY_CAR);
        assertThat(leftByCar.todayExpectedReturnAt()).isEqualTo(NOW.plusSeconds(3600));

        UserPreference cancelled = service.cancelSmartReturnToday(authUserId);
        assertThat(cancelled.smartReturnTodayStatus()).isEqualTo(com.parkio.user.domain.SmartReturnTodayStatus.CANCELLED);
        assertThat(cancelled.todayExpectedReturnAt()).isNull();
        assertThat(meterRegistry.counter("parkio.smart_return.morning_prompt_answers_total",
                "answer", "left_by_car").count()).isEqualTo(1.0);
        assertThat(meterRegistry.counter("parkio.smart_return.morning_prompt_answers_total",
                "answer", "cancelled").count()).isEqualTo(1.0);
    }

    @Test
    void smartReturnPromptClaimMarksPromptDateAndPreventsDuplicatePrompt() {
        UUID authUserId = UUID.randomUUID();
        service.createProfile(command(authUserId));
        service.updateMySmartReturnSettings(authUserId,
                new UpdateSmartReturnSettingsCommand(true, 38.4237, 27.1428,
                        "Konak", LocalTime.of(18, 30), 15));
        LocalDate promptDate = LocalDate.of(2026, 6, 6);

        assertThat(service.claimDueSmartReturnPrompts(promptDate, 10))
                .singleElement()
                .satisfies(c -> assertThat(c.userId()).isEqualTo(authUserId));
        assertThat(service.claimDueSmartReturnPrompts(promptDate, 10)).isEmpty();
    }

    @Test
    void smartReturnReturnCheckClaimMarksInProgressWithoutCompleting() {
        UUID authUserId = UUID.randomUUID();
        service.createProfile(command(authUserId));
        service.updateMySmartReturnSettings(authUserId,
                new UpdateSmartReturnSettingsCommand(true, 38.4237, 27.1428,
                        "Konak", LocalTime.of(18, 30), 15));
        service.markSmartReturnLeftByCar(authUserId, new SmartReturnReturnTimeCommand(NOW.plusSeconds(60)));

        assertThat(service.claimDueSmartReturnChecks(NOW, 10))
                .singleElement()
                .satisfies(c -> {
                    assertThat(c.userId()).isEqualTo(authUserId);
                    assertThat(c.homeLatitude()).isEqualTo(38.4237);
                    assertThat(c.homeLongitude()).isEqualTo(27.1428);
                    assertThat(c.claimRetried()).isFalse();
                });
        assertThat(service.claimDueSmartReturnChecks(NOW, 10)).isEmpty();
        UserPreference claimed = service.getMySmartReturn(authUserId);
        assertThat(claimed.smartReturnTodayStatus()).isEqualTo(SmartReturnTodayStatus.RETURN_CHECK_IN_PROGRESS);
        assertThat(claimed.todayReturnCheckClaimedAt()).isEqualTo(NOW);
        assertThat(claimed.todayReturnCheckClaimExpiresAt()).isEqualTo(NOW.plusSeconds(300));
        assertThat(claimed.todayReturnCheckCompletedAt()).isNull();
    }

    @Test
    void smartReturnExpiredClaimRetriesAfterCrashBeforeParkingSearch() {
        UUID authUserId = UUID.randomUUID();
        service.createProfile(command(authUserId));
        service.updateMySmartReturnSettings(authUserId,
                new UpdateSmartReturnSettingsCommand(true, 38.4237, 27.1428,
                        "Konak", LocalTime.of(18, 30), 15));
        service.markSmartReturnLeftByCar(authUserId, new SmartReturnReturnTimeCommand(NOW.plusSeconds(60)));

        assertThat(service.claimDueSmartReturnChecks(NOW, 10)).hasSize(1);
        assertThat(service.claimDueSmartReturnChecks(NOW.plusSeconds(299), 10)).isEmpty();
        assertThat(service.claimDueSmartReturnChecks(NOW.plusSeconds(301), 10))
                .singleElement()
                .satisfies(c -> assertThat(c.claimRetried()).isTrue());
    }

    @Test
    void smartReturnDuplicateSchedulerExecutionDoesNotDoubleClaimBeforeExpiry() {
        UUID authUserId = UUID.randomUUID();
        service.createProfile(command(authUserId));
        service.updateMySmartReturnSettings(authUserId,
                new UpdateSmartReturnSettingsCommand(true, 38.4237, 27.1428,
                        "Konak", LocalTime.of(18, 30), 15));
        service.markSmartReturnLeftByCar(authUserId, new SmartReturnReturnTimeCommand(NOW.plusSeconds(60)));

        assertThat(service.claimDueSmartReturnChecks(NOW, 10)).hasSize(1);
        assertThat(service.claimDueSmartReturnChecks(NOW, 10)).isEmpty();
    }

    @Test
    void smartReturnNotificationCompletionSendsOnceAndPreventsRetry() {
        UUID authUserId = UUID.randomUUID();
        service.createProfile(command(authUserId));
        service.updateMySmartReturnSettings(authUserId,
                new UpdateSmartReturnSettingsCommand(true, 38.4237, 27.1428,
                        "Konak", LocalTime.of(18, 30), 15));
        service.markSmartReturnLeftByCar(authUserId, new SmartReturnReturnTimeCommand(NOW.plusSeconds(60)));

        service.claimDueSmartReturnChecks(NOW, 10);
        service.completeSmartReturnCheck(authUserId, true, NOW.plusSeconds(5));

        UserPreference completed = service.getMySmartReturn(authUserId);
        assertThat(completed.todayNotificationSentAt()).isEqualTo(NOW.plusSeconds(5));
        assertThat(completed.todayReturnCheckCompletedAt()).isEqualTo(NOW.plusSeconds(5));
        assertThat(service.claimDueSmartReturnChecks(NOW.plusSeconds(301), 10)).isEmpty();
    }

    @Test
    void smartReturnNoSpotsCompletionPreventsRetryWithoutNotificationTimestamp() {
        UUID authUserId = UUID.randomUUID();
        service.createProfile(command(authUserId));
        service.updateMySmartReturnSettings(authUserId,
                new UpdateSmartReturnSettingsCommand(true, 38.4237, 27.1428,
                        "Konak", LocalTime.of(18, 30), 15));
        service.markSmartReturnLeftByCar(authUserId, new SmartReturnReturnTimeCommand(NOW.plusSeconds(60)));

        service.claimDueSmartReturnChecks(NOW, 10);
        service.completeSmartReturnCheck(authUserId, false, NOW.plusSeconds(5));

        UserPreference completed = service.getMySmartReturn(authUserId);
        assertThat(completed.todayNotificationSentAt()).isNull();
        assertThat(completed.todayReturnCheckCompletedAt()).isEqualTo(NOW.plusSeconds(5));
        assertThat(service.claimDueSmartReturnChecks(NOW.plusSeconds(301), 10)).isEmpty();
    }

    @Test
    void smartReturnCancellationPreventsRetryOfInProgressClaim() {
        UUID authUserId = UUID.randomUUID();
        service.createProfile(command(authUserId));
        service.updateMySmartReturnSettings(authUserId,
                new UpdateSmartReturnSettingsCommand(true, 38.4237, 27.1428,
                        "Konak", LocalTime.of(18, 30), 15));
        service.markSmartReturnLeftByCar(authUserId, new SmartReturnReturnTimeCommand(NOW.plusSeconds(60)));

        service.claimDueSmartReturnChecks(NOW, 10);
        service.cancelSmartReturnToday(authUserId);

        assertThat(service.claimDueSmartReturnChecks(NOW.plusSeconds(301), 10)).isEmpty();
    }

    @Test
    void smartReturnDisabledFeaturePreventsScheduling() {
        UUID authUserId = UUID.randomUUID();
        service.createProfile(command(authUserId));
        service.updateMySmartReturnSettings(authUserId,
                new UpdateSmartReturnSettingsCommand(true, 38.4237, 27.1428,
                        "Konak", LocalTime.of(18, 30), 15));
        service.markSmartReturnLeftByCar(authUserId, new SmartReturnReturnTimeCommand(NOW.plusSeconds(60)));
        service.updateMySmartReturnSettings(authUserId,
                new UpdateSmartReturnSettingsCommand(false, null, null, null, null, null));

        assertThat(service.claimDueSmartReturnPrompts(LocalDate.of(2026, 6, 6), 10)).isEmpty();
        assertThat(service.claimDueSmartReturnChecks(NOW, 10)).isEmpty();
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
        public Optional<UserProfile> findById(UUID id) {
            return Optional.ofNullable(byId.get(id));
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

        @Override
        public List<UserPreference> claimDueSmartReturnPrompts(java.time.LocalDate promptDate, int limit) {
            return byProfile.values().stream()
                    .filter(p -> p.smartReturnEnabled() && p.notificationsEnabled() && p.hasHomeLocation())
                    .filter(p -> p.lastSmartReturnPromptDate() == null
                            || !p.lastSmartReturnPromptDate().equals(promptDate))
                    .limit(limit)
                    .toList();
        }

        @Override
        public List<UserPreference> claimDueSmartReturnChecks(Instant now, int limit) {
            return byProfile.values().stream()
                    .filter(p -> p.smartReturnEnabled() && p.notificationsEnabled() && p.hasHomeLocation())
                    .filter(p -> p.smartReturnTodayStatus() == SmartReturnTodayStatus.LEFT_BY_CAR
                            || (p.smartReturnTodayStatus() == SmartReturnTodayStatus.RETURN_CHECK_IN_PROGRESS
                            && p.todayReturnCheckClaimExpiresAt() != null
                            && !p.todayReturnCheckClaimExpiresAt().isAfter(now)))
                    .filter(p -> p.todayExpectedReturnAt() != null)
                    .filter(p -> p.todayReturnCheckCompletedAt() == null)
                    .filter(p -> !p.todayExpectedReturnAt()
                            .minusSeconds((long) p.reminderLeadMinutes() * 60)
                            .isAfter(now))
                    .limit(limit)
                    .toList();
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

    private static final class FakeInboxEventRepository implements InboxEventRepository {
        private final Map<UUID, String> processed = new HashMap<>();

        @Override
        public boolean existsByEventId(UUID eventId) {
            return processed.containsKey(eventId);
        }

        @Override
        public void markProcessed(UUID eventId, String eventType, Instant processedAt) {
            processed.put(eventId, eventType);
        }
    }

    private static final class FakePendingUserStatusEventRepository
            implements PendingUserStatusEventRepository {
        private final Map<UUID, PendingUserStatusEvent> byId = new HashMap<>();

        @Override
        public void save(PendingUserStatusEvent event) {
            byId.put(event.id(), event);
        }

        @Override
        public List<PendingUserStatusEvent> findByAuthUserId(UUID authUserId) {
            return byId.values().stream()
                    .filter(e -> e.authUserId().equals(authUserId))
                    .toList();
        }

        @Override
        public void deleteByAuthUserId(UUID authUserId) {
            byId.values().removeIf(e -> e.authUserId().equals(authUserId));
        }
    }
}
