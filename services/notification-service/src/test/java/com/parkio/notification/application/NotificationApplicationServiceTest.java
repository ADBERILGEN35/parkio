package com.parkio.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.parkio.notification.application.command.RegisterDeviceTokenCommand;
import com.parkio.notification.application.command.UpdatePreferencesCommand;
import com.parkio.notification.application.event.ParkingSpotRejectedByModeratorEvent;
import com.parkio.notification.application.event.PointsEarnedEvent;
import com.parkio.notification.application.event.UserLevelChangedEvent;
import com.parkio.notification.application.port.DeviceTokenRepository;
import com.parkio.notification.application.port.InboxEventRepository;
import com.parkio.notification.application.port.NotificationDeliveryAttemptRepository;
import com.parkio.notification.application.port.NotificationPreferenceRepository;
import com.parkio.notification.application.port.NotificationRepository;
import com.parkio.notification.application.port.NotificationTemplateRepository;
import com.parkio.notification.application.port.OutboxEventAppender;
import com.parkio.notification.domain.DeliveryStatus;
import com.parkio.notification.domain.DevicePlatform;
import com.parkio.notification.domain.DeviceToken;
import com.parkio.notification.domain.Notification;
import com.parkio.notification.domain.NotificationChannel;
import com.parkio.notification.domain.NotificationDeliveryAttempt;
import com.parkio.notification.domain.NotificationPreference;
import com.parkio.notification.domain.NotificationStatus;
import com.parkio.notification.domain.NotificationTemplate;
import com.parkio.notification.domain.NotificationType;
import com.parkio.notification.domain.event.NotificationCreatedEvent;
import com.parkio.notification.domain.exception.NotificationErrorCode;
import com.parkio.notification.domain.exception.NotificationException;
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
 * Behavioural unit tests for {@link NotificationApplicationService} using in-memory
 * fake ports (seeded with the same templates Flyway provides) — no Spring, no DB.
 */
class NotificationApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-07T12:00:00Z");

    private FakeNotificationRepository notifications;
    private FakeDeviceTokenRepository deviceTokens;
    private FakeTemplateRepository templates;
    private FakePreferenceRepository preferences;
    private FakeInboxRepository inbox;
    private FakeOutbox outbox;
    private FakeDeliveryAttemptRepository deliveryAttempts;
    private NotificationApplicationService service;

    @BeforeEach
    void setUp() {
        notifications = new FakeNotificationRepository();
        deviceTokens = new FakeDeviceTokenRepository();
        templates = new FakeTemplateRepository();
        preferences = new FakePreferenceRepository();
        inbox = new FakeInboxRepository();
        outbox = new FakeOutbox();
        deliveryAttempts = new FakeDeliveryAttemptRepository();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        NotificationDeliveryService delivery =
                new NotificationDeliveryService(preferences, deviceTokens, deliveryAttempts, clock);
        service = new NotificationApplicationService(notifications, deviceTokens, templates, preferences,
                inbox, outbox, delivery, clock);
    }

    @Test
    void registersDeviceTokenAsActive() {
        UUID user = UUID.randomUUID();

        DeviceToken token = service.registerDeviceToken(
                new RegisterDeviceTokenCommand(user, "token-abc", DevicePlatform.ANDROID));

        assertThat(token.active()).isTrue();
        assertThat(deviceTokens.byId).hasSize(1);
    }

    @Test
    void duplicateTokenDoesNotCreateASecondRow() {
        UUID user = UUID.randomUUID();
        service.registerDeviceToken(new RegisterDeviceTokenCommand(user, "token-abc", DevicePlatform.IOS));

        service.registerDeviceToken(new RegisterDeviceTokenCommand(user, "token-abc", DevicePlatform.IOS));

        assertThat(deviceTokens.byId).hasSize(1);
    }

    @Test
    void reRegisteringADeactivatedTokenReactivatesIt() {
        UUID user = UUID.randomUUID();
        DeviceToken token = service.registerDeviceToken(
                new RegisterDeviceTokenCommand(user, "token-abc", DevicePlatform.WEB));
        service.deactivateDeviceToken(user, token.id());

        DeviceToken reactivated = service.registerDeviceToken(
                new RegisterDeviceTokenCommand(user, "token-abc", DevicePlatform.WEB));

        assertThat(reactivated.id()).isEqualTo(token.id());
        assertThat(reactivated.active()).isTrue();
        assertThat(deviceTokens.byId).hasSize(1);
    }

    @Test
    void deactivatesDeviceToken() {
        UUID user = UUID.randomUUID();
        DeviceToken token = service.registerDeviceToken(
                new RegisterDeviceTokenCommand(user, "token-abc", DevicePlatform.ANDROID));

        service.deactivateDeviceToken(user, token.id());

        assertThat(deviceTokens.byId.get(token.id()).active()).isFalse();
    }

    @Test
    void levelChangedEventCreatesLevelUpNotification() {
        UUID user = UUID.randomUUID();

        service.handleUserLevelChanged(new UserLevelChangedEvent(UUID.randomUUID(), user, 1, 3, 120, NOW));

        List<Notification> userNotifications = notifications.findRecentByUserId(user, 10);
        assertThat(userNotifications).singleElement().satisfies(n -> {
            assertThat(n.type()).isEqualTo(NotificationType.LEVEL_UP);
            assertThat(n.status()).isEqualTo(NotificationStatus.SENT);
            assertThat(n.body()).contains("level 3");
        });
        assertThat(outbox.events).singleElement().isInstanceOf(NotificationCreatedEvent.class);
    }

    @Test
    void pointsEarnedEventCreatesPointNotification() {
        UUID user = UUID.randomUUID();

        service.handlePointsEarned(new PointsEarnedEvent(UUID.randomUUID(), user, 20, "PARKING_VERIFIED",
                20, UUID.randomUUID(), NOW));

        assertThat(notifications.findRecentByUserId(user, 10)).singleElement()
                .satisfies(n -> {
                    assertThat(n.type()).isEqualTo(NotificationType.POINT_EARNED);
                    assertThat(n.body()).contains("20 points");
                });
    }

    @Test
    void notificationCreationEnqueuesPushAttemptWhenActiveTokenExists() {
        UUID user = UUID.randomUUID();
        service.registerDeviceToken(new RegisterDeviceTokenCommand(user, "token-abc", DevicePlatform.ANDROID));

        service.handleUserLevelChanged(new UserLevelChangedEvent(UUID.randomUUID(), user, 1, 2, 100, NOW));

        assertThat(notifications.findRecentByUserId(user, 10)).hasSize(1);
        assertThat(deliveryAttempts.byId.values()).singleElement().satisfies(a -> {
            assertThat(a.channel()).isEqualTo(NotificationChannel.PUSH);
            assertThat(a.status()).isEqualTo(DeliveryStatus.PENDING);
            assertThat(a.userId()).isEqualTo(user);
        });
    }

    @Test
    void duplicateEventIsSkippedViaInbox() {
        UUID user = UUID.randomUUID();
        UserLevelChangedEvent event = new UserLevelChangedEvent(UUID.randomUUID(), user, 1, 2, 100, NOW);

        service.handleUserLevelChanged(event);
        service.handleUserLevelChanged(event); // redelivery

        assertThat(notifications.findRecentByUserId(user, 10)).hasSize(1);
    }

    @Test
    void moderatorRejectionWarnsOwnerWhenOwnerKnown() {
        UUID owner = UUID.randomUUID();

        service.handleParkingSpotRejectedByModerator(new ParkingSpotRejectedByModeratorEvent(
                UUID.randomUUID(), UUID.randomUUID(), owner, UUID.randomUUID(), UUID.randomUUID(),
                "ILLEGAL_OR_RISKY", NOW));

        assertThat(notifications.findRecentByUserId(owner, 10)).singleElement()
                .satisfies(n -> assertThat(n.type()).isEqualTo(NotificationType.WARNING));
    }

    @Test
    void moderatorRejectionWithoutOwnerCreatesNoNotification() {
        service.handleParkingSpotRejectedByModerator(new ParkingSpotRejectedByModeratorEvent(
                UUID.randomUUID(), UUID.randomUUID(), null, UUID.randomUUID(), UUID.randomUUID(),
                "ILLEGAL_OR_RISKY", NOW));

        assertThat(notifications.byId).isEmpty();
    }

    @Test
    void marksOwnNotificationRead() {
        UUID user = UUID.randomUUID();
        service.handleUserLevelChanged(new UserLevelChangedEvent(UUID.randomUUID(), user, 1, 2, 100, NOW));
        Notification created = notifications.findRecentByUserId(user, 10).get(0);

        Notification read = service.markRead(user, created.id());

        assertThat(read.status()).isEqualTo(NotificationStatus.READ);
        assertThat(read.readAt()).isEqualTo(NOW);
    }

    @Test
    void cannotMarkAnotherUsersNotificationRead() {
        UUID owner = UUID.randomUUID();
        UUID otherUser = UUID.randomUUID();
        service.handleUserLevelChanged(new UserLevelChangedEvent(UUID.randomUUID(), owner, 1, 2, 100, NOW));
        Notification created = notifications.findRecentByUserId(owner, 10).get(0);

        assertThatThrownBy(() -> service.markRead(otherUser, created.id()))
                .isInstanceOf(NotificationException.class)
                .extracting(e -> ((NotificationException) e).errorCode())
                .isEqualTo(NotificationErrorCode.NOTIFICATION_NOT_FOUND);
    }

    @Test
    void preferencesDefaultThenUpdate() {
        UUID user = UUID.randomUUID();

        NotificationPreference defaults = service.getMyPreferences(user);
        assertThat(defaults.pushEnabled()).isTrue();
        assertThat(defaults.emailEnabled()).isTrue();
        assertThat(defaults.inAppEnabled()).isTrue();
        assertThat(preferences.byUser).isEmpty(); // read did not persist

        NotificationPreference updated = service.updateMyPreferences(user,
                new UpdatePreferencesCommand(false, null, null));

        assertThat(updated.pushEnabled()).isFalse();
        assertThat(updated.emailEnabled()).isTrue(); // unchanged
        assertThat(preferences.byUser).containsKey(user);
    }

    @Test
    void createsSmartReturnPromptNotification() {
        UUID user = UUID.randomUUID();

        service.createSmartReturnPrompt(user);

        assertThat(notifications.findRecentByUserId(user, 10)).singleElement()
                .satisfies(n -> {
                    assertThat(n.type()).isEqualTo(NotificationType.SMART_RETURN_PROMPT);
                    assertThat(n.title()).contains("driving today");
                    assertThat(n.body()).contains("parking check");
                });
    }

    @Test
    void createsSmartReturnAvailabilityNotificationWithoutHomeAddress() {
        UUID user = UUID.randomUUID();

        service.createSmartReturnParkingAvailable(user, "Exact Home Street 1");

        assertThat(notifications.findRecentByUserId(user, 10)).singleElement()
                .satisfies(n -> {
                    assertThat(n.type()).isEqualTo(NotificationType.SMART_RETURN_AVAILABLE);
                    assertThat(n.body()).contains("saved home area");
                    assertThat(n.body()).doesNotContain("Exact Home Street 1");
                    assertThat(n.body()).doesNotContain("38.4237");
                });
    }

    // --- Fakes -----------------------------------------------------------

    private static final class FakeNotificationRepository implements NotificationRepository {
        private final Map<UUID, Notification> byId = new HashMap<>();

        @Override
        public Notification save(Notification notification) {
            byId.put(notification.id(), notification);
            return notification;
        }

        @Override
        public Optional<Notification> findById(UUID id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public List<Notification> findRecentByUserId(UUID userId, int limit) {
            return byId.values().stream()
                    .filter(n -> n.userId().equals(userId))
                    .limit(limit)
                    .toList();
        }
    }

    private static final class FakeDeviceTokenRepository implements DeviceTokenRepository {
        private final Map<UUID, DeviceToken> byId = new HashMap<>();

        @Override
        public DeviceToken save(DeviceToken deviceToken) {
            byId.put(deviceToken.id(), deviceToken);
            return deviceToken;
        }

        @Override
        public Optional<DeviceToken> findById(UUID id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public Optional<DeviceToken> findByUserIdAndToken(UUID userId, String token) {
            return byId.values().stream()
                    .filter(t -> t.userId().equals(userId) && t.token().equals(token))
                    .findFirst();
        }

        @Override
        public List<DeviceToken> findActiveByUserId(UUID userId) {
            return byId.values().stream()
                    .filter(t -> t.userId().equals(userId) && t.active())
                    .toList();
        }
    }

    private static final class FakeDeliveryAttemptRepository implements NotificationDeliveryAttemptRepository {
        private final Map<UUID, NotificationDeliveryAttempt> byId = new HashMap<>();

        @Override
        public NotificationDeliveryAttempt save(NotificationDeliveryAttempt attempt) {
            byId.put(attempt.id(), attempt);
            return attempt;
        }

        @Override
        public List<NotificationDeliveryAttempt> claimDue(Instant now, int limit) {
            return byId.values().stream()
                    .filter(a -> a.status() == DeliveryStatus.PENDING)
                    .filter(a -> a.nextAttemptAt() != null && !a.nextAttemptAt().isAfter(now))
                    .limit(limit)
                    .toList();
        }

        @Override
        public boolean existsByNotificationIdAndChannel(UUID notificationId, NotificationChannel channel) {
            return byId.values().stream()
                    .anyMatch(a -> a.notificationId().equals(notificationId) && a.channel() == channel);
        }
    }

    private static final class FakeTemplateRepository implements NotificationTemplateRepository {
        private final Map<NotificationType, NotificationTemplate> byType = new HashMap<>();

        FakeTemplateRepository() {
            byType.put(NotificationType.LEVEL_UP,
                    new NotificationTemplate(NotificationType.LEVEL_UP, "Level up!",
                            "Congratulations — you reached level {level}."));
            byType.put(NotificationType.POINT_EARNED,
                    new NotificationTemplate(NotificationType.POINT_EARNED, "You earned points",
                            "You earned {points} points. Total: {totalPoints}."));
            byType.put(NotificationType.WARNING,
                    new NotificationTemplate(NotificationType.WARNING, "Heads up", "{message}"));
            byType.put(NotificationType.SMART_RETURN_PROMPT,
                    new NotificationTemplate(NotificationType.SMART_RETURN_PROMPT, "Are you driving today?",
                            "Tell Parkio if you want a parking check before you return."));
            byType.put(NotificationType.SMART_RETURN_AVAILABLE,
                    new NotificationTemplate(NotificationType.SMART_RETURN_AVAILABLE, "Parking may be available",
                            "{message}"));
        }

        @Override
        public Optional<NotificationTemplate> findByType(NotificationType type) {
            return Optional.ofNullable(byType.get(type));
        }
    }

    private static final class FakePreferenceRepository implements NotificationPreferenceRepository {
        private final Map<UUID, NotificationPreference> byUser = new HashMap<>();

        @Override
        public NotificationPreference save(NotificationPreference preference) {
            byUser.put(preference.userId(), preference);
            return preference;
        }

        @Override
        public Optional<NotificationPreference> findByUserId(UUID userId) {
            return Optional.ofNullable(byUser.get(userId));
        }
    }

    private static final class FakeInboxRepository implements InboxEventRepository {
        private final java.util.Set<UUID> processed = new java.util.HashSet<>();

        @Override
        public boolean existsByEventId(UUID eventId) {
            return processed.contains(eventId);
        }

        @Override
        public void markProcessed(UUID eventId, String eventType, Instant processedAt) {
            processed.add(eventId);
        }
    }

    private static final class FakeOutbox implements OutboxEventAppender {
        private final List<NotificationCreatedEvent> events = new ArrayList<>();

        @Override
        public void append(NotificationCreatedEvent event) {
            events.add(event);
        }
    }
}
