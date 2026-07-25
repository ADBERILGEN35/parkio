package com.parkio.notification.application;

import com.parkio.notification.application.command.RegisterDeviceTokenCommand;
import com.parkio.notification.application.command.UpdatePreferencesCommand;
import com.parkio.notification.application.event.AppealResolvedEvent;
import com.parkio.notification.application.event.ModerationCaseResolvedEvent;
import com.parkio.notification.application.event.ParkingSpotCreatedEvent;
import com.parkio.notification.application.event.ParkingSpotRejectedByModeratorEvent;
import com.parkio.notification.application.event.ParkingSpotRejectedEvent;
import com.parkio.notification.application.event.ParkingSessionReminderRequestedEvent;
import com.parkio.notification.application.event.PointsDeductedEvent;
import com.parkio.notification.application.event.PointsEarnedEvent;
import com.parkio.notification.application.event.TrustScoreUpdatedEvent;
import com.parkio.notification.application.event.UserLevelChangedEvent;
import com.parkio.notification.application.event.UserRestoredEvent;
import com.parkio.notification.application.event.UserSuspendedEvent;
import com.parkio.notification.application.port.DeviceTokenRepository;
import com.parkio.notification.application.port.InboxEventRepository;
import com.parkio.notification.application.port.NotificationPreferenceRepository;
import com.parkio.notification.application.port.NotificationRepository;
import com.parkio.notification.application.port.OutboxEventAppender;
import com.parkio.notification.application.port.UserLocalePort;
import com.parkio.notification.domain.DeviceToken;
import com.parkio.notification.domain.LocalizedNotificationCatalog;
import com.parkio.notification.domain.Notification;
import com.parkio.notification.domain.NotificationChannel;
import com.parkio.notification.domain.NotificationLocale;
import com.parkio.notification.domain.NotificationPreference;
import com.parkio.notification.domain.NotificationTemplate;
import com.parkio.notification.domain.NotificationType;
import com.parkio.notification.domain.event.NotificationCreatedEvent;
import com.parkio.notification.domain.exception.NotificationErrorCode;
import com.parkio.notification.domain.exception.NotificationException;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Notification use cases: consuming upstream events to create in-app notifications
 * (idempotently), and serving the user's notifications, device tokens and channel
 * preferences. Depends only on domain types and ports (ai-context/01).
 *
 * <p>This service owns notifications, device tokens, delivery records and preference
 * projections only — never profiles, auth, parking, gamification scoring or media
 * (ai-context/03). Each in-app notification is recorded as SENT and, via
 * {@link NotificationDeliveryService}, fans out PUSH delivery attempts for the user's
 * active device tokens (when push is enabled). EMAIL delivery is backlog.
 *
 * <p>Title/body for <em>new</em> notifications are rendered in the recipient's
 * {@code preferredLocale} (default {@code tr}). Structured {@code messageKey} +
 * variables are always stored in metadata so clients can re-render.
 */
@Service
@Transactional
public class NotificationApplicationService {

    private final NotificationRepository notifications;
    private final DeviceTokenRepository deviceTokens;
    private final NotificationPreferenceRepository preferences;
    private final InboxEventRepository inbox;
    private final OutboxEventAppender outbox;
    private final NotificationDeliveryService delivery;
    private final UserLocalePort userLocales;
    private final Clock clock;

    public NotificationApplicationService(NotificationRepository notifications,
                                          DeviceTokenRepository deviceTokens,
                                          NotificationPreferenceRepository preferences,
                                          InboxEventRepository inbox,
                                          OutboxEventAppender outbox,
                                          NotificationDeliveryService delivery,
                                          UserLocalePort userLocales,
                                          Clock clock) {
        this.notifications = notifications;
        this.deviceTokens = deviceTokens;
        this.preferences = preferences;
        this.inbox = inbox;
        this.outbox = outbox;
        this.delivery = delivery;
        this.userLocales = userLocales;
        this.clock = clock;
    }

    // --- Event handlers (invoked directly for now; a Kafka consumer will call them) ---

    /**
     * Spot created. Fan-out to nearby users is NOT implemented yet (location/user
     * targeting isn't ready) — documented backlog. Recorded as processed so the
     * inbox stays consistent.
     */
    public void handleParkingSpotCreated(ParkingSpotCreatedEvent event) {
        if (!claimEvent(event.eventId(), "ParkingSpotCreated")) {
            return;
        }
        // TODO(backlog): fan out NEARBY_PARKING notifications to nearby users once
        // location-based user targeting exists. No notification is created for now.
    }

    public void handleUserLevelChanged(UserLevelChangedEvent event) {
        if (!claimEvent(event.eventId(), "UserLevelChanged")) {
            return;
        }
        createInAppNotification(event.userId(), NotificationType.LEVEL_UP,
                Map.of(
                        "messageKey", "levelUp",
                        "level", Integer.toString(event.newLevel())),
                Map.of("deeplink", "/gamification"));
    }

    public void handlePointsEarned(PointsEarnedEvent event) {
        if (!claimEvent(event.eventId(), "PointsEarned")) {
            return;
        }
        createInAppNotification(event.userId(), NotificationType.POINT_EARNED,
                Map.of(
                        "messageKey", "pointEarned",
                        "points", Long.toString(event.points()),
                        "totalPoints", Long.toString(event.totalPoints())),
                Map.of("deeplink", "/gamification"));
    }

    public void handlePointsDeducted(PointsDeductedEvent event) {
        if (!claimEvent(event.eventId(), "PointsDeducted")) {
            return;
        }
        createInAppNotification(event.userId(), NotificationType.WARNING,
                Map.of(
                        "messageKey", "pointsDeducted",
                        "points", Long.toString(event.points())),
                Map.of("deeplink", "/reports"));
    }

    public void handleTrustScoreUpdated(TrustScoreUpdatedEvent event) {
        if (!claimEvent(event.eventId(), "TrustScoreUpdated")) {
            return;
        }
        String direction = event.newScore() >= event.previousScore() ? "increased" : "decreased";
        createInAppNotification(event.userId(), NotificationType.WARNING,
                Map.of(
                        "messageKey", "trustChanged",
                        "previousScore", Integer.toString(event.previousScore()),
                        "newScore", Integer.toString(event.newScore()),
                        "direction", direction),
                Map.of("deeplink", "/profile"));
    }

    public void handleParkingSpotRejected(ParkingSpotRejectedEvent event) {
        if (!claimEvent(event.eventId(), "ParkingSpotRejected")) {
            return;
        }
        createInAppNotification(event.ownerUserId(), NotificationType.WARNING,
                Map.of("messageKey", "spotRejectedIllegal"),
                Map.of("deeplink", spotDeeplink(event.parkingSpotId())));
    }

    // --- Moderation action events (parkio.moderation.action) ---

    public void handleUserSuspended(UserSuspendedEvent event) {
        if (!claimEvent(event.eventId(), "UserSuspended")) {
            return;
        }
        createInAppNotification(event.userId(), NotificationType.WARNING,
                Map.of("messageKey", "accountSuspended"),
                Map.of("deeplink", "/reports"));
    }

    public void handleUserRestored(UserRestoredEvent event) {
        if (!claimEvent(event.eventId(), "UserRestored")) {
            return;
        }
        createInAppNotification(event.userId(), NotificationType.SYSTEM,
                Map.of("messageKey", "accountRestored"),
                Map.of("deeplink", "/profile"));
    }

    /** Notifies the spot owner of a moderator rejection — only when the owner is known. */
    public void handleParkingSpotRejectedByModerator(ParkingSpotRejectedByModeratorEvent event) {
        if (!claimEvent(event.eventId(), "ParkingSpotRejectedByModerator")) {
            return;
        }
        if (event.ownerUserId() != null) {
            createInAppNotification(event.ownerUserId(), NotificationType.WARNING,
                    Map.of("messageKey", "spotRejectedByModerator"),
                    Map.of("deeplink", spotDeeplink(event.parkingSpotId())));
        }
    }

    // --- Moderation case events (parkio.moderation.case) ---

    public void handleAppealResolved(AppealResolvedEvent event) {
        if (!claimEvent(event.eventId(), "AppealResolved")) {
            return;
        }
        String outcome = event.accepted() ? "accepted" : "rejected";
        createInAppNotification(event.userId(), NotificationType.SYSTEM,
                Map.of(
                        "messageKey", "appealResolved",
                        "outcome", outcome),
                Map.of("deeplink", "/reports"));
    }

    /** Notifies the affected user when a USER-targeted case is resolved; otherwise a no-op. */
    public void handleModerationCaseResolved(ModerationCaseResolvedEvent event) {
        if (!claimEvent(event.eventId(), "ModerationCaseResolved")) {
            return;
        }
        if (ModerationCaseResolvedEvent.TARGET_TYPE_USER.equals(event.targetType()) && event.targetId() != null) {
            createInAppNotification(event.targetId(), NotificationType.SYSTEM,
                    Map.of("messageKey", "moderationCaseResolved"),
                    Map.of("deeplink", "/reports"));
        }
    }

    // --- Queries / commands ---

    @Transactional(readOnly = true)
    public List<Notification> getMyNotifications(UUID userId, int limit) {
        return notifications.findRecentByUserId(userId, limit);
    }

    /** Marks the caller's own notification read; another user's id is treated as not found. */
    public Notification markRead(UUID userId, UUID notificationId) {
        Notification notification = notifications.findById(notificationId)
                .filter(n -> n.isOwnedBy(userId))
                .orElseThrow(() -> new NotificationException(NotificationErrorCode.NOTIFICATION_NOT_FOUND));
        notification.markRead(clock.instant());
        return notifications.save(notification);
    }

    /** Registers a device token, re-activating an existing (user, token) instead of duplicating. */
    public DeviceToken registerDeviceToken(RegisterDeviceTokenCommand command) {
        Instant now = clock.instant();
        return deviceTokens.findByUserIdAndToken(command.userId(), command.token())
                .map(existing -> {
                    existing.reactivate(now);
                    return deviceTokens.save(existing);
                })
                .orElseGet(() -> deviceTokens.save(
                        DeviceToken.register(command.userId(), command.token(), command.platform(), now)));
    }

    /** Deactivates the caller's own device token; another user's id is treated as not found. */
    public void deactivateDeviceToken(UUID userId, UUID tokenId) {
        DeviceToken token = deviceTokens.findById(tokenId)
                .filter(t -> t.isOwnedBy(userId))
                .orElseThrow(() -> new NotificationException(NotificationErrorCode.DEVICE_TOKEN_NOT_FOUND));
        token.deactivate(clock.instant());
        deviceTokens.save(token);
    }

    @Transactional(readOnly = true)
    public NotificationPreference getMyPreferences(UUID userId) {
        return preferences.findByUserId(userId)
                .orElseGet(() -> NotificationPreference.createDefault(userId, clock.instant()));
    }

    public NotificationPreference updateMyPreferences(UUID userId, UpdatePreferencesCommand command) {
        Instant now = clock.instant();
        NotificationPreference preference = preferences.findByUserId(userId)
                .orElseGet(() -> NotificationPreference.createDefault(userId, now));
        preference.update(command.pushEnabled(), command.emailEnabled(), command.inAppEnabled(), now);
        return preferences.save(preference);
    }

    public Notification createSmartReturnPrompt(UUID userId) {
        return createInAppNotification(userId, NotificationType.SMART_RETURN_PROMPT,
                Map.of("messageKey", "smartReturnPrompt"),
                Map.of("action", "SMART_RETURN_TODAY", "deeplink", "/profile?section=smart-return"));
    }

    public Notification createSmartReturnParkingAvailable(UUID userId, String areaLabel) {
        return createInAppNotification(userId, NotificationType.SMART_RETURN_AVAILABLE,
                Map.of("messageKey", "smartReturnAvailable"),
                Map.of("action", "SMART_RETURN_MAP", "deeplink", "/map?smartReturn=1"));
    }

    /**
     * Creates an in-app (+ push) reminder for a stale ACTIVE parking session.
     * Deep-links to the map active-session chrome. Inbox dedupe prevents duplicates.
     */
    public void handleParkingSessionReminderRequested(ParkingSessionReminderRequestedEvent event) {
        if (!claimEvent(event.eventId(), "ParkingSessionReminderRequested")) {
            return;
        }
        String stage = event.stage() == null ? "" : event.stage();
        String messageKey = switch (stage) {
            case "SECOND" -> "parkingSessionReminder2";
            default -> "parkingSessionReminder1";
        };
        createInAppNotification(
                event.userId(),
                NotificationType.SYSTEM,
                Map.of(
                        "messageKey", messageKey,
                        "sessionId", event.sessionId() == null ? "" : event.sessionId().toString(),
                        "stage", stage),
                Map.of(
                        "action", "PARKING_SESSION_CONFIRM",
                        "deeplink", "/map?parkingSession=active"));
    }

    // --- Internals ---

    private Notification createInAppNotification(UUID userId, NotificationType type, Map<String, String> variables,
                                                Map<String, String> metadata) {
        Instant now = clock.instant();
        Map<String, String> vars = variables == null ? Map.of() : variables;
        String messageKey = resolveMessageKey(type, vars);
        NotificationLocale locale = userLocales.resolvePreferredLocale(userId);
        NotificationTemplate.RenderedContent content = LocalizedNotificationCatalog.render(messageKey, locale, vars);

        // Variables first; explicit metadata wins on colliding keys such as deeplink.
        // Always persist messageKey so clients can re-render without relying on stored locale.
        Map<String, String> storedMetadata = new LinkedHashMap<>();
        storedMetadata.putAll(vars);
        storedMetadata.put("messageKey", messageKey);
        storedMetadata.put("locale", locale.code());
        if (metadata != null && !metadata.isEmpty()) {
            storedMetadata.putAll(metadata);
        }
        Notification notification = notifications.save(Notification.create(
                userId, type, NotificationChannel.IN_APP, content.title(), content.body(), storedMetadata, now));
        outbox.append(NotificationCreatedEvent.of(notification, now));
        delivery.enqueuePushDelivery(notification);
        return notification;
    }

    private static String resolveMessageKey(NotificationType type, Map<String, String> variables) {
        String fromVariables = variables.get("messageKey");
        if (fromVariables != null && !fromVariables.isBlank()) {
            return fromVariables;
        }
        String fromType = LocalizedNotificationCatalog.defaultMessageKey(type);
        return fromType != null ? fromType : "notification";
    }

    private boolean claimEvent(UUID eventId, String eventType) {
        return inbox.tryClaim(eventId, eventType, clock.instant());
    }

    private static String spotDeeplink(UUID parkingSpotId) {
        return parkingSpotId == null ? "/my-spots" : "/spots/" + parkingSpotId;
    }
}
