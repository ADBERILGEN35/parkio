package com.parkio.auth.domain.exception;

import java.util.Objects;

/**
 * Single domain exception carrying a stable {@link AuthErrorCode}. Keeping one
 * exception type with a code enum yields consistent API errors without a class
 * explosion.
 */
public class AuthException extends RuntimeException {

    private final AuthErrorCode errorCode;

    public AuthException(AuthErrorCode errorCode) {
        super(errorCode.defaultMessage());
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
    }

    public AuthErrorCode errorCode() {
        return errorCode;
    }
}
