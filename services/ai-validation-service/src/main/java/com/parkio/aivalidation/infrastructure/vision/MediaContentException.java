package com.parkio.aivalidation.infrastructure.vision;

/**
 * Media bytes could not be obtained for vision analysis. {@link Reason} feeds the
 * error metrics; the classifier always fails closed (UNCERTAIN) on this exception.
 * Never carries URLs, image bytes, or secrets in its message.
 */
public class MediaContentException extends RuntimeException {

    public enum Reason {
        NOT_FOUND,
        TOO_LARGE,
        UNSUPPORTED_TYPE,
        UNAVAILABLE
    }

    private final Reason reason;

    public MediaContentException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public MediaContentException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
