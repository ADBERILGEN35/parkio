package com.parkio.auth.application;

/** Rate limiter for password reset requests. */
public interface PasswordResetLimiter {

    boolean tryAcquire(String normalizedEmail);
}
