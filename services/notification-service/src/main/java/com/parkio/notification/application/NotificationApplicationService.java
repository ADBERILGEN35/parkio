package com.parkio.notification.application;

import com.parkio.notification.application.command.RegisterDeviceTokenCommand;
import com.parkio.notification.application.command.UpdatePreferencesCommand;
import com.parkio.notification.application.event.AppealResolvedEvent;
import com.parkio.notification.application.event.ModerationCaseResolvedEvent;
import com.parkio.notification.application.event.ParkingSpotCreatedEvent;
import com.parkio.notification.application.event.ParkingSpotRejectedByModeratorEvent;
import com.parkio.notification.application.event.ParkingSpotRejectedEvent;
import com.parkio.notification.application.event.PointsDeductedEvent;
import com.parkio.notification.application.event.PointsEarnedEvent;
import com.parkio.notification.application.event.UserLevelChangedEvent;
import com.parkio.notification.application.event.UserRestoredEvent;
import com.parkio.notification.application.event.UserSuspendedEvent;
import com.parkio.notification.application.port.DeviceTokenRepository;
import com.parkio.notification.application.port.InboxEventRepository;
import com.parkio.notification.application.port.NotificationPreferenceRepository;
import com.parkio.notification.application.port.NotificationRepository;
import com.parkio.notification.application.port.NotificationTemplateRepository;
import com.parkio.notification.application.port.OutboxEventAppender;
import com.parkio.notification.domain.DeviceToken;
import com.parkio.notification.domain.Notification;
import com.parkio.notification.domain.NotificationChannel;
import com.parkio.notification.domain.NotificationPreference;
import com.parkio.notification.domain.NotificationTemplate;
import com.parkio.notification.domain.NotificationType;
import com.parkio.notification.domain.event.NotificationCreatedEvent;
import com.parkio.notification.domain.exception.NotificationErrorCode;
import com.parkio.notification.domain.exception.NotificationException;
import java.time.Clock;
import java.time.Instant;
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
 * (ai-context/03). Real push/email delivery is not implemented: in-app notifications
 * are recorded as SENT; external channels would start PENDING for a future relay.
 */
@Service
@Transactional
public class NotificationApplicationService {

    private final NotificationRepository notifications;
    private final DeviceTokenRepository deviceTokens;
    private final NotificationTemplateRepository templates;
    private final NotificationPreferenceRepository preferences;
    private final InboxEventRepository inbox;
    private final OutboxEventAppender outbox;
    private final Clock clock;

    public NotificationApplicationService(NotificationRepository notifications,
                                          DeviceTokenRepository deviceTokens,
                                          NotificationTemplateRepository templates,
                                          NotificationPreferenceRepository preferences,
                                          InboxEventRepository inbox,
                                          OutboxEventAppender outbox,
                                          Clock clock) {
        this.notifications = notifications;
        this.deviceTokens = deviceTokens;
        this.templates = templates;
        this.preferences = preferences;
        this.inbox = inbox;
        this.outbox = outbox;
        this.clock = clock;
    }

    // --- Event handlers (invoked directly for now; a Kafka consumer will call them) ---

    /**
     * Spot created. Fan-out to nearby users is NOT implemented yet (location/user
     * targeting isn't ready) — documented backlog. Recorded as processed so the
     * inbox stays consistent.
     */
    public void handleParkingSpotCreated(ParkingSpotCreatedEvent event) {
        if (alreadyProcessed(event.eventId())) {
            return;
        }
        // TODO(backlog): fan out NEARBY_PARKING notifications to nearby users once
        // location-based user targeting exists. No notification is created for now.
        markProcessed(event.eventId(), "ParkingSpotCreated");
    }

    public void handleUserLevelChanged(UserLevelChangedEvent event) {
        if (alreadyProcessed(event.eventId())) {
            return;
        }
        createInAppNotification(event.userId(), NotificationType.LEVEL_UP,
                Map.of("level", Integer.toString(event.newLevel())));
        markProcessed(event.eventId(), "UserLevelChanged");
    }

    public void handlePointsEarned(PointsEarnedEvent event) {
        if (alreadyProcessed(event.eventId())) {
            return;
        }
        createInAppNotification(event.userId(), NotificationType.POINT_EARNED,
                Map.of("points", Long.toString(event.points()),
                        "totalPoints", Long.toString(event.totalPoints())));
        markProcessed(event.eventId(), "PointsEarned");
    }

    public void handlePointsDeducted(PointsDeductedEvent event) {
        if (alreadyProcessed(event.eventId())) {
            return;
        }
        createInAppNotification(event.userId(), NotificationType.WARNING,
                Map.of("message", "You lost " + event.points() + " points (penalty)."));
        markProcessed(event.eventId(), "PointsDeducted");
    }

    public void handleParkingSpotRejected(ParkingSpotRejectedEvent event) {
        if (alreadyProcessed(event.eventId())) {
            return;
        }
        createInAppNotification(event.ownerUserId(), NotificationType.WARNING,
                Map.of("message", "Your parking spot was rejected as illegal or risky."));
        markProcessed(event.eventId(), "ParkingSpotRejected");
    }

    // --- Moderation action events (parkio.moderation.action) ---

    public void handleUserSuspended(UserSuspendedEvent event) {
        if (alreadyProcessed(event.eventId())) {
            return;
        }
        createInAppNotification(event.userId(), NotificationType.WARNING,
                Map.of("message", "Your account has been suspended by moderation."));
        markProcessed(event.eventId(), "UserSuspended");
    }

    public void handleUserRestored(UserRestoredEvent event) {
        if (alreadyProcessed(event.eventId())) {
            return;
        }
        createInAppNotification(event.userId(), NotificationType.SYSTEM,
                Map.of("message", "Your account has been restored."));
        markProcessed(event.eventId(), "UserRestored");
    }

    /** Notifies the spot owner of a moderator rejection — only when the owner is known. */
    public void handleParkingSpotRejectedByModerator(ParkingSpotRejectedByModeratorEvent event) {
        if (alreadyProcessed(event.eventId())) {
            return;
        }
        if (event.ownerUserId() != null) {
            createInAppNotification(event.ownerUserId(), NotificationType.WARNING,
                    Map.of("message", "Your parking spot was rejected by a moderator."));
        }
        markProcessed(event.eventId(), "ParkingSpotRejectedByModerator");
    }

    // --- Moderation case events (parkio.moderation.case) ---

    public void handleAppealResolved(AppealResolvedEvent event) {
        if (alreadyProcessed(event.eventId())) {
            return;
        }
        String outcome = event.accepted() ? "accepted" : "rejected";
        createInAppNotification(event.userId(), NotificationType.SYSTEM,
                Map.of("message", "Your appeal was " + outcome + "."));
        markProcessed(event.eventId(), "AppealResolved");
    }

    /** Notifies the affected user when a USER-targeted case is resolved; otherwise a no-op. */
    public void handleModerationCaseResolved(ModerationCaseResolvedEvent event) {
        if (alreadyProcessed(event.eventId())) {
            return;
        }
        if (ModerationCaseResolvedEvent.TARGET_TYPE_USER.equals(event.targetType()) && event.targetId() != null) {
            createInAppNotification(event.targetId(), NotificationType.SYSTEM,
                    Map.of("message", "A moderation case about your account was resolved."));
        }
        markProcessed(event.eventId(), "ModerationCaseResolved");
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

    // --- Internals ---

    private Notification createInAppNotification(UUID userId, NotificationType type, Map<String, String> variables) {
        Instant now = clock.instant();
        NotificationTemplate.RenderedContent content = templates.findByType(type)
                .map(template -> template.render(variables))
                .orElseGet(() -> fallbackContent(type));
        Notification notification = notifications.save(Notification.create(
                userId, type, NotificationChannel.IN_APP, content.title(), content.body(), now));
        outbox.append(NotificationCreatedEvent.of(notification, now));
        return notification;
    }

    private static NotificationTemplate.RenderedContent fallbackContent(NotificationType type) {
        return new NotificationTemplate.RenderedContent("Notification",
                "You have a new " + type.name().toLowerCase().replace('_', ' ') + " notification.");
    }

    private boolean alreadyProcessed(UUID eventId) {
        return inbox.existsByEventId(eventId);
    }

    private void markProcessed(UUID eventId, String eventType) {
        inbox.markProcessed(eventId, eventType, clock.instant());
    }
}
