package com.parkio.auth.domain.exception;

/** Internal marker for lockout metrics; serialized exactly like INVALID_CREDENTIALS. */
public class LoginLockedException extends AuthException {

    public LoginLockedException() {
        super(AuthErrorCode.INVALID_CREDENTIALS);
    }
}
