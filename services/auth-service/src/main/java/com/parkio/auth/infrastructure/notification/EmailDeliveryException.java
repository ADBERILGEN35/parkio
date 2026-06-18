package com.parkio.auth.infrastructure.notification;

/** Raised when a configured transactional email provider cannot accept a message. */
public class EmailDeliveryException extends RuntimeException {

    public EmailDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
