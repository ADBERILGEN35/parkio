package com.parkio.auth.domain.exception;

/**
 * Internal marker for the login throttle (per-client progressive delay or account-wide soft
 * delay, CL-F15); counted by the {@code login_lockouts} metric and serialized exactly like
 * INVALID_CREDENTIALS so a throttled attempt is indistinguishable from a wrong password.
 */
public class LoginLockedException extends AuthException {

    public LoginLockedException() {
        super(AuthErrorCode.INVALID_CREDENTIALS);
    }
}
