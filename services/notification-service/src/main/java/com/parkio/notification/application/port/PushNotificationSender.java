package com.parkio.notification.application.port;

/**
 * Outbound port for delivering a push notification to a single device. Implementations
 * live in {@code infrastructure} (e.g. a no-op sender for local/dev, a future FCM
 * adapter). Must never throw on provider errors — return {@link PushSendResult#failed}
 * with a sanitised reason instead.
 */
public interface PushNotificationSender {

    PushSendResult send(PushMessage message);
}
