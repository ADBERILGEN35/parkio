package com.parkio.moderation.domain.exception;

/** Domain exception carrying a {@link ModerationErrorCode}; translated to HTTP in presentation. */
public class ModerationException extends RuntimeException {

    private final ModerationErrorCode errorCode;

    public ModerationException(ModerationErrorCode errorCode) {
        this(errorCode, errorCode.name());
    }

    public ModerationException(ModerationErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ModerationErrorCode errorCode() {
        return errorCode;
    }
}
