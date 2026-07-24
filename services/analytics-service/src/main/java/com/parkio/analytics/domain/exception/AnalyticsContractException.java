package com.parkio.analytics.domain.exception;

/**
 * Non-retryable contract / payload validation failure for an upstream event.
 * Propagates to the Kafka error handler (retry budget then DLT) without creating
 * analytics rows.
 */
public class AnalyticsContractException extends RuntimeException {

    public AnalyticsContractException(String message) {
        super(message);
    }
}