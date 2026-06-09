package com.parkio.notification.application.port;

/**
 * Outcome of a {@link PushNotificationSender#send} call. On success carries the
 * provider's message id; on failure carries a sanitised reason code — never a secret
 * or the device token.
 */
public record PushSendResult(boolean delivered, String providerMessageId, String failureReason) {

    public static PushSendResult sent(String providerMessageId) {
        return new PushSendResult(true, providerMessageId, null);
    }

    public static PushSendResult failed(String failureReason) {
        return new PushSendResult(false, null, failureReason);
    }
}
