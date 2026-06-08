package com.parkio.notification.domain.exception;

/** Stable, domain-level error codes (mapped to HTTP in presentation). */
public enum NotificationErrorCode {
    MISSING_USER_ID,
    NOTIFICATION_NOT_FOUND,
    DEVICE_TOKEN_NOT_FOUND
}
