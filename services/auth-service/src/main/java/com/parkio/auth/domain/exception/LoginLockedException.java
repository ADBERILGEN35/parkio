package com.parkio.auth.domain.exception;

/**
 * Internal marker for the login throttle (per-client progressive delay or the account-wide delay
 * for unknown clients, CL-F15); counted by the {@code login_lockouts} metric and serialized exactly like
 * INVALID_CREDENTIALS so a throttled attempt is indistinguishable from a wrong password.
 */
public class LoginLockedException extends AuthException {

    public LoginLockedException() {
        super(AuthErrorCode.INVALID_CREDENTIALS);
    }
}
