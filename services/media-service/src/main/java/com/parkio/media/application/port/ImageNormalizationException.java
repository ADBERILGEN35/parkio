package com.parkio.media.application.port;

/** Raised when uploaded image bytes cannot be safely decoded or re-encoded. */
public class ImageNormalizationException extends RuntimeException {

    public ImageNormalizationException(String message) {
        super(message);
    }

    public ImageNormalizationException(String message, Throwable cause) {
        super(message, cause);
    }
}
