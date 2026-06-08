package com.parkio.aivalidation.domain.exception;

/** Domain exception carrying an {@link AiValidationErrorCode}; translated to HTTP in presentation. */
public class AiValidationException extends RuntimeException {

    private final AiValidationErrorCode errorCode;

    public AiValidationException(AiValidationErrorCode errorCode) {
        this(errorCode, errorCode.name());
    }

    public AiValidationException(AiValidationErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public AiValidationErrorCode errorCode() {
        return errorCode;
    }
}
