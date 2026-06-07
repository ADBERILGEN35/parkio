package com.parkio.user.domain.exception;

import java.util.Objects;

/**
 * Single domain exception carrying a stable {@link UserErrorCode}. One exception
 * type with a code enum yields consistent API errors without a class explosion.
 */
public class UserException extends RuntimeException {

    private final UserErrorCode errorCode;

    public UserException(UserErrorCode errorCode) {
        super(errorCode.defaultMessage());
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
    }

    public UserErrorCode errorCode() {
        return errorCode;
    }
}
