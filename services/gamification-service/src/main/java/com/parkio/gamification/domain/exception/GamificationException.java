package com.parkio.gamification.domain.exception;

/** Domain exception carrying a {@link GamificationErrorCode}; translated to HTTP in presentation. */
public class GamificationException extends RuntimeException {

    private final GamificationErrorCode errorCode;

    public GamificationException(GamificationErrorCode errorCode) {
        this(errorCode, errorCode.name());
    }

    public GamificationException(GamificationErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public GamificationErrorCode errorCode() {
        return errorCode;
    }
}
