package com.parkio.auth.application;

/** Rate limiter for email-verification resend requests. */
public interface VerificationResendLimiter {

    boolean tryAcquire(String normalizedEmail);
}
