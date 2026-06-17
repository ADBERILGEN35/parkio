package com.parkio.media.application.port;

/**
 * Thrown by a {@link MediaScanner} adapter when the scan could not be completed
 * (scanner unreachable, timeout, protocol or size error). It deliberately carries no
 * verdict: the application maps it to a fail-closed rejection so unscanned bytes never
 * become servable.
 */
public class MediaScannerUnavailableException extends RuntimeException {

    public MediaScannerUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public MediaScannerUnavailableException(String message) {
        super(message);
    }
}
