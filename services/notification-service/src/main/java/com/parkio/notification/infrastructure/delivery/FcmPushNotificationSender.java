package com.parkio.notification.infrastructure.delivery;

import com.parkio.notification.application.port.PushMessage;
import com.parkio.notification.application.port.PushNotificationSender;
import com.parkio.notification.application.port.PushSendResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Placeholder Firebase Cloud Messaging sender. It marks where a real FCM adapter will
 * live but performs <strong>no</strong> network call and requires <strong>no</strong>
 * credentials — disabled by default and never committed with secrets.
 *
 * <p>Activated only when {@code parkio.notification.delivery.push.provider=fcm-disabled}.
 * Because real FCM is backlog, every send returns a sanitised
 * {@code FCM_NOT_CONFIGURED} failure (no secret leakage) so the worker records the
 * attempt as failed rather than silently dropping it. Config (project id, credentials
 * location) is externalised; supply it only once the real integration is built.
 */
@Component
@ConditionalOnProperty(name = "parkio.notification.delivery.push.provider", havingValue = "fcm-disabled")
public class FcmPushNotificationSender implements PushNotificationSender {

    private static final Logger log = LoggerFactory.getLogger(FcmPushNotificationSender.class);

    private final boolean enabled;
    private final boolean projectConfigured;

    public FcmPushNotificationSender(
            @Value("${parkio.notification.delivery.push.fcm.enabled:false}") boolean enabled,
            @Value("${parkio.notification.delivery.push.fcm.project-id:}") String projectId) {
        this.enabled = enabled;
        this.projectConfigured = projectId != null && !projectId.isBlank();
    }

    @Override
    public PushSendResult send(PushMessage message) {
        log.warn("FCM push delivery is a placeholder and not implemented (enabled={}, projectConfigured={}). "
                + "Recording attempt as failed.", enabled, projectConfigured);
        return PushSendResult.failed("FCM_NOT_CONFIGURED");
    }
}
