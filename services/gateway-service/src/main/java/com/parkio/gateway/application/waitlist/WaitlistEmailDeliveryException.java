package com.parkio.gateway.application.waitlist;

/**
 * Raised when a waitlist row was (or remains) durably stored but outbound email failed.
 * Clients may retry; the pending record is not rolled back.
 */
public class WaitlistEmailDeliveryException extends RuntimeException {

    public WaitlistEmailDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }

    public WaitlistEmailDeliveryException(String message) {
        super(message);
    }
}
