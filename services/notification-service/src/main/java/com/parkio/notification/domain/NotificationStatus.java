package com.parkio.notification.domain;

/** Delivery/read lifecycle of a notification. */
public enum NotificationStatus {
    /** Queued for an external channel (push/email), not yet sent. */
    PENDING,
    /** Delivered (in-app is immediately SENT; external channels once a relay sends). */
    SENT,
    /** Delivery attempt failed. */
    FAILED,
    /** The recipient has read it. */
    READ
}
