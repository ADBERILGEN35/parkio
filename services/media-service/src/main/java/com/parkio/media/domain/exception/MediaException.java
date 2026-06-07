package com.parkio.media.domain.exception;

/** Domain exception carrying a {@link MediaErrorCode}; translated to HTTP in presentation. */
public class MediaException extends RuntimeException {

    private final MediaErrorCode errorCode;

    public MediaException(MediaErrorCode errorCode) {
        this(errorCode, errorCode.name());
    }

    public MediaException(MediaErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public MediaErrorCode errorCode() {
        return errorCode;
    }
}
