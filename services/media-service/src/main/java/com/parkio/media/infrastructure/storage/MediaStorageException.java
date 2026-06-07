package com.parkio.media.infrastructure.storage;

/** Wraps low-level object-store failures as an unchecked infrastructure error. */
public class MediaStorageException extends RuntimeException {

    public MediaStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
