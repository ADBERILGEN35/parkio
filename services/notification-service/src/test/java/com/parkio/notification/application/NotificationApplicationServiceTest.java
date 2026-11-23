package com.parkio.notification.application;

import com.parkio.notification.application.port.NotificationOwnerFence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.parkio.notification.application.command.RegisterDeviceTokenCommand;
import com.parkio.notification.application.command.UpdatePreferencesCommand;
import com.parkio.notification.application.event.ParkingSpotRejectedByModeratorEvent;
import com.parkio.notification.application.event.ParkingSpotRejectedEvent;
import com.parkio.notification.application.event.PointsEarnedEvent;
import com.parkio.notification.application.event.TrustScoreUpdatedEvent;
import com.parkio.notification.application.event.UserLevelChangedEvent;
import com.parkio.notification.application.port.DeviceTokenHandover;
import com.parkio.notification.application.port.DeviceTokenRepository;
import com.parkio.notification.application.port.InboxEventRepository;
import com.parkio.notification.application.port.NotificationDeliveryAttemptRepository;
import com.parkio.notification.application.port.NotificationPreferenceRepository;
import com.parkio.notification.application.port.NotificationRepository;
import com.parkio.notification.application.port.OutboxEventAppender;
import com.parkio.notification.application.port.PushMessage;
import com.parkio.notification.application.port.PushSendResult;
import com.parkio.notification.application.port.UserLocalePort;
import com.parkio.notification.domain.DeliveryStatus;
import com.parkio.notification.domain.DevicePlatform;
import com.parkio.notification.domain.DeviceToken;
import com.parkio.notification.domain.Notification;
import com.parkio.notification.domain.NotificationChannel;
import com.parkio.notification.domain.NotificationDeliveryAttempt;
import com.parkio.notification.domain.NotificationLocale;
import com.parkio.notification.domain.NotificationPreference;
import com.parkio.notification.domain.NotificationStatus;
import com.parkio.notification.domain.NotificationType;
import com.parkio.notification.domain.event.NotificationCreatedEvent;
import com.parkio.notification.domain.exception.NotificationErrorCode;
import com.parkio.notification.domain.exception.NotificationException;
import com.parkio.notification.infrastructure.delivery.PushDeliveryWorker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Behavioural unit tests for {@link NotificationApplicationService} using in-memory
 * fake ports — no Spring, no DB. Default locale is product default ({@code tr}).
 */
class NotificationApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-07T12:00:00Z");
    private static final String SHARED_DEVICE_TOKEN = "ExponentPushToken[shared-device]";

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private FakeNotificationRepository notifications;
    private FakeDeviceTokenRepository deviceTokens;
    private FakePreferenceRepository preferences;
    private FakeInboxRepository inbox;
    private FakeOutbox outbox;
    private FakeDeliveryAttemptRepository deliveryAttempts;
    private FakeUserLocalePort userLocales;
    private FakeOwnerFence ownerFence;
    private NotificationApplicationService service;

    @BeforeEach
    void setUp() {
        notifications = new FakeNotificationRepository();
        deviceTokens = new FakeDeviceTokenRepository();
        preferences = new FakePreferenceRepository();
        inbox = new FakeInboxRepository();
        outbox = new FakeOutbox();
        deliveryAttempts = new FakeDeliveryAttemptRepository();
        userLocales = new FakeUserLocalePort();
        ownerFence = new FakeOwnerFence();
        NotificationDeliveryService delivery =
                new NotificationDeliveryService(preferences, deviceTokens, deliveryAttempts, clock);
        service = new NotificationApplicationService(notifications, deviceTokens, deviceTokens, preferences,
                inbox, outbox, delivery, userLocales, ownerFence, clock);
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
    void registeringATokenAnotherUserHoldsDeactivatesTheirRow() {
        UUID previousHolder = UUID.randomUUID();
        UUID newHolder = UUID.randomUUID();
        DeviceToken previous = register(previousHolder, SHARED_DEVICE_TOKEN);

        DeviceToken current = register(newHolder, SHARED_DEVICE_TOKEN);

        assertThat(deviceTokens.byId.get(previous.id()).active()).isFalse();
        assertThat(current.active()).isTrue();
        assertThat(deviceTokens.findActiveByUserId(previousHolder)).isEmpty();
        assertThat(deviceTokens.findActiveByUserId(newHolder)).extracting(DeviceToken::id)
                .containsExactly(current.id());
    }

    @Test
    void handOverLeavesOtherTokensActive() {
        UUID previousHolder = UUID.randomUUID();
        UUID bystander = UUID.randomUUID();
        DeviceToken handedOver = register(previousHolder, SHARED_DEVICE_TOKEN);
        DeviceToken previousHoldersOtherDevice = register(previousHolder, "ExponentPushToken[other-device]");
        DeviceToken bystandersDevice = register(bystander, "ExponentPushToken[bystander-device]");

        register(UUID.randomUUID(), SHARED_DEVICE_TOKEN);

        assertThat(deviceTokens.byId.get(handedOver.id()).active()).isFalse();
        assertThat(deviceTokens.byId.get(previousHoldersOtherDevice.id()).active()).isTrue();
        assertThat(deviceTokens.byId.get(bystandersDevice.id()).active()).isTrue();
    }

    @Test
    void reRegisteringOwnActiveTokenKeepsItActive() {
        UUID user = UUID.randomUUID();
        DeviceToken first = register(user, SHARED_DEVICE_TOKEN);

        DeviceToken again = register(user, SHARED_DEVICE_TOKEN);

        assertThat(again.id()).isEqualTo(first.id());
        assertThat(deviceTokens.byId.get(first.id()).active()).isTrue();
    }

    @Test
    void tokenReturnsToThePreviousHolderWhenItRegistersAgain() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        DeviceToken firstRow = register(first, SHARED_DEVICE_TOKEN);
        DeviceToken secondRow = register(second, SHARED_DEVICE_TOKEN);

        DeviceToken back = register(first, SHARED_DEVICE_TOKEN);

        assertThat(back.id()).isEqualTo(firstRow.id());
        assertThat(back.active()).isTrue();
        assertThat(deviceTokens.byId.get(secondRow.id()).active()).isFalse();
    }

    @Test
    void erasedUserCannotTakeOverAToken() {
        UUID holder = UUID.randomUUID();
        UUID erased = UUID.randomUUID();
        DeviceToken held = register(holder, SHARED_DEVICE_TOKEN);
        ownerFence.erased.add(erased);

        assertThatThrownBy(() -> register(erased, SHARED_DEVICE_TOKEN))
                .isInstanceOf(NotificationException.class)
                .extracting(e -> ((NotificationException) e).errorCode())
                .isEqualTo(NotificationErrorCode.DEVICE_TOKEN_NOT_FOUND);
        assertThat(deviceTokens.byId).containsOnlyKeys(held.id());
        assertThat(deviceTokens.byId.get(held.id()).active()).isTrue();
    }

    @Test
    void deliveryWorkerSendsNoPushOfThePreviousHolderToAHandedOverToken() {
        UUID previousHolder = UUID.randomUUID();
        UUID newHolder = UUID.randomUUID();
        register(previousHolder, SHARED_DEVICE_TOKEN);
        // Queued while the previous holder still held the token.
        service.handleUserLevelChanged(new UserLevelChangedEvent(UUID.randomUUID(), previousHolder, 1, 2, 100, NOW));

        register(newHolder, SHARED_DEVICE_TOKEN);
        service.handleUserLevelChanged(new UserLevelChangedEvent(UUID.randomUUID(), previousHolder, 2, 3, 200, NOW));
        service.handleUserLevelChanged(new UserLevelChangedEvent(UUID.randomUUID(), newHolder, 1, 2, 100, NOW));
        List<PushMessage> sent = new ArrayList<>();
        PushDeliveryWorker worker = new PushDeliveryWorker(deliveryAttempts, deviceTokens, notifications,
                message -> {
                    sent.add(message);
                    return PushSendResult.sent("provider-" + sent.size());
                },
                clock, new SimpleMeterRegistry(), true, 100, 5, 30_000L);

        worker.processPendingBatch();

        assertThat(sent).singleElement()
                .satisfies(message -> assertThat(message.deviceToken()).isEqualTo(SHARED_DEVICE_TOKEN));
        assertThat(deliveryAttempts.byId.values())
                .filteredOn(attempt -> attempt.status() == DeliveryStatus.SENT)
                .singleElement()
                .satisfies(attempt -> assertThat(attempt.userId()).isEqualTo(newHolder));
        assertThat(deliveryAttempts.byId.values())
                .filteredOn(attempt -> attempt.userId().equals(previousHolder))
                .singleElement()
                .satisfies(attempt -> {
                    assertThat(attempt.status()).isNotEqualTo(DeliveryStatus.SENT);
                    assertThat(attempt.failureReason()).isEqualTo("DEVICE_TOKEN_INACTIVE");
                });
    }

    @Test
    void levelChangedEventCreatesLevelUpNotificationInDefaultTurkish() {
        UUID user = UUID.randomUUID();

        service.handleUserLevelChanged(new UserLevelChangedEvent(UUID.randomUUID(), user, 1, 3, 120, NOW));

        List<Notification> userNotifications = notifications.findRecentByUserId(user, 10);
        assertThat(userNotifications).singleElement().satisfies(n -> {
            assertThat(n.type()).isEqualTo(NotificationType.LEVEL_UP);
            assertThat(n.status()).isEqualTo(NotificationStatus.SENT);
            assertThat(n.title()).isEqualTo("Seviye atladınız!");
            assertThat(n.body()).contains("3. seviyeye");
            assertThat(n.metadata()).containsEntry("level", "3")
                    .containsEntry("messageKey", "levelUp")
                    .containsEntry("locale", "tr")
                    .containsEntry("deeplink", "/gamification");
        });
        assertThat(outbox.events).singleElement().isInstanceOf(NotificationCreatedEvent.class);
    }

    @Test
    void levelChangedEventCreatesEnglishContentWhenLocaleIsEn() {
        UUID user = UUID.randomUUID();
        userLocales.locale = NotificationLocale.EN;

        service.handleUserLevelChanged(new UserLevelChangedEvent(UUID.randomUUID(), user, 1, 3, 120, NOW));

        assertThat(notifications.findRecentByUserId(user, 10)).singleElement()
                .satisfies(n -> {
                    assertThat(n.title()).isEqualTo("Level up!");
                    assertThat(n.body()).contains("level 3");
                    assertThat(n.metadata()).containsEntry("locale", "en")
                            .containsEntry("messageKey", "levelUp");
                });
    }

    @Test
    void pointsEarnedEventCreatesPointNotification() {
        UUID user = UUID.randomUUID();

        service.handlePointsEarned(new PointsEarnedEvent(UUID.randomUUID(), user, 20, "PARKING_VERIFIED",
                20, UUID.randomUUID(), NOW));

        assertThat(notifications.findRecentByUserId(user, 10)).singleElement()
                .satisfies(n -> {
                    assertThat(n.type()).isEqualTo(NotificationType.POINT_EARNED);
                    assertThat(n.body()).contains("20 puan");
                    assertThat(n.metadata()).containsEntry("messageKey", "pointEarned");
                });
    }

    @Test
    void trustScoreUpdatedEventCreatesSystemNotification() {
        UUID user = UUID.randomUUID();

        service.handleTrustScoreUpdated(new TrustScoreUpdatedEvent(
                UUID.randomUUID(), user, 100, 85, "MODERATION_PENALTY", UUID.randomUUID(), NOW));

        assertThat(notifications.findRecentByUserId(user, 10)).singleElement()
                .satisfies(n -> {
                    assertThat(n.type()).isEqualTo(NotificationType.WARNING);
                    assertThat(n.body()).contains("100");
                    assertThat(n.body()).contains("85");
                    assertThat(n.metadata()).containsEntry("messageKey", "trustChanged")
                            .containsEntry("previousScore", "100")
                            .containsEntry("newScore", "85")
                            .containsEntry("direction", "decreased")
                            .containsEntry("deeplink", "/profile");
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
        UUID spotId = UUID.randomUUID();

        service.handleParkingSpotRejectedByModerator(new ParkingSpotRejectedByModeratorEvent(
                UUID.randomUUID(), spotId, owner, UUID.randomUUID(), UUID.randomUUID(),
                "ILLEGAL_OR_RISKY", NOW));

        assertThat(notifications.findRecentByUserId(owner, 10)).singleElement()
                .satisfies(n -> {
                    assertThat(n.type()).isEqualTo(NotificationType.WARNING);
                    assertThat(n.metadata()).containsEntry("deeplink", "/spots/" + spotId)
                            .containsEntry("messageKey", "spotRejectedByModerator");
                });
    }

    @Test
    void moderatorRejectionWithoutOwnerCreatesNoNotification() {
        service.handleParkingSpotRejectedByModerator(new ParkingSpotRejectedByModeratorEvent(
                UUID.randomUUID(), UUID.randomUUID(), null, UUID.randomUUID(), UUID.randomUUID(),
                "ILLEGAL_OR_RISKY", NOW));

        assertThat(notifications.byId).isEmpty();
    }

    @Test
    void parkingSpotRejectedWarnsOwnerWithSpotDeeplink() {
        UUID owner = UUID.randomUUID();
        UUID spotId = UUID.randomUUID();

        service.handleParkingSpotRejected(new ParkingSpotRejectedEvent(
                UUID.randomUUID(), spotId, owner, UUID.randomUUID(), "ILLEGAL_OR_RISKY", NOW));

        assertThat(notifications.findRecentByUserId(owner, 10)).singleElement()
                .satisfies(n -> {
                    assertThat(n.type()).isEqualTo(NotificationType.WARNING);
                    assertThat(n.metadata()).containsEntry("deeplink", "/spots/" + spotId)
                            .containsEntry("messageKey", "spotRejectedIllegal");
                });
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
                    assertThat(n.title()).contains("araç");
                    assertThat(n.body()).contains("park kontrolü");
                    assertThat(n.metadata()).containsEntry("action", "SMART_RETURN_TODAY")
                            .containsEntry("messageKey", "smartReturnPrompt")
                            .containsEntry("deeplink", "/profile?section=smart-return");
                });
    }

    @Test
    void createsSmartReturnAvailabilityNotificationWithoutHomeAddress() {
        UUID user = UUID.randomUUID();

        service.createSmartReturnParkingAvailable(user, "Exact Home Street 1");

        assertThat(notifications.findRecentByUserId(user, 10)).singleElement()
                .satisfies(n -> {
                    assertThat(n.type()).isEqualTo(NotificationType.SMART_RETURN_AVAILABLE);
                    assertThat(n.body()).contains("Kayıtlı ev alanınızın");
                    assertThat(n.body()).doesNotContain("Exact Home Street 1");
                    assertThat(n.body()).doesNotContain("38.4237");
                    assertThat(n.metadata()).containsEntry("action", "SMART_RETURN_MAP")
                            .containsEntry("messageKey", "smartReturnAvailable")
                            .containsEntry("deeplink", "/map?smartReturn=1");
                    assertThat(n.metadata().values()).noneMatch(value -> value.contains("Exact Home Street 1"));
                });
    }

    private DeviceToken register(UUID userId, String token) {
        return service.registerDeviceToken(new RegisterDeviceTokenCommand(userId, token, DevicePlatform.ANDROID));
    }

    // --- Fakes -----------------------------------------------------------

    private static final class FakeOwnerFence implements NotificationOwnerFence {
        private final Set<UUID> erased = new HashSet<>();

        @Override
        public boolean admitWrite(UUID userId) {
            return !erased.contains(userId);
        }

        @Override
        public void holdForErasure(UUID userId) {
            // Locking is an adapter concern; the fake only models the erased set.
        }
    }

    private static final class FakeUserLocalePort implements UserLocalePort {
        NotificationLocale locale = NotificationLocale.DEFAULT;

        @Override
        public NotificationLocale resolvePreferredLocale(UUID userId) {
            return locale;
        }
    }

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

    private static final class FakeDeviceTokenRepository implements DeviceTokenRepository, DeviceTokenHandover {
        private final Map<UUID, DeviceToken> byId = new HashMap<>();

        @Override
        public int deactivateForOtherUsers(String token, UUID userId, Instant now) {
            List<DeviceToken> heldByOthers = byId.values().stream()
                    .filter(t -> t.token().equals(token) && !t.userId().equals(userId) && t.active())
                    .toList();
            heldByOthers.forEach(t -> t.deactivate(now));
            return heldByOthers.size();
        }

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
        private final java.util.Set<UUID> claimed = java.util.concurrent.ConcurrentHashMap.newKeySet();

        @Override
        public boolean tryClaim(UUID eventId, String eventType, Instant processedAt) {
            return claimed.add(eventId);
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
