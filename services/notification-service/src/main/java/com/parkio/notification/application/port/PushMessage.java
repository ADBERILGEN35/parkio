package com.parkio.notification.application.port;

import com.parkio.notification.domain.DevicePlatform;

import java.util.Map;

/**
 * Immutable push payload handed to a {@link PushNotificationSender}. Carries the raw
 * device token only in memory at send time — it is never persisted on a delivery
 * attempt or logged (ai-context/07).
 */
public record PushMessage(
        String deviceToken,
        DevicePlatform platform,
        String title,
        String body,
        Map<String, String> data) {

    public PushMessage(String deviceToken, DevicePlatform platform, String title, String body) {
        this(deviceToken, platform, title, body, Map.of());
    }
}
