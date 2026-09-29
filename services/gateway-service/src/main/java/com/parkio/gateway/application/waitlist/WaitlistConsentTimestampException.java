package com.parkio.gateway.application.waitlist;

/**
 * Client-asserted consent event time failed bounded clock-skew policy.
 * Distinct from email format validation so clients can show accurate copy.
 */
public class WaitlistConsentTimestampException extends RuntimeException {

    public WaitlistConsentTimestampException(String message) {
        super(message);
    }
}
