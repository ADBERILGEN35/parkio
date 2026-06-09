package com.parkio.notification.domain;

/**
 * Lifecycle of a single {@link NotificationDeliveryAttempt} on an external channel
 * (e.g. PUSH). Distinct from {@link NotificationStatus}, which tracks the in-app
 * notification's read state.
 */
public enum DeliveryStatus {
    /** Queued; awaiting a send by the delivery worker. */
    PENDING,
    /** Successfully handed off to the provider. */
    SENT,
    /** Permanently failed after exhausting retries. */
    FAILED,
    /** Intentionally not delivered (e.g. no active device token). */
    SKIPPED
}
