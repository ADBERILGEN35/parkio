package com.parkio.user.application;

import com.parkio.user.application.command.CreateProfileCommand;
import com.parkio.user.application.command.UpdatePreferencesCommand;
import com.parkio.user.application.command.UpdateProfileCommand;
import com.parkio.user.application.command.UpsertVehicleCommand;
import com.parkio.user.application.event.UserRegisteredEvent;
import com.parkio.user.application.event.UserRestoredEvent;
import com.parkio.user.application.event.UserSuspendedEvent;
import com.parkio.user.application.port.InboxEventRepository;
import com.parkio.user.application.port.OutboxEventAppender;
import com.parkio.user.application.port.UserPreferenceRepository;
import com.parkio.user.application.port.UserProfileRepository;
import com.parkio.user.application.port.UserTrustProfileRepository;
import com.parkio.user.application.port.UserTrustScoreHistoryRepository;
import com.parkio.user.application.port.UserVehicleProfileRepository;
import com.parkio.user.application.result.AccountStatusView;
import com.parkio.user.application.result.PublicProfileView;
import com.parkio.user.domain.UserPreference;
import com.parkio.user.domain.UserProfile;
import com.parkio.user.domain.UserTrustProfile;
import com.parkio.user.domain.UserTrustScoreHistory;
import com.parkio.user.domain.UserVehicleProfile;
import com.parkio.user.domain.event.UserProfileCreatedEvent;
import com.parkio.user.domain.exception.UserErrorCode;
import com.parkio.user.domain.exception.UserException;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * User-profile use cases: profile creation (incl. from a UserRegistered event),
 * reading/updating the caller's profile, preferences, vehicle and stats, and the
 * privacy-safe public profile. Depends only on domain types and ports; framework
 * concerns (JPA, Kafka) sit behind the ports in infrastructure.
 *
 * <p>auth-service owns credentials/roles/tokens; this service owns none of those
 * (ai-context/03). Trust/gamification fields are projections, not computed here.
 */
@Service
@Transactional
public class UserApplicationService {

    private final UserProfileRepository profiles;
    private final UserPreferenceRepository preferences;
    private final UserVehicleProfileRepository vehicles;
    private final UserTrustProfileRepository trustProfiles;
    private final UserTrustScoreHistoryRepository trustHistory;
    private final OutboxEventAppender outbox;
    private final InboxEventRepository inbox;
    private final Clock clock;

    public UserApplicationService(UserProfileRepository profiles,
                                  UserPreferenceRepository preferences,
                                  UserVehicleProfileRepository vehicles,
                                  UserTrustProfileRepository trustProfiles,
                                  UserTrustScoreHistoryRepository trustHistory,
                                  OutboxEventAppender outbox,
                                  InboxEventRepository inbox,
                                  Clock clock) {
        this.profiles = profiles;
        this.preferences = preferences;
        this.vehicles = vehicles;
        this.trustProfiles = trustProfiles;
        this.trustHistory = trustHistory;
        this.outbox = outbox;
        this.inbox = inbox;
        this.clock = clock;
    }

    /**
     * Creates a user profile plus its default preferences and trust projection,
     * recording an initial trust-history entry and an outbox event — all in one
     * transaction.
     */
    public UserProfile createProfile(CreateProfileCommand command) {
        if (profiles.existsByAuthUserId(command.authUserId())) {
            throw new UserException(UserErrorCode.PROFILE_ALREADY_EXISTS);
        }

        Instant now = clock.instant();
        UserProfile profile = profiles.save(UserProfile.create(
                command.authUserId(), command.email(), command.displayName(),
                command.phoneNumber(), command.city(), now));

        preferences.save(UserPreference.createDefault(profile.id()));
        UserTrustProfile trust = trustProfiles.save(UserTrustProfile.createDefault(profile.id()));
        trustHistory.save(UserTrustScoreHistory.record(
                profile.id(), null, trust.trustScore(), UserTrustScoreHistory.REASON_INITIAL, now));

        outbox.append(UserProfileCreatedEvent.of(profile.id(), profile.authUserId(), now));
        return profile;
    }

    /**
     * Idempotent handler for the auth-service {@code UserRegistered} event: creates a
     * default profile if none exists yet. Deduplicated by {@code eventId} via the inbox
     * (ai-context/06) so at-least-once Kafka redelivery is safe; the {@code
     * existsByAuthUserId} guard additionally tolerates a missing/duplicate inbox row.
     * Runs in the surrounding transaction so the profile and the inbox record commit
     * together. Invoked by the Kafka consumer in {@code infrastructure.messaging}.
     */
    public void handleUserRegistered(UserRegisteredEvent event) {
        if (inbox.existsByEventId(event.eventId())) {
            return; // already processed; skip redelivery
        }
        if (!profiles.existsByAuthUserId(event.userId())) {
            String displayName = deriveDisplayName(event.email());
            createProfile(new CreateProfileCommand(event.userId(), event.email(), displayName, null, null));
        }
        inbox.markProcessed(event.eventId(), UserRegisteredEvent.TYPE, clock.instant());
    }

    /**
     * Idempotent handler for moderation's {@code UserSuspended}: flips the profile's
     * account status to {@code SUSPENDED}. Auth credentials are not touched here
     * (ai-context/03). A no-op if the profile is unknown. Inbox-deduplicated.
     */
    public void handleUserSuspended(UserSuspendedEvent event) {
        if (inbox.existsByEventId(event.eventId())) {
            return;
        }
        profiles.findByAuthUserId(event.userId()).ifPresent(profile -> {
            profile.suspend();
            profiles.save(profile);
        });
        inbox.markProcessed(event.eventId(), UserSuspendedEvent.TYPE, clock.instant());
    }

    /**
     * Idempotent handler for moderation's {@code UserRestored}: flips the profile's
     * account status back to {@code ACTIVE}. A no-op if the profile is unknown.
     */
    public void handleUserRestored(UserRestoredEvent event) {
        if (inbox.existsByEventId(event.eventId())) {
            return;
        }
        profiles.findByAuthUserId(event.userId()).ifPresent(profile -> {
            profile.restore();
            profiles.save(profile);
        });
        inbox.markProcessed(event.eventId(), UserRestoredEvent.TYPE, clock.instant());
    }

    @Transactional(readOnly = true)
    public UserProfile getMyProfile(UUID authUserId) {
        return requireProfile(authUserId);
    }

    /**
     * Resolves the live account status for the gateway's per-request enforcement
     * check. Returns only id + status (no profile data). Throws
     * {@link UserErrorCode#PROFILE_NOT_FOUND} (→ 404) when no profile exists yet, so
     * the gateway treats an unprovisioned/unknown account as non-active (fail closed).
     */
    @Transactional(readOnly = true)
    public AccountStatusView getAccountStatus(UUID authUserId) {
        return AccountStatusView.of(requireProfile(authUserId));
    }

    public UserProfile updateMyProfile(UUID authUserId, UpdateProfileCommand command) {
        UserProfile profile = requireProfile(authUserId);
        profile.update(command.displayName(), command.phoneNumber(), command.city());
        return profiles.save(profile);
    }

    @Transactional(readOnly = true)
    public UserPreference getMyPreferences(UUID authUserId) {
        return requirePreferences(requireProfile(authUserId).id());
    }

    public UserPreference updateMyPreferences(UUID authUserId, UpdatePreferencesCommand command) {
        UserPreference preference = requirePreferences(requireProfile(authUserId).id());
        preference.update(command.preferredRadiusMeters(), command.notificationsEnabled());
        return preferences.save(preference);
    }

    /** The vehicle profile is optional; absence is a valid (empty) result. */
    @Transactional(readOnly = true)
    public Optional<UserVehicleProfile> getMyVehicle(UUID authUserId) {
        return vehicles.findByUserProfileId(requireProfile(authUserId).id());
    }

    public UserVehicleProfile upsertMyVehicle(UUID authUserId, UpsertVehicleCommand command) {
        UUID profileId = requireProfile(authUserId).id();
        UserVehicleProfile vehicle = vehicles.findByUserProfileId(profileId)
                .map(existing -> {
                    existing.replace(command.vehicleType(), command.plate());
                    return existing;
                })
                .orElseGet(() -> UserVehicleProfile.create(profileId, command.vehicleType(), command.plate()));
        return vehicles.save(vehicle);
    }

    @Transactional(readOnly = true)
    public UserTrustProfile getMyStats(UUID authUserId) {
        return requireTrustProfile(requireProfile(authUserId).id());
    }

    /**
     * Looks up another user's public profile by their platform-wide id, which is
     * the {@code authUserId} — never the internal {@code user_profiles.id}
     * (ai-context/03: services reference each other by the shared identity).
     */
    @Transactional(readOnly = true)
    public PublicProfileView getPublicProfile(UUID authUserId) {
        UserProfile profile = requireProfile(authUserId);
        return PublicProfileView.of(profile, requireTrustProfile(profile.id()));
    }

    private UserProfile requireProfile(UUID authUserId) {
        return profiles.findByAuthUserId(authUserId)
                .orElseThrow(() -> new UserException(UserErrorCode.PROFILE_NOT_FOUND));
    }

    private UserPreference requirePreferences(UUID userProfileId) {
        return preferences.findByUserProfileId(userProfileId)
                .orElseThrow(() -> new UserException(UserErrorCode.PROFILE_NOT_FOUND));
    }

    private UserTrustProfile requireTrustProfile(UUID userProfileId) {
        return trustProfiles.findByUserProfileId(userProfileId)
                .orElseThrow(() -> new UserException(UserErrorCode.PROFILE_NOT_FOUND));
    }

    private static String deriveDisplayName(String email) {
        String base = email == null ? null : email.trim();
        int at = base == null ? -1 : base.indexOf('@');
        String local = at > 0 ? base.substring(0, at) : base;
        if (local == null || local.length() < UserProfile.DISPLAY_NAME_MIN) {
            return "user";
        }
        return local.length() > UserProfile.DISPLAY_NAME_MAX
                ? local.substring(0, UserProfile.DISPLAY_NAME_MAX)
                : local;
    }
}
