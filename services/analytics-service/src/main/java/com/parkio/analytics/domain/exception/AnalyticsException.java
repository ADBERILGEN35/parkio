package com.parkio.analytics.domain.exception;

/** Domain exception carrying an {@link AnalyticsErrorCode}; translated to HTTP in presentation. */
public class AnalyticsException extends RuntimeException {

    private final AnalyticsErrorCode errorCode;

    public AnalyticsException(AnalyticsErrorCode errorCode) {
        this(errorCode, errorCode.name());
    }

    public AnalyticsException(AnalyticsErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public AnalyticsErrorCode errorCode() {
        return errorCode;
    }
}
