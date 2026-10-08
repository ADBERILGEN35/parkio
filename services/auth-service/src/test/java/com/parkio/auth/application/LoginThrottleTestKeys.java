package com.parkio.auth.application;

/** Fixed login-throttle secrets for tests (low-entropy, test-only values; never configured anywhere). */
public final class LoginThrottleTestKeys {

    public static final String SECRET_A = "test-only-login-throttle-hmac-key-aaaaaaaaaaaa";
    public static final String SECRET_B = "test-only-login-throttle-hmac-key-bbbbbbbbbbbb";
    /** The current secret alone (no rotation in progress). */
    public static final LoginThrottleKeys CURRENT = LoginThrottleKeys.of(SECRET_A, null);
    /** The tracker before a rotation to {@link #ROTATED}: entries written under secret A. */
    public static final LoginThrottleKeys OLD = CURRENT;
    /** During the overlap after rotating from A to B: writes under B, reads under B and A. */
    public static final LoginThrottleKeys ROTATED = LoginThrottleKeys.of(SECRET_B, SECRET_A);
    /** After the overlap: A removed. */
    public static final LoginThrottleKeys NEW_ONLY = LoginThrottleKeys.of(SECRET_B, null);

    private LoginThrottleTestKeys() {
    }
}
