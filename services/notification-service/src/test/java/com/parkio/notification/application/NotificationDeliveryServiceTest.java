package com.parkio.notification.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.notification.application.port.DeviceTokenRepository;
import com.parkio.notification.application.port.NotificationDeliveryAttemptRepository;
import com.parkio.notification.application.port.NotificationPreferenceRepository;
import com.parkio.notification.domain.DeliveryStatus;
import com.parkio.notification.domain.DevicePlatform;
import com.parkio.notification.domain.DeviceToken;
import com.parkio.notification.domain.Notification;
import com.parkio.notification.domain.NotificationChannel;
import com.parkio.notification.domain.NotificationDeliveryAttempt;
import com.parkio.notification.domain.NotificationPreference;
import com.parkio.notification.domain.NotificationType;
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
 * Behavioural unit tests for {@link NotificationDeliveryService} using in-memory fakes
 * — no Spring, no DB. Covers the documented push-enqueue semantics.
 */
class NotificationDeliveryServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-07T12:00:00Z");

    private FakePreferenceRepository preferences;
    private FakeDeviceTokenRepository deviceTokens;
    private FakeDeliveryAttemptRepository attempts;
    private NotificationDeliveryService service;

    @BeforeEach
    void setUp() {
        preferences = new FakePreferenceRepository();
        deviceTokens = new FakeDeviceTokenRepository();
        attempts = new FakeDeliveryAttemptRepository();
        service = new NotificationDeliveryService(preferences, deviceTokens, attempts,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void activeTokenWithoutPreferenceIsTheOptInSignal() {
        UUID user = UUID.randomUUID();
        deviceTokens.save(DeviceToken.register(user, "token-1", DevicePlatform.ANDROID, NOW));
        deviceTokens.save(DeviceToken.register(user, "token-2", DevicePlatform.IOS, NOW));
        Notification notification = notificationFor(user);

        List<NotificationDeliveryAttempt> created = service.enqueuePushDelivery(notification);

        assertThat(created).hasSize(2)
                .allSatisfy(a -> {
                    assertThat(a.channel()).isEqualTo(NotificationChannel.PUSH);
                    assertThat(a.status()).isEqualTo(DeliveryStatus.PENDING);
                    assertThat(a.deviceTokenId()).isNotNull();
                    assertThat(a.nextAttemptAt()).isEqualTo(NOW); // due immediately
                });
    }

    @Test
    void noPreferenceAndNoTokenCreatesNothing() {
        // Privacy-safe default: without an explicit preference or a registered device
        // token there is no opt-in signal, so no attempt (not even SKIPPED) is recorded.
        UUID user = UUID.randomUUID();
        Notification notification = notificationFor(user);

        List<NotificationDeliveryAttempt> created = service.enqueuePushDelivery(notification);

        assertThat(created).isEmpty();
        assertThat(attempts.byId).isEmpty();
    }

    @Test
    void recordsSkippedAttemptWhenPushExplicitlyEnabledButNoActiveToken() {
        UUID user = UUID.randomUUID();
        preferences.save(new NotificationPreference(user, true, true, true, NOW, NOW, null));
        Notification notification = notificationFor(user);

        List<NotificationDeliveryAttempt> created = service.enqueuePushDelivery(notification);

        assertThat(created).singleElement().satisfies(a -> {
            assertThat(a.status()).isEqualTo(DeliveryStatus.SKIPPED);
            assertThat(a.deviceTokenId()).isNull();
            assertThat(a.failureReason()).isEqualTo("NO_ACTIVE_DEVICE_TOKEN");
        });
    }

    @Test
    void inactiveTokenIsNotTargeted() {
        UUID user = UUID.randomUUID();
        preferences.save(new NotificationPreference(user, true, true, true, NOW, NOW, null));
        DeviceToken token = DeviceToken.register(user, "token-1", DevicePlatform.WEB, NOW);
        token.deactivate(NOW);
        deviceTokens.save(token);
        Notification notification = notificationFor(user);

        List<NotificationDeliveryAttempt> created = service.enqueuePushDelivery(notification);

        assertThat(created).singleElement()
                .satisfies(a -> assertThat(a.status()).isEqualTo(DeliveryStatus.SKIPPED));
    }

    @Test
    void disabledPushPreferenceCreatesNoAttempt() {
        UUID user = UUID.randomUUID();
        deviceTokens.save(DeviceToken.register(user, "token-1", DevicePlatform.ANDROID, NOW));
        preferences.save(new NotificationPreference(user, false, true, true, NOW, NOW, null));
        Notification notification = notificationFor(user);

        List<NotificationDeliveryAttempt> created = service.enqueuePushDelivery(notification);

        assertThat(created).isEmpty();
        assertThat(attempts.byId).isEmpty();
    }

    @Test
    void isIdempotentWhenAttemptsAlreadyExist() {
        UUID user = UUID.randomUUID();
        deviceTokens.save(DeviceToken.register(user, "token-1", DevicePlatform.ANDROID, NOW));
        Notification notification = notificationFor(user);

        service.enqueuePushDelivery(notification);
        List<NotificationDeliveryAttempt> second = service.enqueuePushDelivery(notification);

        assertThat(second).isEmpty();
        assertThat(attempts.byId).hasSize(1);
    }

    private static Notification notificationFor(UUID user) {
        return Notification.create(user, NotificationType.LEVEL_UP, NotificationChannel.IN_APP,
                "Level up!", "You reached level 2.", NOW);
    }

    // --- Fakes -----------------------------------------------------------

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
            return new ArrayList<>(byId.values()).stream()
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
}
