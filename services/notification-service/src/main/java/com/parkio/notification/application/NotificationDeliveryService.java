package com.parkio.notification.application;

import com.parkio.notification.application.port.DeviceTokenRepository;
import com.parkio.notification.application.port.NotificationDeliveryAttemptRepository;
import com.parkio.notification.application.port.NotificationPreferenceRepository;
import com.parkio.notification.domain.DeviceToken;
import com.parkio.notification.domain.Notification;
import com.parkio.notification.domain.NotificationChannel;
import com.parkio.notification.domain.NotificationDeliveryAttempt;
import com.parkio.notification.domain.NotificationPreference;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Delivery foundation: turns a created in-app {@link Notification} into push delivery
 * attempts (when the user allows PUSH and has active device tokens). The actual send
 * is performed asynchronously by the delivery worker; this use case only enqueues.
 *
 * <p>Behaviour (documented for ai-context/02 tunable semantics):
 * <ul>
 *   <li>Explicit PUSH preference disabled → no attempt is created.</li>
 *   <li>PUSH allowed + active device tokens → one {@code PENDING} attempt per token.</li>
 *   <li>Explicit PUSH preference enabled + no active token → a single {@code SKIPPED}
 *       attempt is recorded (reason {@code NO_ACTIVE_DEVICE_TOKEN}) for auditability.</li>
 *   <li><b>No preference row (privacy-safe default):</b> push is allowed only if the
 *       user has at least one active device token — registering a token is the explicit
 *       opt-in signal (it requires OS-level push permission). No preference and no
 *       token → nothing is recorded.</li>
 * </ul>
 * EMAIL delivery is intentionally out of scope (backlog).
 */
@Service
@Transactional
public class NotificationDeliveryService {

    static final String REASON_NO_ACTIVE_DEVICE_TOKEN = "NO_ACTIVE_DEVICE_TOKEN";

    private final NotificationPreferenceRepository preferences;
    private final DeviceTokenRepository deviceTokens;
    private final NotificationDeliveryAttemptRepository attempts;
    private final Clock clock;

    public NotificationDeliveryService(NotificationPreferenceRepository preferences,
                                       DeviceTokenRepository deviceTokens,
                                       NotificationDeliveryAttemptRepository attempts,
                                       Clock clock) {
        this.preferences = preferences;
        this.deviceTokens = deviceTokens;
        this.attempts = attempts;
        this.clock = clock;
    }

    /**
     * Enqueues push delivery attempts for a freshly created notification. Idempotent:
     * if attempts already exist for this notification's PUSH channel, nothing is created.
     */
    public List<NotificationDeliveryAttempt> enqueuePushDelivery(Notification notification) {
        UUID userId = notification.userId();
        if (attempts.existsByNotificationIdAndChannel(notification.id(), NotificationChannel.PUSH)) {
            return List.of();
        }
        List<DeviceToken> activeTokens = deviceTokens.findActiveByUserId(userId);
        // Privacy-safe default: without an explicit preference, push is allowed only
        // when the user has registered an active device token (the opt-in signal).
        boolean pushEnabled = preferences.findByUserId(userId)
                .map(NotificationPreference::pushEnabled)
                .orElse(!activeTokens.isEmpty());
        if (!pushEnabled) {
            return List.of();
        }
        Instant now = clock.instant();
        if (activeTokens.isEmpty()) {
            return List.of(attempts.save(NotificationDeliveryAttempt.skipped(
                    notification.id(), userId, NotificationChannel.PUSH, REASON_NO_ACTIVE_DEVICE_TOKEN, now)));
        }
        List<NotificationDeliveryAttempt> created = new ArrayList<>(activeTokens.size());
        for (DeviceToken token : activeTokens) {
            created.add(attempts.save(NotificationDeliveryAttempt.pending(
                    notification.id(), userId, NotificationChannel.PUSH, token.id(), now)));
        }
        return created;
    }
}
