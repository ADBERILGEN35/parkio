package com.parkio.notification.domain.exception;

/** Domain exception carrying a {@link NotificationErrorCode}; translated to HTTP in presentation. */
public class NotificationException extends RuntimeException {

    private final NotificationErrorCode errorCode;

    public NotificationException(NotificationErrorCode errorCode) {
        this(errorCode, errorCode.name());
    }

    public NotificationException(NotificationErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public NotificationErrorCode errorCode() {
        return errorCode;
    }
}
